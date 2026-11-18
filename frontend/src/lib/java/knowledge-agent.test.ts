import { describe, expect, it, vi } from 'vitest';
import type { PiAgentModelEvent, PiAgentModelProvider, PiAgentModelRequest } from '@/lib/agent/types';
import { authorizedKnowledgeAgent } from './knowledge-agent-auth';
import { knowledgeAgentProvider, parseKnowledgeAgentInput, runKnowledgeAgent, type KnowledgeAgentInput } from './knowledge-agent';

const project = '10000000-0000-0000-0000-000000000001';
const release = '20000000-0000-0000-0000-000000000001';
const id = `${release}:claim-1`;
function fixture(): KnowledgeAgentInput {
  const quote = '本公司计划开展技术改造项目，相关方案仍需进一步审批。';
  return { schemaVersion: '1.0', taskId: '30000000-0000-0000-0000-000000000001', projectId: project,
    question: { securityId: 'SH:600519', question: '有哪些计划事件？', releaseId: null }, promptVersion: 'knowledge-qa-1',
    evidence: { schemaVersion: '1.0', projectId: project, securityId: 'SH:600519', releaseId: release,
      snapshotHash: 'a'.repeat(64), fetchedAt: '2026-11-17T00:00:00Z', status: 'READY', truncated: false,
      pointInTimeSafe: false, warnings: ['SOURCE_STATEMENTS_NOT_VERIFIED_EVENTS'], claims: [{
        id, candidateId: '40000000-0000-0000-0000-000000000001', documentId: '50000000-0000-0000-0000-000000000001',
        predicate: 'EVENT', object: '技术改造项目', modality: 'PLANNED', quote, pageNumber: 2, chunkId: 'p2-c1',
        quoteStart: 0, quoteEnd: quote.length, sourceUrl: 'https://example.com/source.pdf', publishedAt: null,
        observedAt: '2026-11-17T00:00:00Z', documentHash: 'b'.repeat(64),
      }] } };
}
function scripted(turns: Array<{ name: string; args: object }>): PiAgentModelProvider & { requests: PiAgentModelRequest[] } {
  const requests: PiAgentModelRequest[] = [];
  return { name: 'fake', requests, async *complete(request): AsyncGenerator<PiAgentModelEvent> {
    requests.push(request);
    const turn = turns[requests.length - 1];
    if (!turn) { yield { type: 'finish', reason: 'stop' }; return; }
    yield { type: 'tool_call_delta', index: 0, id: `call-${requests.length}`, nameDelta: turn.name, argumentsDelta: JSON.stringify(turn.args) };
    yield { type: 'usage', usage: { inputTokens: 100, outputTokens: 20, totalTokens: 120 } };
    yield { type: 'finish', reason: 'tool_calls' };
  } };
}
describe('knowledge Agent scope and evidence', () => {
  it('uses the actual PI loop, queries first and returns observed citations with usage', async () => {
    const provider = scripted([{ name: 'query_knowledge_graph', args: { securityId: '600519', limit: 5 } },
      { name: 'submit_knowledge_answer', args: { citationIds: [id] } }]);
    const result = await runKnowledgeAgent(parseKnowledgeAgentInput(fixture()), provider, 'test-model');
    expect(result.status).toBe('COMPLETED'); expect(result.citationIds).toEqual([id]);
    expect(result.turns).toBe(2); expect(result.usage.totalTokens).toBe(240);
    const tool = provider.requests[1].messages.find(message => message.role === 'tool');
    expect(tool?.content).toContain('PLANNED'); expect(tool?.content).toContain('计划');
    expect(provider.requests[0].tools?.map(tool => tool.name)).toEqual(['query_knowledge_graph', 'submit_knowledge_answer']);
  });
  it('rejects forged citations even after querying real evidence', async () => {
    const provider = scripted([{ name: 'query_knowledge_graph', args: { securityId: '600519' } },
      { name: 'submit_knowledge_answer', args: { citationIds: ['forged'] } }]);
    expect((await runKnowledgeAgent(fixture(), provider, 'test')).status).toBe('FAILED');
  });
  it('cannot submit before any tool observation', async () => {
    const result = await runKnowledgeAgent(fixture(), scripted([{ name: 'submit_knowledge_answer', args: { citationIds: [id] } }]), 'test');
    expect(result.status).toBe('FAILED'); expect(result.toolObserved).toBe(false);
  });
  it('cannot query another security or a different release', async () => {
    const provider = scripted([{ name: 'query_knowledge_graph', args: { securityId: 'SZ:000001' } },
      { name: 'submit_knowledge_answer', args: { citationIds: [id] } }]);
    const result = await runKnowledgeAgent(fixture(), provider, 'test');
    expect(result.status).toBe('FAILED'); expect(result.observedCitationIds).toEqual([]);
  });
  it('rejects cross-project, invalid evidence and unsupported input before model execution', () => {
    const input = fixture(); input.evidence.projectId = release;
    expect(() => parseKnowledgeAgentInput(input)).toThrow();
    const malformed = fixture(); malformed.evidence.claims[0].quoteEnd = 999;
    expect(() => parseKnowledgeAgentInput(malformed)).toThrow();
    expect(() => parseKnowledgeAgentInput({ ...fixture(), apiKey: 'untrusted' })).toThrow();
  });
  it('propagates cancellation without an additional provider request', async () => {
    const controller = new AbortController(); controller.abort();
    const provider = scripted([]);
    await expect(runKnowledgeAgent(fixture(), provider, 'test', controller.signal)).rejects.toThrow();
    expect(provider.requests).toHaveLength(0);
  });
});
describe('internal service boundary', () => {
  it('supports local model configuration without a cloud key and rejects remote endpoints', () => {
    vi.stubEnv('SIGNALFORGE_AGENT_PROVIDER', 'openai-compatible');
    vi.stubEnv('SIGNALFORGE_AGENT_MODEL', 'local-qwen');
    vi.stubEnv('SIGNALFORGE_DEEPSEEK_API_KEY', '');
    vi.stubEnv('SIGNALFORGE_AGENT_BASE_URL', 'http://127.0.0.1:8000/v1');
    expect(knowledgeAgentProvider().provider.name).toBe('local');
    vi.stubEnv('SIGNALFORGE_AGENT_BASE_URL', 'https://untrusted.invalid/v1');
    expect(() => knowledgeAgentProvider()).toThrow('INVALID_LOCAL_MODEL_URL');
    vi.unstubAllEnvs();
  });
  it('requires a configured service token and rejects browser-originated calls', () => {
    vi.stubEnv('SIGNALFORGE_AGENT_TOKEN', 'x'.repeat(64));
    expect(authorizedKnowledgeAgent(new Headers())).toBe(false);
    expect(authorizedKnowledgeAgent(new Headers({ authorization: `Bearer ${'y'.repeat(64)}` }))).toBe(false);
    const headers = new Headers({ authorization: `Bearer ${'x'.repeat(64)}` });
    expect(authorizedKnowledgeAgent(headers)).toBe(true);
    headers.set('origin', 'http://127.0.0.1:58872');
    expect(authorizedKnowledgeAgent(headers)).toBe(false);
    vi.unstubAllEnvs();
  });
});
