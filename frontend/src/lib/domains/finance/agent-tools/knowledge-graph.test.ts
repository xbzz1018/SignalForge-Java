import { describe, expect, it, vi } from 'vitest';
import { createKnowledgeGraphTool, type KnowledgeGraphResult } from './knowledge-graph';
import { createFinancePiAgentTools } from './factory';
const projectId = '00000000-0000-0000-0000-000000000001';
const releaseId = '11111111-1111-1111-1111-111111111111';
const quote = '公司计划召开业绩说明会，向投资者介绍经营情况。';
const result: KnowledgeGraphResult = {
  schemaVersion: '1.0', projectId, securityId: 'SH:600519', releaseId,
  snapshotHash: 'b'.repeat(64), fetchedAt: '2026-11-10T00:00:00Z',
  status: 'READY', pointInTimeSafe: false, truncated: false,
  claims: [{
    id: releaseId + ':claim-one', candidateId: releaseId, documentId: releaseId,
    predicate: 'EVENT', object: '业绩说明会', modality: 'PLANNED',
    quote, pageNumber: 1, chunkId: 'p1-c1',
    quoteStart: 0, quoteEnd: quote.length, sourceUrl: 'https://pdf.dfcfw.com/test.pdf',
    publishedAt: null, observedAt: '2026-11-10T00:00:00Z', documentHash: 'a'.repeat(64),
  }], warnings: ['NOT_HISTORICAL_EVIDENCE'],
};
function context(signal = new AbortController().signal) {
  return { runId: 'test', turn: 1, toolCallId: 'call', operationId: 'operation', signal };
}
describe('published knowledge graph tool', () => {
  it('keeps project authority outside model arguments and retains literal citations', async () => {
    const query = vi.fn().mockResolvedValue(result);
    const tool = createKnowledgeGraphTool({ projectId, query });
    const output = await tool.execute({ securityId: '600519', releaseId }, context());
    expect(query).toHaveBeenCalledWith(projectId, { securityId: 'SH:600519', releaseId, limit: 5 }, expect.any(AbortSignal));
    expect(output.ok).toBe(true);
    if (output.ok) expect(output.data).toEqual(result);
    expect(tool.effect).toBe('read');
    expect(() => tool.parseInput!({ securityId: '600519', projectId: releaseId })).toThrow();
    expect(() => tool.parseInput!({ securityId: '600519', cypher: 'MATCH (n)' })).toThrow();
  });
  it('enforces stock identity and bounded input', () => {
    const tool = createKnowledgeGraphTool({ projectId, query: vi.fn() });
    for (const input of [
      { securityId: 'SH:000001' }, { securityId: '510300' }, { securityId: '600519', limit: 9 },
      { securityId: '600519', predicates: ['SUPPLIER'] }, { securityId: '600519', releaseId: '../admin' },
    ]) expect(() => tool.parseInput!(input)).toThrow();
  });
  it('rejects cross-project and drifted release responses', async () => {
    const query = vi.fn().mockResolvedValue({ ...result, projectId: releaseId });
    const tool = createKnowledgeGraphTool({ projectId, query });
    expect(await tool.execute({ securityId: '600519' }, context()))
      .toMatchObject({ ok: false, error: { code: 'KNOWLEDGE_RESPONSE_REJECTED' } });
    query.mockResolvedValue({ ...result, releaseId: projectId });
    expect(await tool.execute({ securityId: '600519', releaseId }, context())).toMatchObject({ ok: false });
  });
  it('keeps no-data explicit and limits repeated calls', async () => {
    const query = vi.fn().mockResolvedValue({ ...result, status: 'NO_PUBLISHED_CLAIMS', releaseId: null,
      snapshotHash: null, fetchedAt: null, claims: [] });
    const tool = createKnowledgeGraphTool({ projectId, query, maxRequests: 1 });
    expect(await tool.execute({ securityId: '600519' }, context()))
      .toMatchObject({ ok: true, data: { status: 'NO_PUBLISHED_CLAIMS' } });
    expect(await tool.execute({ securityId: '600519' }, context()))
      .toMatchObject({ ok: false, error: { code: 'KNOWLEDGE_CALL_BUDGET_EXCEEDED' } });
    expect(query).toHaveBeenCalledTimes(1);
  });
  it('rejects malformed or inconsistent citations before exposing them to the model', async () => {
    const query = vi.fn();
    const tool = createKnowledgeGraphTool({ projectId, query, maxRequests: 16 });
    const claim = result.claims[0];
    for (const response of [
      null, { ...result, status: 'UNKNOWN' }, { ...result, status: 'NO_MATCHING_CLAIMS' },
      { ...result, claims: [{ ...claim, quoteEnd: 999 }] },
      { ...result, claims: [{ ...claim, modality: 'CONFIRMED_BY_AI' }] },
      { ...result, claims: [{ ...claim, sourceUrl: 'javascript:alert(1)' }] },
      { ...result, claims: [claim, claim] },
    ]) {
      query.mockResolvedValue(response);
      expect(await tool.execute({ securityId: '600519' }, context()))
        .toMatchObject({ ok: false, error: { code: 'KNOWLEDGE_RESPONSE_REJECTED' } });
    }
  });
  it('keeps dependency errors distinct from a successful empty result', async () => {
    const query = vi.fn().mockRejectedValue(new Error('graph temporarily unavailable'));
    const output = await createKnowledgeGraphTool({ projectId, query }).execute({ securityId: '600519' }, context());
    expect(output.ok).toBe(false);
  });
  it('propagates cancellation without sending a request', async () => {
    const query = vi.fn(), controller = new AbortController();
    controller.abort();
    expect(await createKnowledgeGraphTool({ projectId, query }).execute({ securityId: '600519' }, context(controller.signal)))
      .toMatchObject({ ok: false, error: { code: 'ABORTED' } });
    expect(query).not.toHaveBeenCalled();
  });
  it('can be installed by an authenticated host but stays off certified prepared surfaces', () => {
    const options = { workspaceRoot: '.', includeImageExtraction: false, includeDashboardInspector: false,
      includeQuantApi: false, knowledgeGraph: { projectId, query: vi.fn() } };
    expect(createFinancePiAgentTools(options).map(tool => tool.name)).toContain('query_knowledge_graph');
    expect(() => createFinancePiAgentTools({ ...options, preparedSurface: 'custom' })).toThrow();
  });
});
