"use client";

import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import { Database, Download, ExternalLink, Network, RefreshCw, Square } from "lucide-react";
import { PageHeader } from "@/components/layout/PageHeader";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { connectResearchSession, researchCsrf, researchFetch } from "@/lib/java/client";
import { EvidenceWorkspace, type Notice } from "./EvidenceWorkspace";
import { cn } from "@/lib/utils";
import { KnowledgeQuestionPanel } from "./KnowledgeQuestionPanel";

type Project = { id: string; name: string; role: string };
type Job = { id: string; securityId: string; state: string; snapshotId: string | null; reused: boolean; errorCode: string | null; createdAt: string };
type GraphNode = { id: string; kind: string; label: string };
type Edge = { id: string; source: string; target: string; type: string; evidenceKey: string };
type Evidence = { id: string; sourceUrl: string; recordPath: string; observedAt: string; publishedAt: string | null; snapshotHash: string; scope: string; record: Record<string, unknown> };
type Graph = { releaseId: string; securityId: string; fetchedAt: string; contentHash: string; snapshotHash: string; nodes: GraphNode[]; edges: Edge[]; evidence: Evidence[]; warnings: string[]; storage: string };
type Model = { configured: boolean; connected: boolean; model: string; checkedAt: string | null; errorCode: string | null; totalTokens: number };
const STATES: Record<string, string> = { QUEUED: "排队中", COLLECTING: "自动获取资料", PROJECTING: "构建关系图谱", SUCCEEDED: "已完成", FAILED: "失败", CANCELLED: "已取消" };
const terminal = (job: Job) => ["SUCCEEDED", "FAILED", "CANCELLED"].includes(job.state);
const message = (error: unknown) => error instanceof Error ? error.message : "请求未成功，请重试。";
const time = (value: string | null) => value ? new Date(value).toLocaleString("zh-CN", { timeZone: "Asia/Shanghai" }) : "来源未提供";
const relation = (value: string) => ({ CLASSIFIED_AS: "来源行业分类", HAS_ANNOUNCEMENT: "关联公告", HAS_SOURCE_CLAIM: "审核来源陈述", SUPPORTED_BY: "原文支持" })[value] || value;

function RelationGraph({ graph, selected, onSelect }: { graph: Graph; selected: string; onSelect: (key: string) => void }) {
  const security = graph.nodes.find(node => node.kind === "Security");
  const targets = graph.edges.filter(edge => edge.source === security?.id).map(edge => ({ edge, node: graph.nodes.find(node => node.id === edge.target)! }));
  const height = Math.max(240, targets.length * 68 + 40);
  const center = height / 2;
  return (
    <div className="hidden overflow-hidden rounded-xl border border-border/60 bg-muted/20 sm:block" role="group" aria-label="证券关系图">
      <svg viewBox={`0 0 900 ${height}`} className="w-full" aria-label="证券、行业与公告关系">
        {targets.map(({ edge, node }, index) => {
          const y = 40 + index * 68;
          const active = selected === edge.evidenceKey;
          return (
            <g key={edge.id} role="button" tabIndex={0} aria-label={`查看证据：${node.label}`}
              onClick={() => onSelect(edge.evidenceKey)} onKeyDown={event => { if (event.key === "Enter" || event.key === " ") { event.preventDefault(); onSelect(edge.evidenceKey); } }}
              className="cursor-pointer focus:outline-none">
              <path d={`M 235 ${center} C 350 ${center}, 350 ${y}, 470 ${y}`} fill="none" stroke={active ? "hsl(var(--primary))" : "hsl(var(--border))"} strokeWidth={active ? 2.5 : 1.5} />
              <text x="350" y={y - 8} fontSize="11" fill="hsl(var(--muted-foreground))">{relation(edge.type)}</text>
              <rect x="470" y={y - 22} width="400" height="45" rx="9" fill={active ? "hsl(var(--primary) / 0.1)" : "hsl(var(--card))"} stroke={active ? "hsl(var(--primary))" : "hsl(var(--border))"} />
              <text x="485" y={y + 4} fontSize="12" fill="hsl(var(--foreground))">{Array.from(node.label).slice(0, 29).join("")}{Array.from(node.label).length > 29 ? "…" : ""}</text>
              <title>{node.label}</title>
            </g>
          );
        })}
        <rect x="20" y={center - 34} width="215" height="68" rx="12" fill="hsl(var(--primary) / 0.1)" stroke="hsl(var(--primary) / 0.4)" />
        <text x="36" y={center - 5} fontSize="14" fontWeight="600" fill="hsl(var(--foreground))">{security?.label.split(" · ")[0]}</text>
        <text x="36" y={center + 17} fontSize="11" fill="hsl(var(--muted-foreground))">{graph.securityId}</text>
      </svg>
    </div>
  );
}

