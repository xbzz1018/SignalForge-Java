"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { researchCsrf, researchFetch } from "@/lib/java/client";

type Chunk = { id: string; pageNumber: number; text: string; startOffset: number; endOffset: number };
type Document = { id: string; providerId: string; title: string; pdfHash: string; status: string; parserVersion: string; parsed: { chunks: Chunk[]; coverage: { totalPages: number; parsedPages: number; complete: boolean }; warnings: string[] } };
type Candidate = { id: string; documentId: string; extractionId: string; predicate: string; object: string; modality: string; chunkId: string; quote: string; quoteStart: number; quoteEnd: number; status: string; validationErrors: string[]; reviewReason: string | null; reviewedAt: string | null };
type Job = { id: string; kind: string; state: string; resultId: string | null; errorCode: string | null; request: { catalogSnapshotId?: string; documentId?: string }; createdAt: string };
type Run = { id: string; state: string; selectedChunks: string[]; actualTokens: number | null; reservedTokens: number; model: string };
type Publication = { releaseId: string; createdAt: string; candidateIds: string[] };
type Budget = { limit: number; used: number; reserved: number };
export type Notice = { providerId: string; title: string };
const labels: Record<string, string> = { QUEUED: "排队中", RUNNING: "处理中", SUCCEEDED: "完成", FAILED: "失败", CANCELLED: "已取消", DOCUMENT: "正文抓取", EXTRACTION: "AI 抽取", PUBLICATION: "关系发布", PENDING: "待审核", INVALID: "校验不通过", APPROVED: "已通过", REJECTED: "已驳回", PUBLISHED: "已发布", PLANNED: "计划", ASSERTED: "原文陈述", NEGATED: "否定", UNCERTAIN: "不确定", BUSINESS: "业务", PRODUCT: "产品", EVENT: "事件" };
const errorText = (error: unknown) => error instanceof Error ? error.message : "请求失败，请重试。";
const ongoing = (job: Job) => ["QUEUED", "RUNNING"].includes(job.state);

function Citation({ chunk, candidate }: { chunk: Chunk; candidate?: Candidate }) {
  const valid = candidate && candidate.chunkId === chunk.id && candidate.quoteStart >= 0 && chunk.text.slice(candidate.quoteStart, candidate.quoteEnd) === candidate.quote;
  return <pre className="max-h-72 overflow-auto whitespace-pre-wrap break-words rounded-xl bg-muted p-4 text-xs leading-6">{valid ? <>{chunk.text.slice(0, candidate.quoteStart)}<mark className="bg-amber-200 text-slate-950">{candidate.quote}</mark>{chunk.text.slice(candidate.quoteEnd)}</> : chunk.text}</pre>;
}

