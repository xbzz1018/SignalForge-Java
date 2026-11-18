import { afterEach, describe, expect, it, vi } from 'vitest';
import { POST } from './route';

afterEach(() => vi.unstubAllEnvs());
describe('internal knowledge Agent route', () => {
  it('requires a service token before parsing or provider setup', async () => {
    vi.stubEnv('SIGNALFORGE_AGENT_TOKEN', 't'.repeat(64));
    expect((await POST(new Request('http://localhost/api/internal/knowledge-agent', { method: 'POST', body: '{}' }))).status).toBe(401);
  });
  it('rejects browser Origin even with a service credential', async () => {
    vi.stubEnv('SIGNALFORGE_AGENT_TOKEN', 't'.repeat(64));
    expect((await POST(new Request('http://localhost/api/internal/knowledge-agent', { method: 'POST',
      headers: { authorization: `Bearer ${'t'.repeat(64)}`, origin: 'http://localhost' }, body: '{}' }))).status).toBe(401);
  });
  it('bounds malformed and oversized inputs before invoking a model', async () => {
    vi.stubEnv('SIGNALFORGE_AGENT_TOKEN', 't'.repeat(64));
    const call = (body: string) => POST(new Request('http://localhost/api/internal/knowledge-agent', { method: 'POST',
      headers: { authorization: `Bearer ${'t'.repeat(64)}` }, body }));
    expect((await call('{"projectId":"untrusted"}')).status).toBe(400);
    expect((await call('x'.repeat(48001))).status).toBe(413);
  });
});
