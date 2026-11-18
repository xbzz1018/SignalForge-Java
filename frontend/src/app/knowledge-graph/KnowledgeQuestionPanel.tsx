"use client";

import { useEffect, useRef, useState, type FormEvent } from "react";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { researchCsrf, researchFetch } from "@/lib/java/client";
import type { KnowledgeGraphResult } from "@/lib/domains/finance/agent-tools/knowledge-graph";

type Task = { id: string; state: string; errorCode: string | null; request: { researchType: string; question?: { question: string } } };
type Report = { status: string; statements: { citationId: string; modality: string; text: string }[];
  evidence: KnowledgeGraphResult; model: string; turns: number; usage: { totalTokens: number; source: string } };
const terminal = (task: Task) => ["SUCCEEDED", "FAILED", "CANCELLED"].includes(task.state);
const labels: Record<string, string> = { QUEUED: "排队中", RUNNING: "查询证据与生成回答", VERIFYING: "校验引用", SUCCEEDED: "已完成", FAILED: "未完成", CANCELLED: "已取消" };
const modalities: Record<string, string> = { ASSERTED: "来源陈述", PLANNED: "计划", NEGATED: "否定", UNCERTAIN: "不确定" };

/** Isolated component so collection/review UI does not acquire Agent execution state. */
export function KnowledgeQuestionPanel({ project, security, owner }: { project: string; security: string; owner: boolean }) {
  const [question, setQuestion] = useState("已审核公告提到了哪些计划事件？请列出原文依据。");
  const [tasks, setTasks] = useState<Task[]>([]);
  const [task, setTask] = useState<Task | null>(null);
  const [report, setReport] = useState<Report | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [watch, setWatch] = useState(0);
  const pending = useRef<{ body: string; key: string } | null>(null);
  const root = `/projects/${project}/research/tasks`;

  useEffect(() => {
    let alive = true;
    researchFetch(`${root}?researchType=KNOWLEDGE_QA`).then((items: Task[]) => {
      if (alive) setTasks(items);
    }).catch(value => { if (alive) setError(value.message); });
    return () => { alive = false; };
  }, [root, watch]);

  const taskId = task?.id;
  useEffect(() => {
    if (!taskId) return;
    let alive = true;
    let settled = false;
    let refreshing = false;
    let source: EventSource | undefined;
    async function refresh() {
      if (settled || refreshing) return;
      refreshing = true;
      try {
        const item: Task = await researchFetch(`${root}/${taskId}`);
        if (!alive) return;
        setTask(item);
        if (terminal(item)) {
          source?.close();
          if (item.state === "SUCCEEDED") {
            const result = await researchFetch(`${root}/${taskId}/result`);
            if (alive) setReport(result);
          }
          settled = true;
          setWatch(value => value + 1);
        }
      } catch (value) {
        if (alive) setError(value instanceof Error ? value.message : "读取任务失败");
      } finally { refreshing = false; }
    }
    source = new EventSource(`/api/java${root}/${taskId}/events`);
    ["QUEUED", "RUNNING", "VERIFYING", "SUCCEEDED", "FAILED", "CANCELLED"].forEach(type => source!.addEventListener(type, refresh));
    void refresh();
    // Polling also recovers after a Java restart, when its session must be reconnected.
    const timer = setInterval(() => { void refresh(); }, 4000);
    return () => { alive = false; clearInterval(timer); source?.close(); };
  }, [root, taskId]);

  async function submit(event: FormEvent) {
    event.preventDefault(); setError(""); setBusy(true); setReport(null);
    const body = JSON.stringify({ securityId: security.trim(), question: question.trim(), releaseId: null });
    if (pending.current?.body !== body) pending.current = { body, key: crypto.randomUUID() };
    try {
      const result = await researchFetch(`/projects/${project}/knowledge/questions`, { method: "POST",
        headers: { ...(await researchCsrf()), "Content-Type": "application/json", "Idempotency-Key": pending.current!.key }, body });
      setTask(result); pending.current = null; setWatch(v => v + 1);
    } catch (value) { setError(value instanceof Error ? value.message : "提交失败"); }
    finally { setBusy(false); }
  }
  async function cancel() {
    if (!task) return;
    try {
      setTask(await researchFetch(`${root}/${task.id}/cancel`, { method: "POST", headers: await researchCsrf() }));
      setWatch(v => v + 1);
    } catch (value) { setError(value instanceof Error ? value.message : "取消失败"); }
  }
  return <Card><CardHeader><CardTitle className="text-base">向投研 Agent 提问</CardTitle></CardHeader><CardContent className="space-y-4">
    <form onSubmit={submit} className="space-y-3">
      <Label htmlFor="knowledge-question">关于 {security || "当前证券"} 的问题</Label>
      <Input id="knowledge-question" value={question} maxLength={1000} onChange={event => setQuestion(event.target.value)} required />
      <p className="text-xs leading-5 text-muted-foreground">从已审核发布的公告证据中选择相关内容，保留原文、页码与语气。请先完成正文抽取、审核和发布；无证据时提示资料不足。存在证据时会调用模型并产生用量。</p>
      <Button disabled={!owner || busy || !question.trim() || !security.trim() || (!!task && !terminal(task))}>{busy ? "提交中…" : "查询并生成引用回答"}</Button>
    </form>
    {error && <p role="alert" className="break-words text-sm text-destructive">{error}</p>}
    {tasks.length > 0 && <label className="block text-sm">最近问答<select aria-label="最近问答" className="mt-2 w-full rounded-xl border bg-background p-2" value={task?.id || ""} onChange={event => { setReport(null); setTask(tasks.find(item => item.id === event.target.value) || null); }}><option value="">选择历史任务</option>{tasks.map(item => <option key={item.id} value={item.id}>{item.request.question?.question} · {labels[item.state]}</option>)}</select></label>}
    {task && <div role="status" className="flex flex-wrap items-center gap-3 text-sm"><span>{labels[task.state]}{task.errorCode ? `：${task.errorCode}` : ""}</span>{owner && !terminal(task) && <Button variant="outline" size="sm" onClick={cancel}>取消问答</Button>}</div>}
    {report && <div className="space-y-4">
      {report.status === "INSUFFICIENT_EVIDENCE" && <p className="text-sm">当前已发布证据不足以回答。可采集更多公告并审核发布后重新提问。</p>}
      {report.statements.map((statement, index) => {
        const ref = report.evidence.claims.find(item => item.id === statement.citationId);
        if (!ref) return null;
        return <article key={statement.citationId} className="space-y-2 rounded-xl border p-4 text-sm">
          <p className="font-medium">[{index + 1}] {modalities[statement.modality]} · {ref.object}</p>
          <blockquote className="whitespace-pre-wrap break-words leading-6">{statement.text}</blockquote>
          <p className="text-xs text-muted-foreground">第 {ref.pageNumber} 页 · {ref.chunkId}</p>
          <div className="flex gap-4 text-xs text-primary"><a href={ref.sourceUrl} target="_blank" rel="noopener noreferrer">查看公开来源</a><a href={`/api/java/projects/${project}/knowledge/documents/${ref.documentId}/pdf#page=${ref.pageNumber}`} target="_blank" rel="noopener noreferrer">查看对应 PDF</a></div>
        </article>;
      })}
      <p className="break-all text-xs leading-5 text-muted-foreground">模型：{report.model} · {report.turns} 轮 · {report.usage.totalTokens} Token（{report.usage.source === "provider" ? "模型返回" : report.usage.source === "none" ? "未调用模型" : "估算或不完整"}）<br />证据版本：{report.evidence.releaseId || "暂无已发布版本"}<br />证据仅覆盖选定公告批次，来源陈述不等同于事件已经发生，不能用于证明历史时点可用。</p>
    </div>}
  </CardContent></Card>;
}