export function EvidenceWorkspace({ project, catalog, notices, owner, modelReady, onRelease }: { project: string; catalog: string; notices: Notice[]; owner: boolean; modelReady: boolean; onRelease: (release: string) => Promise<void> }) {
  const root = `/projects/${project}/knowledge`;
  const [documents, setDocuments] = useState<Document[]>([]);
  const [documentId, setDocumentId] = useState("");
  const [notice, setNotice] = useState(notices[0]?.providerId || "");
  const [jobs, setJobs] = useState<Job[]>([]);
  const [publications, setPublications] = useState<Publication[]>([]);
  const [budget, setBudget] = useState<Budget | null>(null);
  const [runs, setRuns] = useState<Run[]>([]);
  const [candidates, setCandidates] = useState<Candidate[]>([]);
  const [reasons, setReasons] = useState<Record<string, string>>({});
  const [selected, setSelected] = useState<string[]>([]);
  const [focus, setFocus] = useState<Candidate>();
  const [chunkId, setChunkId] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);
  const active = useRef(true);
  const currentDocument = useRef(documentId);
  currentDocument.current = documentId;
  const pending = useRef<{ path: string; body: string; key: string } | null>(null);
  const document = documents.find(item => item.id === documentId);
  const refresh = useCallback(async () => {
    const [docs, work, releases, quota] = await Promise.all([
      researchFetch(`${root}/releases/${catalog}/documents`), researchFetch(`${root}/evidence/jobs`),
      researchFetch(`${root}/releases/${catalog}/publications`), researchFetch(`${root}/evidence/budget`),
    ]);
    if (!active.current) return;
    setDocuments(docs); setJobs(work); setPublications(releases); setBudget(quota);
    setDocumentId(current => current || docs[0]?.id || ""); setLoading(false);
  }, [root, catalog]);
  const refreshDocument = useCallback(async () => {
    if (!documentId) return;
    const [items, history] = await Promise.all([researchFetch(`${root}/documents/${documentId}/candidates`), researchFetch(`${root}/documents/${documentId}/extractions`)]);
    if (!active.current) return;
    if (currentDocument.current !== documentId) return;
    setCandidates(items); setRuns(history);
    setSelected(current => current.filter(id => items.some((item: Candidate) => item.id === id && ["APPROVED", "PUBLISHED"].includes(item.status))));
  }, [root, documentId]);
  useEffect(() => {
    active.current = true;
    void refresh().catch(value => { if (active.current) { setError(errorText(value)); setLoading(false); } });
    return () => { active.current = false; };
  }, [refresh]);
  useEffect(() => {
    let stopped = false;
    setCandidates([]); setRuns([]); setSelected([]); setFocus(undefined); setChunkId("");
    if (documentId) Promise.all([researchFetch(`${root}/documents/${documentId}/candidates`), researchFetch(`${root}/documents/${documentId}/extractions`)])
      .then(([items, history]) => { if (!stopped) { setCandidates(items); setRuns(history); } })
      .catch(value => { if (!stopped) setError(errorText(value)); });
    return () => { stopped = true; };
  }, [root, documentId]);
  useEffect(() => {
    if (!jobs.some(ongoing)) return;
    let running = false;
    const timer = setInterval(async () => {
      if (running) return;
      running = true;
      try { await refresh(); await refreshDocument(); } catch (value) { if (active.current) setError(errorText(value)); }
      finally { running = false; }
    }, 2000);
    return () => clearInterval(timer);
  }, [jobs, refresh, refreshDocument]);

  async function post(path: string, payload: unknown, idempotent = true) {
    setBusy(true); setError("");
    const body = JSON.stringify(payload);
    if (idempotent && (pending.current?.body !== body || pending.current.path !== path)) pending.current = { path, body, key: crypto.randomUUID() };
    try {
      const result = await researchFetch(`${root}${path}`, { method: "POST", body, headers: { ...await researchCsrf(), "Content-Type": "application/json", ...(idempotent ? { "Idempotency-Key": pending.current!.key } : {}) } });
      pending.current = null;
      if (active.current) { await refresh(); await refreshDocument(); }
      return result;
    } catch (value) { if (active.current) setError(errorText(value)); return null; }
    finally { if (active.current) setBusy(false); }
  }
  async function showRelease(id: string) { setError(""); try { await onRelease(id); } catch (value) { setError(errorText(value)); } }
  const chunk = document?.parsed.chunks.find(item => item.id === (focus?.chunkId || chunkId)) || document?.parsed.chunks[0];
  const relatedJobs = jobs.filter(job => job.request.catalogSnapshotId === catalog || documents.some(doc => doc.id === job.request.documentId));
  const activeExtraction = relatedJobs.some(job => job.kind === "EXTRACTION" && job.request.documentId === documentId && ongoing(job));
  return <Card><CardHeader><CardTitle className="text-base">公告正文与候选审核</CardTitle><p className="text-xs leading-5 text-muted-foreground">选择公告 → 抓取正文 → AI 提出候选 → 核对引用并审核 → 发布关系</p></CardHeader><CardContent className="space-y-5">
    {error && <p role="alert" className="break-words rounded-lg bg-destructive/10 p-3 text-sm text-destructive">{error}</p>}
    <div className="space-y-2"><label htmlFor="notice-picker" className="text-sm font-medium">已采集公告</label><select id="notice-picker" value={notice} onChange={event => setNotice(event.target.value)} className="w-full min-w-0 rounded-xl border bg-background p-2 text-sm">{notices.map(item => <option key={item.providerId} value={item.providerId}>{item.title}</option>)}</select>
      <Button size="sm" disabled={!owner || busy || !notice || relatedJobs.some(job => job.kind === "DOCUMENT" && ongoing(job))} onClick={() => void post("/documents", { catalogSnapshotId: catalog, providerId: notice })}>抓取所选公告正文</Button>
      <p className="text-xs text-muted-foreground">保存原始 PDF、SHA-256、页码和分段。扫描件暂需 OCR；长文最多解析 20 页，覆盖范围会明确标记。</p></div>
    {loading && <p className="text-sm text-muted-foreground">加载正文记录…</p>}
    {!loading && documents.length === 0 && <p className="text-sm text-muted-foreground">尚无正文。先选择一份公告并抓取。</p>}
    {documents.length > 0 && <div className="space-y-4">
      <label className="block text-sm font-medium">保存的正文版本<select aria-label="正文版本" value={documentId} onChange={event => setDocumentId(event.target.value)} className="mt-2 w-full min-w-0 rounded-xl border bg-background p-2 text-sm">{documents.map(item => <option key={item.id} value={item.id}>{item.title} · {item.pdfHash.slice(0, 8)}</option>)}</select></label>
      {document && <>
        <div className="space-y-2 text-xs text-muted-foreground"><p>{document.parsed.coverage.complete ? "正文解析完整" : document.status === "NO_TEXT" ? "无可读文本，需要 OCR" : "部分正文，禁止视为全文"} · {document.parsed.coverage.parsedPages}/{document.parsed.coverage.totalPages} 页 · {document.parsed.chunks.length} 段</p><p className="break-all">PDF SHA-256：{document.pdfHash}<br />解析版本：{document.parserVersion}</p>{document.parsed.warnings.length > 0 && <p>{document.parsed.warnings.join("、")}</p>}<a className="inline-block text-primary hover:underline" href={`/api/java${root}/documents/${document.id}/pdf`} download>下载保存的原始 PDF</a></div>
        <div className="flex flex-wrap items-center gap-2"><Button size="sm" disabled={!owner || !modelReady || busy || activeExtraction || !document.parsed.chunks.length} onClick={() => void post(`/documents/${document.id}/extractions`, { retryFailed: false })}>使用 DeepSeek 提出候选</Button>{runs.some(run => ["FAILED", "UNKNOWN", "CANCELLED"].includes(run.state)) && <Button variant="outline" size="sm" disabled={!owner || !modelReady || busy || activeExtraction} onClick={() => void post(`/documents/${document.id}/extractions`, { retryFailed: true })}>明确重试失败抽取（再次计费）</Button>}</div>
        <p className="text-xs leading-5 text-muted-foreground">点击抽取才会发送前 6 段、最多 6000 字符到模型。成功输入会复用；模型结果仅供审核，逐字引用通过也不代表语义已确认。</p>
        {budget && <p className="text-xs text-muted-foreground">项目当日 Token：已用 {budget.used} · 预占 {budget.reserved} · 上限 {budget.limit}（UTC 日期）</p>}
        {runs.map(run => <p key={run.id} className="break-words text-xs text-muted-foreground">{run.model} · {labels[run.state] || run.state} · {run.actualTokens === null ? `预占 ${run.reservedTokens} Token` : `实际 ${run.actualTokens} Token`} · 输入段：{run.selectedChunks.join("、")}</p>)}
        <div className="space-y-3"><h4 className="text-sm font-semibold">候选关系（{candidates.length}）</h4>{runs.some(run => run.state === "SUCCEEDED") && candidates.length === 0 && <p className="text-sm text-muted-foreground">模型未提出可靠候选，可查看其他公告。</p>}
          {candidates.map(candidate => <article key={candidate.id} data-candidate-id={candidate.id} className="space-y-3 rounded-xl border p-3">
            <div className="flex flex-wrap items-center justify-between gap-2"><p className="break-words text-sm font-semibold">{labels[candidate.predicate] || candidate.predicate} · {labels[candidate.modality] || candidate.modality}：{candidate.object}</p><span className="shrink-0 rounded-full bg-muted px-2 py-1 text-xs">{labels[candidate.status]}</span></div>
            <blockquote className="whitespace-pre-wrap break-words border-l-2 border-primary/40 pl-3 text-xs leading-6">{candidate.quote}</blockquote>
            <Button variant="outline" size="sm" onClick={() => { setFocus(candidate); setChunkId(candidate.chunkId); }}>定位原文 {candidate.chunkId}</Button>
            {candidate.validationErrors.length > 0 && <p className="break-words text-xs text-destructive">校验问题：{candidate.validationErrors.join("、")}；不可审核通过。</p>}
            {candidate.reviewReason && <p className="text-xs text-muted-foreground">审核意见：{candidate.reviewReason}</p>}
            {candidate.status === "PENDING" && owner && <div className="space-y-2"><label className="block text-xs">审核说明（必填，3—500 字）<textarea aria-label={`审核说明 ${candidate.id}`} value={reasons[candidate.id] || ""} onChange={event => setReasons(current => ({ ...current, [candidate.id]: event.target.value }))} maxLength={500} rows={2} className="mt-2 w-full rounded-lg border bg-background p-2 text-sm" /></label><div className="flex flex-wrap gap-2">{["APPROVED", "REJECTED"].map(decision => <Button key={decision} variant={decision === "APPROVED" ? "default" : "outline"} size="sm" disabled={busy || (reasons[candidate.id] || "").trim().length < 3} onClick={() => void post(`/candidates/${candidate.id}/review`, { decision, reason: reasons[candidate.id] }, false)}>{decision === "APPROVED" ? "审核通过" : "驳回候选"}</Button>)}</div><p className="text-xs text-muted-foreground">审核决定保存后锁定，请核对证券主体、语气和引文上下文。</p></div>}
            {["APPROVED", "PUBLISHED"].includes(candidate.status) && <label className="flex items-center gap-2 text-xs"><input type="checkbox" disabled={!owner || busy} checked={selected.includes(candidate.id)} onChange={event => setSelected(current => event.target.checked ? [...current, candidate.id] : current.filter(id => id !== candidate.id))} />选择到本次发布</label>}
          </article>)}
        </div>
        {chunk && <div className="space-y-2" aria-label="原文定位"><label className="block text-sm font-medium">原文分段<select aria-label="原文分段" value={chunk.id} onChange={event => { setChunkId(event.target.value); setFocus(undefined); }} className="ml-2 rounded-lg border bg-background p-2 text-xs">{document.parsed.chunks.map(item => <option key={item.id} value={item.id}>第 {item.pageNumber} 页 · {item.id}</option>)}</select></label><p className="text-xs text-muted-foreground">第 {chunk.pageNumber} 页 · 页内位置 {chunk.startOffset}—{chunk.endOffset}（UTF-16）</p><Citation chunk={chunk} candidate={focus} /></div>}
        <div className="space-y-2"><Button disabled={!owner || busy || selected.length === 0 || selected.length > 8} onClick={() => void post("/publications", { catalogSnapshotId: catalog, candidateIds: selected })}>发布选中关系（{selected.length}/8）</Button><p className="text-xs leading-5 text-muted-foreground">每次发布生成独立图谱版本，包含本次选择的陈述。计划、否定和不确定语气会保留；历史版本可重新查看。</p></div>
      </>}
    </div>}
    {relatedJobs.length > 0 && <details open={relatedJobs.some(ongoing)}><summary className="cursor-pointer text-sm">正文与发布任务</summary><div className="mt-3 space-y-2">{relatedJobs.map(job => <div key={job.id} className="flex flex-wrap items-center gap-2 text-xs"><span>{labels[job.kind]} · {labels[job.state]} {job.errorCode && `· ${job.errorCode}`}</span>{ongoing(job) && owner && <Button size="sm" variant="ghost" disabled={busy} onClick={() => void post(`/evidence/jobs/${job.id}/cancel`, {}, false)}>取消任务</Button>}{job.kind === "PUBLICATION" && job.state === "SUCCEEDED" && job.resultId && <Button variant="outline" size="sm" onClick={() => void showRelease(job.resultId!)}>查看已发布图谱</Button>}</div>)}</div></details>}
    {publications.length > 0 && <div className="space-y-2"><h4 className="text-sm font-medium">已发布版本</h4>{publications.map(item => <Button className="mr-2 max-w-full whitespace-normal text-left" key={item.releaseId} variant="outline" size="sm" onClick={() => void showRelease(item.releaseId)}>{new Date(item.createdAt).toLocaleString("zh-CN")} · {item.candidateIds.length} 条陈述</Button>)}<Button variant="ghost" size="sm" onClick={() => void showRelease(catalog)}>查看基础采集图谱</Button></div>}
  </CardContent></Card>;
}