export default function KnowledgeGraphClient() {
  const [connected, setConnected] = useState<boolean | null>(null);
  const [password, setPassword] = useState("");
  const [projects, setProjects] = useState<Project[]>([]);
  const [project, setProject] = useState("");
  const [security, setSecurity] = useState("600519");
  const [jobs, setJobs] = useState<Job[]>([]);
  const [job, setJob] = useState<Job | null>(null);
  const [graph, setGraph] = useState<Graph | null>(null);
  const [selected, setSelected] = useState("");
  const [model, setModel] = useState<Model | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [probing, setProbing] = useState(false);
  const [watchVersion, setWatchVersion] = useState(0);
  const latest = useRef(0);
  const activeProject = useRef(project);
  activeProject.current = project;
  const pending = useRef<{ body: string; key: string } | null>(null);
  const owner = projects.find(item => item.id === project)?.role === "OWNER";
  const root = `/projects/${project}/knowledge`;

  const load = useCallback(async () => {
    const me = await researchFetch("/me");
    setProjects(me.projects); setProject(current => current || me.projects[0]?.id || ""); setConnected(true);
  }, []);
  useEffect(() => { load().catch(() => setConnected(false)); }, [load]);
  const refresh = useCallback(async () => {
    if (!project) return;
    const rows: Job[] = await researchFetch(`/projects/${project}/knowledge/collections`);
    if (activeProject.current === project) setJobs(rows);
  }, [project]);
  useEffect(() => {
    const version = ++latest.current;
    setJobs([]); setJob(null); setGraph(null); setModel(null); setError(""); pending.current = null;
    if (!project) return;
    Promise.all([researchFetch(`${root}/collections`), researchFetch(`${root}/model`)]).then(([rows, config]) => {
      if (version !== latest.current) return;
      setJobs(rows); setModel(config); if (rows.length) setJob(rows[0]);
    }).catch(value => { if (version === latest.current) setError(message(value)); });
  }, [project, root]);

  useEffect(() => {
    if (!job?.id || !project) return;
    let cancelled = false;
    const abort = new AbortController();
    const path = `/projects/${project}/knowledge/collections/${job.id}`;
    setGraph(null); setSelected("");
    async function watch() {
      try {
        for (let poll = 0; poll < 100 && !cancelled; poll++) {
          const next: Job = await researchFetch(path, { signal: abort.signal });
          if (cancelled) return;
          setJob(next);
          if (terminal(next)) {
            await refresh();
            if (next.state === "SUCCEEDED" && next.snapshotId) {
              const result = await researchFetch(`/projects/${project}/knowledge/releases/${next.snapshotId}`, { signal: abort.signal });
              if (!cancelled) { setGraph(result); setSelected(result.edges[0]?.evidenceKey || ""); }
            }
            return;
          }
          await new Promise(resolve => setTimeout(resolve, 1800));
        }
        if (!cancelled) setError("状态查询已暂停，可以点击刷新继续查看。");
      } catch (value) { if (!cancelled) setError(message(value)); }
    }
    void watch();
    return () => { cancelled = true; abort.abort(); };
  }, [job?.id, project, refresh, watchVersion]);

  async function create(event: FormEvent) {
    event.preventDefault(); setError(""); setBusy(true);
    const body = JSON.stringify({ securityId: security.trim().toUpperCase() });
    if (pending.current?.body !== body) pending.current = { body, key: crypto.randomUUID() };
    try {
      const value = await researchFetch(`${root}/collections`, { method: "POST", body, headers: { ...await researchCsrf(), "Content-Type": "application/json", "Idempotency-Key": pending.current.key } });
      if (activeProject.current !== project) return;
      pending.current = null; setJob(value); setGraph(null); await refresh();
    } catch (value) { setError(message(value)); } finally { setBusy(false); }
  }
  async function reconnect(event: FormEvent) {
    event.preventDefault(); setBusy(true); setError("");
    try { await connectResearchSession(password); setPassword(""); await load(); } catch (value) { setError(message(value)); } finally { setBusy(false); }
  }
  async function cancel() {
    if (!job) return;
    try { setJob(await researchFetch(`${root}/collections/${job.id}/cancel`, { method: "POST", headers: await researchCsrf() })); await refresh(); }
    catch (value) { setError(message(value)); }
  }
  async function probe() {
    setProbing(true); setError("");
    try { setModel(await researchFetch(`${root}/model/probe`, { method: "POST", headers: await researchCsrf() })); }
    catch (value) { setError(message(value)); } finally { setProbing(false); }
  }
  function download() {
    if (!graph) return;
    const url = URL.createObjectURL(new Blob([JSON.stringify(graph, null, 2)], { type: "application/json" }));
    const link = document.createElement("a"); link.href = url; link.download = `knowledge-${graph.securityId.replace(":", "-")}-${graph.releaseId}.json`; link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
  async function downloadSource() {
    if (!graph) return;
    try {
      const response = await fetch(`/api/java${root}/releases/${graph.releaseId}/source`);
      if (!response.ok) throw new Error("来源快照下载失败。");
      const raw = await response.text();
      const bytes = new TextEncoder().encode(raw);
      const hash = Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256", bytes))).map(value => value.toString(16).padStart(2, "0")).join("");
      if (hash !== graph.snapshotHash) throw new Error("来源快照摘要不匹配，请刷新后重试。");
      const url = URL.createObjectURL(new Blob([raw], { type: "application/json" }));
      const link = document.createElement("a"); link.href = url; link.download = `source-${graph.releaseId}.json`; link.click();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (value) { setError(message(value)); }
  }
  async function loadRelease(release: string) {
    const result = await researchFetch(`${root}/releases/${release}`);
    if (activeProject.current === project) { setGraph(result); setSelected(result.edges[0]?.evidenceKey || ""); }
  }
  const notices: Notice[] = graph?.evidence.filter(item => item.scope === "ANNOUNCEMENT_METADATA" || typeof item.record.providerId === "string")
    .map(item => ({ providerId: String(item.record.providerId), title: String(item.record.title) })).filter(item => /^AN[0-9]+$/.test(item.providerId)) || [];
  const evidence = graph?.evidence.find(item => item.id === selected);
  return (
    <div className="min-h-screen bg-background text-foreground">
      <PageHeader title="知识图谱" subtitle="自动获取公开证券资料，查看关系与原始出处。" />
      <main className="mx-auto max-w-[1600px] space-y-5 p-4 sm:p-6 lg:p-8">
        <div className="flex flex-wrap items-end justify-between gap-4">
          <div><p className="text-xs font-semibold tracking-widest text-primary">RESEARCH KNOWLEDGE</p><h2 className="mt-2 text-2xl font-bold">从证券到关系与证据</h2><p className="mt-2 text-sm text-muted-foreground">输入股票代码，自动获取行业分类与最近公告，无需上传资料。</p></div>
          {connected && <label className="text-sm">研究项目<select aria-label="研究项目" value={project} onChange={event => setProject(event.target.value)} className="ml-2 max-w-full rounded-xl border bg-background p-2">{projects.map(item => <option key={item.id} value={item.id}>{item.name} · {item.role === "OWNER" ? "可编辑" : "只读"}</option>)}</select></label>}
        </div>
        {error && <p role="alert" className="break-words rounded-xl border border-destructive/20 bg-destructive/10 p-3 text-sm text-destructive">{error}</p>}
        {connected === null && <p className="text-sm text-muted-foreground">连接研究服务中…</p>}
        {connected === false && <Card className="max-w-md"><CardHeader><CardTitle>连接研究会话</CardTitle></CardHeader><CardContent><form onSubmit={reconnect} className="space-y-4"><Label htmlFor="research-password">当前账号的登录密码</Label><Input id="research-password" type="password" autoComplete="current-password" value={password} onChange={event => setPassword(event.target.value)} required /><Button disabled={busy}>连接研究服务</Button></form></CardContent></Card>}
        {connected && <div className="grid items-start gap-5 lg:grid-cols-[300px_minmax(0,1fr)]">
          <aside className="space-y-5">
            <Card><CardHeader><CardTitle className="text-base">自动采集</CardTitle></CardHeader><CardContent><form onSubmit={create} className="space-y-4"><div className="space-y-2"><Label htmlFor="security-code">股票代码</Label><Input id="security-code" value={security} onChange={event => setSecurity(event.target.value)} placeholder="600519 或 SZ:000001" maxLength={9} required /></div><p className="text-xs leading-5 text-muted-foreground">当前支持沪深 A 股。获取来源行业分类和最多 8 条近期公告；最近一小时的已验收快照可复用。</p><Button className="w-full gap-2" disabled={busy || !owner || !project}><Network className="h-4 w-4" />{busy ? "提交中…" : "获取资料并构建图谱"}</Button>{!owner && <p className="text-xs text-muted-foreground">当前项目为只读权限。</p>}</form></CardContent></Card>
            <Card><CardHeader className="flex-row items-center justify-between"><CardTitle className="text-base">最近采集</CardTitle><Button variant="ghost" size="icon" aria-label="刷新采集记录" onClick={() => { refresh().catch(value => setError(message(value))); setWatchVersion(value => value + 1); }}><RefreshCw className="h-4 w-4" /></Button></CardHeader><CardContent className="max-h-[420px] space-y-2 overflow-auto">{jobs.length === 0 && <p className="text-sm text-muted-foreground">尚无采集记录。</p>}{jobs.map(item => <button key={item.id} data-job-id={item.id} onClick={() => { setError(""); setJob(item); setWatchVersion(value => value + 1); }} className={cn("w-full rounded-xl border p-3 text-left", item.id === job?.id ? "border-primary/40 bg-primary/5" : "border-border hover:bg-muted/40")}><span className="block text-sm font-semibold">{item.securityId}</span><span className="mt-1 block text-xs text-muted-foreground">{STATES[item.state]} · {time(item.createdAt)}</span></button>)}</CardContent></Card>
            <Card><CardHeader><CardTitle className="text-base">AI 连接</CardTitle></CardHeader><CardContent className="space-y-3 text-xs text-muted-foreground"><p>{model?.model || "DeepSeek"} · {model?.configured ? "已配置" : "未配置"}</p>{model?.checkedAt && <p>{model.connected ? `连接成功，${model.totalTokens} Token` : `连接失败：${model.errorCode}`}<br />{time(model.checkedAt)}</p>}<p>基础资料采集无需模型；正文候选由你主动触发抽取。连接测试发送固定短句，会产生少量调用用量。</p><Button variant="outline" size="sm" disabled={!owner || !model?.configured || probing} onClick={probe}>{probing ? "测试中…" : "测试模型连接"}</Button></CardContent></Card>
          </aside>
          <section className="min-w-0 space-y-5">
            <KnowledgeQuestionPanel key={project} project={project} security={security} owner={!!owner} />
            {!job && <Card><CardContent className="py-16 text-center"><Database className="mx-auto h-9 w-9 text-primary/60" /><h3 className="mt-4 font-semibold">从一只股票开始</h3><p className="mt-2 text-sm text-muted-foreground">采集后将展示证券、行业和公告，以及每条关系对应的来源。</p></CardContent></Card>}
            {job && <Card><CardContent className="space-y-3 pt-5"><div className="flex flex-wrap items-center justify-between gap-3"><div><h3 className="font-semibold">{job.securityId}</h3><p className="mt-1 text-xs text-muted-foreground">{time(job.createdAt)}{job.reused ? " · 复用已保存来源快照" : ""}</p></div><span className="rounded-full bg-primary/10 px-3 py-1 text-xs text-primary">{STATES[job.state]}</span></div>{!terminal(job) && <Button variant="outline" size="sm" onClick={cancel}><Square className="mr-2 h-3 w-3" />取消采集</Button>}{job.errorCode && <p className="break-words text-xs text-destructive">{job.errorCode}{job.state === "PROJECTING" ? " · 投影将有限重试" : " · 可以重新提交采集"}</p>}</CardContent></Card>}
            {graph && <>
              <div className="flex justify-end"><Button variant="ghost" size="sm" onClick={downloadSource}><Download className="mr-2 h-4 w-4" />下载来源快照并校验摘要</Button></div>
              <Card><CardHeader className="flex-row flex-wrap items-center justify-between gap-3"><div><CardTitle className="text-base">证券关系</CardTitle><p className="mt-2 text-xs text-muted-foreground">{graph.nodes.length} 个节点 · {graph.edges.length} 条关系 · Neo4j 实际查询</p></div><Button variant="outline" size="sm" onClick={download}><Download className="mr-2 h-4 w-4" />下载关系与证据</Button></CardHeader><CardContent className="space-y-4"><RelationGraph graph={graph} selected={selected} onSelect={setSelected} /><div className="space-y-2" aria-label="关系列表">{graph.edges.map(edge => <button key={edge.id} onClick={() => setSelected(edge.evidenceKey)} aria-pressed={selected === edge.evidenceKey} className={cn("flex w-full flex-col gap-1 rounded-xl border p-3 text-left sm:flex-row sm:gap-3", selected === edge.evidenceKey ? "border-primary/40 bg-primary/5" : "border-border hover:bg-muted/40")}><span className="shrink-0 text-xs font-medium text-primary">{relation(edge.type)}</span><span className="min-w-0 break-words text-sm">{edge.type === "SUPPORTED_BY" && `${graph.nodes.find(node => node.id === edge.source)?.label} → `}{graph.nodes.find(node => node.id === edge.target)?.label}</span></button>)}</div><p className="text-xs leading-5 text-muted-foreground">行业来自东方财富分类。公告关联仅证明来源收录了该公告；正文需在下方单独抓取并审核。审核关系表示来源的陈述，不能据此证明事件已完成。</p></CardContent></Card>
              {evidence && <Card><CardHeader><CardTitle className="text-base">关系证据</CardTitle></CardHeader><CardContent className="space-y-4 text-sm"><div className="grid gap-3 sm:grid-cols-2"><p><span className="text-muted-foreground">抓取时间：</span>{time(evidence.observedAt)}</p><p><span className="text-muted-foreground">来源公开时间：</span>{time(evidence.publishedAt)}</p></div>{typeof evidence.record.quote === "string" && <div className="space-y-2"><p className="text-xs text-muted-foreground">第 {String(evidence.record.pageNumber)} 页 · {String(evidence.record.chunkId)} · 语气：{String(evidence.record.modality)}</p><blockquote className="whitespace-pre-wrap break-words rounded-xl bg-muted p-4 leading-6">{evidence.record.quote}</blockquote><p className="text-xs text-muted-foreground">审核说明：{String(evidence.record.reviewReason)}</p><a className="text-primary hover:underline" href={`/api/java${root}/documents/${String(evidence.record.documentId)}/pdf`} download>下载对应 PDF</a></div>}<a href={evidence.sourceUrl} target="_blank" rel="noopener noreferrer" className="inline-flex items-center gap-2 text-primary hover:underline">查看来源页面<ExternalLink className="h-3.5 w-3.5" /></a><p className="break-all text-xs text-muted-foreground">记录定位：{evidence.recordPath}<br />原始快照 SHA-256：{evidence.snapshotHash}</p><details><summary className="cursor-pointer text-xs text-primary">查看保存的来源记录</summary><pre className="mt-3 max-h-80 overflow-auto whitespace-pre-wrap break-all rounded-xl bg-muted p-4 text-xs">{JSON.stringify(evidence.record, null, 2)}</pre></details></CardContent></Card>}
              {job?.snapshotId && <EvidenceWorkspace key={`${project}:${job.snapshotId}`} project={project} catalog={job.snapshotId} notices={notices} owner={owner} modelReady={!!model?.configured} onRelease={loadRelease} />}
              <Card><CardContent className="space-y-2 pt-5 text-xs leading-5 text-muted-foreground"><p className="break-all">图谱版本：{graph.releaseId}<br />来源内容版本：{graph.contentHash}</p><p>本次覆盖近期公告第一页，非完整公告历史。分类按当前来源观察，不保证历史时点可用。</p>{graph.warnings.length > 0 && <p className="break-words">资料缺口：{graph.warnings.join("、")}</p>}</CardContent></Card>
            </>}
          </section>
        </div>}
      </main>
    </div>
  );
}
