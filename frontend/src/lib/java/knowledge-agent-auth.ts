import { createHash, timingSafeEqual } from 'node:crypto';

/** Shared service credential, deliberately independent of browser sessions. */
export function authorizedKnowledgeAgent(headers: Headers): boolean {
  const token = process.env.SIGNALFORGE_AGENT_TOKEN;
  if (!token || token.length < 32 || headers.has('origin')) return false;
  const expected = createHash('sha256').update(`Bearer ${token}`).digest();
  const actual = createHash('sha256').update(headers.get('authorization') || '').digest();
  return timingSafeEqual(expected, actual);
}
