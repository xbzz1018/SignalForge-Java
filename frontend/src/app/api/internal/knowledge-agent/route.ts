import { authorizedKnowledgeAgent } from '@/lib/java/knowledge-agent-auth';
import { knowledgeAgentProvider, parseKnowledgeAgentInput, runKnowledgeAgent } from '@/lib/java/knowledge-agent';

export const runtime = 'nodejs';
export const dynamic = 'force-dynamic';
let active = 0;
export async function POST(request: Request) {
  if (!authorizedKnowledgeAgent(request.headers)) return Response.json({ code: 'UNAUTHENTICATED' }, { status: 401 });
  if (active >= 2) return Response.json({ code: 'AGENT_BUSY' }, { status: 429 });
  active++;
  try {
    const raw = await request.text();
    if (Buffer.byteLength(raw) > 48000) return Response.json({ code: 'REQUEST_TOO_LARGE' }, { status: 413 });
    let input;
    try { input = parseKnowledgeAgentInput(JSON.parse(raw)); }
    catch { return Response.json({ code: 'INVALID_AGENT_INPUT' }, { status: 400 }); }
    const { provider, model } = knowledgeAgentProvider();
    const result = await runKnowledgeAgent(input, provider, model, request.signal);
    return Response.json(result, { headers: { 'Cache-Control': 'private, no-store' } });
  } catch {
    return Response.json({ code: 'AGENT_UNAVAILABLE' }, { status: 503 });
  } finally { active--; }
}
