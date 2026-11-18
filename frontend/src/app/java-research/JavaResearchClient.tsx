"use client";

import {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
  type FormEvent,
} from "react";
import { Activity, Download, RefreshCw, Square, Database } from "lucide-react";
import {
  Line,
  LineChart,
  CartesianGrid,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { PageHeader } from "@/components/layout/PageHeader";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  connectResearchSession,
  researchCsrf,
  researchFetch,
} from "@/lib/java/client";
import { cn } from "@/lib/utils";

type Project = { id: string; name: string; role: string };
type Task = {
  id: string;
  state: string;
  errorCode?: string;
  createdAt: string;
  attempts: number;
  request: {
    securities: { securityId: string }[];
    priceRange: { start: string; end: string };
  };
};
type Series = {
  securityId: string;
  market: string;
  dataVersion: string;
  fetchStatus: string;
  bars: { date: string; close: string }[];
  metrics: Record<string, number>;
  coverage: {
    observations: number;
    firstDate: string;
    lastDate: string;
    complete: boolean;
  };
  sourceRefs: {
    uri: string;
    provider: string;
    fetchedAt: string;
    demoData: boolean;
  }[];
};
type Result = { demoData: boolean; series: Series[] };
const STATES: Record<string, string> = {
  QUEUED: "排队中",
  RUNNING: "获取数据与计算",
  VERIFYING: "验证结果",
  SUCCEEDED: "已完成",
  FAILED: "失败",
  CANCELLED: "已取消",
};
const TERMINAL = new Set(["SUCCEEDED", "FAILED", "CANCELLED"]);
const COLORS = ["#15846d", "#5578b9"];
const percent = (value: number) =>
  Number.isFinite(Number(value)) ? `${Number(value).toFixed(2)}%` : "—";
const errorMessage = (error: unknown) =>
  error instanceof Error ? error.message : "请求失败，请稍后重试。";

function initialDates() {
  const today = new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Shanghai",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(new Date());
  const end = new Date(`${today}T00:00:00Z`);
  end.setUTCDate(end.getUTCDate() - 1);
  const start = new Date(end);
  start.setUTCDate(start.getUTCDate() - 90);
  return {
    start: start.toISOString().slice(0, 10),
    end: end.toISOString().slice(0, 10),
  };
}

function ResearchChart({
  series,
  drawdown = false,
}: {
  series: Series[];
  drawdown?: boolean;
}) {
  const data = useMemo(() => {
    const dates = new Map<string, Record<string, string | number>>();
    for (const item of series) {
      let peak = Number(item.bars[0].close);
      const first = peak;
      for (const bar of item.bars) {
        const price = Number(bar.close);
        peak = Math.max(peak, price);
        const row = dates.get(bar.date) || { date: bar.date };
        row[item.securityId] = drawdown
          ? ((peak - price) / peak) * 100
          : (price / first - 1) * 100;
        dates.set(bar.date, row);
      }
    }
    return [...dates.values()].sort((a, b) =>
      String(a.date).localeCompare(String(b.date)),
    );
  }, [series, drawdown]);
  return (
    <div
      className="h-64 w-full"
      role="img"
      aria-label={drawdown ? "历史回撤曲线" : "区间收益曲线"}
    >
      <ResponsiveContainer
        width="100%"
        height="100%"
        minWidth={0}
        initialDimension={{ width: 500, height: 256 }}
      >
        <LineChart
          data={data}
          margin={{ left: 0, right: 8, top: 8, bottom: 0 }}
        >
          <CartesianGrid strokeDasharray="3 3" opacity={0.25} />
          <XAxis
            dataKey="date"
            tickFormatter={(value) => String(value).slice(5)}
            fontSize={11}
            minTickGap={32}
          />
          <YAxis
            tickFormatter={(value) => `${value.toFixed(0)}%`}
            width={48}
            fontSize={11}
          />
          <Tooltip formatter={(value) => `${Number(value).toFixed(2)}%`} />
          {series.map((item, index) => (
            <Line
              key={item.securityId}
              dataKey={item.securityId}
              stroke={COLORS[index]}
              dot={false}
              strokeWidth={2}
              isAnimationActive={false}
            />
          ))}
        </LineChart>
      </ResponsiveContainer>
    </div>
  );
}

export default function JavaResearchClient() {
  const [connected, setConnected] = useState<boolean | null>(null);
  const [password, setPassword] = useState("");
  const [projects, setProjects] = useState<Project[]>([]);
  const [project, setProject] = useState("");
  const [tasks, setTasks] = useState<Task[]>([]);
  const [task, setTask] = useState<Task | null>(null);
  const [result, setResult] = useState<Result | null>(null);
  const [codes, setCodes] = useState(["510300", "510500"]);
  const [dates, setDates] = useState(initialDates);
  const [message, setMessage] = useState("");
  const [busy, setBusy] = useState(false);
  const [events, setEvents] = useState<{ sequence: number; type: string }[]>(
    [],
  );
  const selection = useRef(0);
  const controller = useRef<AbortController | null>(null);
  const idempotency = useRef<{ body: string; key: string } | null>(null);
  const activeProject = useRef("");
  const identity = useRef("");
  const path = `/projects/${project}/research/tasks`;
  const owner = projects.find((item) => item.id === project)?.role === "OWNER";
  const stopWatching = useCallback(() => {
    controller.current?.abort();
    selection.current++;
  }, []);

  const refresh = useCallback(async (projectId: string) => {
    if (!projectId) return [] as Task[];
    const list = await researchFetch(`/projects/${projectId}/research/tasks?researchType=ETF_RISK_COMPARISON`);
    if (activeProject.current === projectId) setTasks(list);
    return list as Task[];
  }, []);

  const load = useCallback(async () => {
    const me = await researchFetch("/me");
    identity.current = me.username;
    let saved: { project?: string; task?: string } | null = null;
    try {
      saved = JSON.parse(
        localStorage.getItem(`signalforge:web:last:${me.username}`) || "null",
      );
    } catch {}
    setProjects(me.projects);
    setConnected(true);
    setProject((current) =>
      me.projects.some((item: Project) => item.id === current)
        ? current
        : me.projects.find((item: Project) => item.id === saved?.project)?.id ||
          me.projects[0]?.id ||
          "",
    );
  }, []);

  const select = useCallback(
    async (item: Task, projectId: string) => {
      if (activeProject.current !== projectId) return;
      controller.current?.abort();
      const current = ++selection.current;
      const abort = new AbortController();
      controller.current = abort;
      setTask(item);
      setResult(null);
      setEvents([]);
      setMessage("");
      try {
        localStorage.setItem(
          `signalforge:web:last:${identity.current}`,
          JSON.stringify({ project: projectId, task: item.id }),
        );
      } catch {}
      const taskPath = `/projects/${projectId}/research/tasks/${item.id}`;
      let cursor = 0,
        replayed = false,
        failures = 0;
      try {
        while (!abort.signal.aborted && selection.current === current) {
          const state: Task = await researchFetch(taskPath, {
            signal: abort.signal,
          });
          if (selection.current !== current) return;
          setTask(state);
          if (TERMINAL.has(state.state) && replayed) {
            if (state.state === "SUCCEEDED") {
              const output = await researchFetch(`${taskPath}/result`, {
                signal: abort.signal,
              });
              if (selection.current === current) setResult(output);
            }
            await refresh(projectId);
            return;
          }
          const response = await fetch(`/api/java${taskPath}/events`, {
            signal: abort.signal,
            headers: { "Last-Event-ID": String(cursor) },
          });
          if (!response.ok || !response.body)
            throw new Error("进度连接中断，请重新选择任务。");
          const reader = response.body.getReader(),
            decoder = new TextDecoder();
          let buffer = "";
          try {
            while (true) {
              const part = await reader.read();
              if (part.done) break;
              buffer += decoder
                .decode(part.value, { stream: true })
                .replace(/\r\n/g, "\n");
              let end;
              while ((end = buffer.indexOf("\n\n")) >= 0) {
                const frame = buffer.slice(0, end);
                buffer = buffer.slice(end + 2);
                const data = frame
                  .split("\n")
                  .filter((line) => line.startsWith("data:"))
                  .map((line) => line.slice(5).trimStart())
                  .join("\n");
                if (!data) continue;
                const event = JSON.parse(data);
                if (event.sequence <= cursor) continue;
                cursor = event.sequence;
                if (selection.current !== current) return;
                setEvents((previous) => [...previous, event].slice(-100));
                if (STATES[event.type])
                  setTask((previous) =>
                    previous && !TERMINAL.has(previous.state)
                      ? {
                          ...previous,
                          state: event.type,
                          attempts: event.payload?.attempt ?? previous.attempts,
                          errorCode: event.payload?.code ?? previous.errorCode,
                        }
                      : previous,
                  );
              }
            }
            failures = 0;
            replayed = true;
          } catch (error) {
            if (abort.signal.aborted || ++failures > 3) throw error;
          } finally {
            await reader.cancel().catch(() => {});
            reader.releaseLock();
          }
          if (!TERMINAL.has(state.state))
            await new Promise((resolve) => setTimeout(resolve, 1000));
        }
      } catch (error) {
        if (!abort.signal.aborted && selection.current === current)
          setMessage(errorMessage(error));
      }
    },
    [refresh],
  );

  useEffect(() => {
    load().catch((error) => {
      setConnected(false);
      if (error.status !== 401) setMessage(errorMessage(error));
    });
    return stopWatching;
  }, [load, stopWatching]);
  useEffect(() => {
    activeProject.current = project;
    stopWatching();
    setTask(null);
    setResult(null);
    setTasks([]);
    setEvents([]);
    let active = true;
    if (project)
      refresh(project)
        .then((list) => {
          if (!active) return;
          let saved: { project?: string; task?: string } | null = null;
          try {
            saved = JSON.parse(
              localStorage.getItem(
                `signalforge:web:last:${identity.current}`,
              ) || "null",
            );
          } catch {}
          const previous =
            saved?.project === project
              ? list.find((item) => item.id === saved.task)
              : undefined;
          if (previous) void select(previous, project);
        })
        .catch((error) => {
          if (active) setMessage(errorMessage(error));
        });
    return () => {
      active = false;
      stopWatching();
    };
  }, [project, refresh, select, stopWatching]);

  async function create(event: FormEvent) {
    event.preventDefault();
    if (!owner) return;
    setBusy(true);
    setMessage("");
    const projectId = project;
    try {
      if (
        !codes.every((code) => /^[15]\d{5}$/.test(code)) ||
        codes[0] === codes[1]
      )
        throw new Error("请输入两只不同的沪深ETF六位代码。");
      if (
        dates.start > dates.end ||
        (Date.parse(dates.end) - Date.parse(dates.start)) / 86400000 > 730
      )
        throw new Error("请选择有效日期，区间最长730天。");
      // Keep the original body and key together after uncertain network outcomes.
      if (!idempotency.current) {
        const body = JSON.stringify({
          schemaVersion: "1.0",
          researchType: "ETF_RISK_COMPARISON",
          securities: codes.map((code) => ({
            securityId: code,
            market: code.startsWith("5") ? "SSE" : "SZSE",
          })),
          knowledgeAsOf: new Date().toISOString(),
          priceRange: { ...dates, frequency: "DAILY" },
          priceAdjustment: "NONE",
          metrics: ["TOTAL_RETURN", "ANNUALIZED_VOLATILITY", "MAX_DRAWDOWN"],
          executionMode: "DETERMINISTIC",
        });
        idempotency.current = { body, key: crypto.randomUUID() };
      }
      const pending = idempotency.current;
      const saved: Task = await researchFetch(path, {
        method: "POST",
        headers: {
          ...(await researchCsrf()),
          "Content-Type": "application/json",
          "Idempotency-Key": pending.key,
        },
        body: pending.body,
      });
      idempotency.current = null;
      await refresh(projectId);
      void select(saved, projectId);
    } catch (error) {
      setMessage(errorMessage(error));
    } finally {
      setBusy(false);
    }
  }

  async function cancel() {
    if (!task) return;
    const selected = selection.current;
    const projectId = project;
    try {
      const saved = await researchFetch(`${path}/${task.id}/cancel`, {
        method: "POST",
        headers: await researchCsrf(),
      });
      if (selected === selection.current) setTask(saved);
      await refresh(projectId);
    } catch (error) {
      setMessage(errorMessage(error));
    }
  }

  function download() {
    if (!result || !task) return;
    const url = URL.createObjectURL(
      new Blob([JSON.stringify(result, null, 2)], { type: "application/json" }),
    );
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = `signalforge-${task.id}.json`;
    anchor.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }

  return (
    <div className="min-h-screen bg-background text-foreground">
      <PageHeader
        title="研究任务"
        subtitle="自动获取ETF历史行情，保存输入、参数与研究结果。"
      />
      <main className="mx-auto max-w-[1500px] space-y-6 px-4 py-6 lg:px-8">
        {message && (
          <p
            role="alert"
            className="rounded-xl border border-destructive/20 bg-destructive/5 p-4 text-sm text-destructive"
          >
            {message}
          </p>
        )}
        {connected === null ? (
          <p className="text-muted-foreground">正在加载研究项目…</p>
        ) : !connected ? (
          <Card className="mx-auto max-w-md">
            <CardHeader>
              <CardTitle className="text-xl">连接研究服务</CardTitle>
            </CardHeader>
            <CardContent>
              <p className="mb-4 text-sm text-muted-foreground">
                输入本地开发账号密码，连接研究任务与历史结果。
              </p>
              <form
                className="space-y-4"
                onSubmit={async (event) => {
                  event.preventDefault();
                  setBusy(true);
                  setMessage("");
                  try {
                    await connectResearchSession(password);
                    setPassword("");
                    await load();
                  } catch (error) {
                    setMessage(errorMessage(error));
                  } finally {
                    setBusy(false);
                  }
                }}
              >
                <Label htmlFor="research-password">密码</Label>
                <Input
                  id="research-password"
                  type="password"
                  autoComplete="current-password"
                  value={password}
                  onChange={(event) => setPassword(event.target.value)}
                  required
                />
                <Button disabled={busy} type="submit">
                  连接研究服务
                </Button>
              </form>
            </CardContent>
          </Card>
        ) : (
          <>
            <div className="flex flex-wrap items-end justify-between gap-4">
              <div>
                <p className="text-xs font-semibold uppercase tracking-widest text-primary">
                  Research workspace
                </p>
                <h2 className="mt-2 text-2xl font-bold">ETF 风险比较</h2>
                <p className="mt-2 text-sm text-muted-foreground">
                  收益、波动、回撤与计算依据，在同一研究记录中查看。
                </p>
              </div>
              <div className="space-y-2">
                <Label htmlFor="research-project">研究项目</Label>
                <select
                  id="research-project"
                  className="h-10 max-w-full rounded-lg border border-input bg-background px-3 text-sm"
                  value={project}
                  disabled={busy}
                  onChange={(event) => {
                    idempotency.current = null;
                    setProject(event.target.value);
                  }}
                >
                  {projects.map((item) => (
                    <option key={item.id} value={item.id}>
                      {item.name} · {item.role === "OWNER" ? "可编辑" : "只读"}
                    </option>
                  ))}
                </select>
              </div>
            </div>
            <div className="grid gap-6 lg:grid-cols-[320px_minmax(0,1fr)]">
              <aside className="space-y-5">
                <Card>
                  <CardHeader>
                    <CardTitle className="text-base">新建研究</CardTitle>
                  </CardHeader>
                  <CardContent>
                    <form
                      className="space-y-4"
                      onSubmit={create}
                      onChange={() => {
                        idempotency.current = null;
                      }}
                    >
                      <div className="grid grid-cols-2 gap-3">
                        {codes.map((code, index) => (
                          <div key={index} className="space-y-2">
                            <Label htmlFor={`etf-${index}`}>
                              ETF代码 {index === 0 ? "A" : "B"}
                            </Label>
                            <Input
                              id={`etf-${index}`}
                              value={code}
                              disabled={busy}
                              maxLength={6}
                              required
                              inputMode="numeric"
                              onChange={(event) =>
                                setCodes((previous) =>
                                  previous.map((value, i) =>
                                    i === index
                                      ? event.target.value.trim()
                                      : value,
                                  ),
                                )
                              }
                            />
                          </div>
                        ))}
                      </div>
                      <div className="grid grid-cols-2 gap-3">
                        {(["start", "end"] as const).map((key) => (
                          <div key={key} className="min-w-0 space-y-2">
                            <Label htmlFor={`research-${key}`}>
                              {key === "start" ? "开始日期" : "结束日期"}
                            </Label>
                            <Input
                              id={`research-${key}`}
                              className="min-w-0"
                              type="date"
                              disabled={busy}
                              required
                              value={dates[key]}
                              onChange={(event) =>
                                setDates((previous) => ({
                                  ...previous,
                                  [key]: event.target.value,
                                }))
                              }
                            />
                          </div>
                        ))}
                      </div>
                      <p className="text-xs leading-6 text-muted-foreground">
                        日线、不复权，区间最长730天。系统自动获取数据，无需上传。
                      </p>
                      <Button
                        type="submit"
                        disabled={!owner || busy}
                        className="w-full"
                      >
                        <Activity className="mr-2 h-4 w-4" />
                        {busy ? "提交中…" : "获取数据并开始研究"}
                      </Button>
                      {!owner && (
                        <p className="text-xs text-muted-foreground">
                          当前项目只读，可查看历史研究。
                        </p>
                      )}
                    </form>
                  </CardContent>
                </Card>
                <Card>
                  <CardHeader className="flex-row items-center justify-between space-y-0">
                    <CardTitle className="text-base">最近研究</CardTitle>
                    <Button
                      variant="ghost"
                      size="sm"
                      aria-label="刷新研究列表"
                      onClick={() =>
                        refresh(project).catch((error) =>
                          setMessage(errorMessage(error)),
                        )
                      }
                    >
                      <RefreshCw className="h-4 w-4" />
                    </Button>
                  </CardHeader>
                  <CardContent className="max-h-[480px] space-y-2 overflow-auto">
                    {tasks.length ? (
                      tasks.map((item) => (
                        <button
                          key={item.id}
                          data-task-id={item.id}
                          onClick={() => void select(item, project)}
                          className={cn(
                            "w-full rounded-xl border p-3 text-left text-sm transition-colors hover:bg-muted",
                            task?.id === item.id
                              ? "border-primary/40 bg-primary/5"
                              : "border-border",
                          )}
                        >
                          <span className="font-semibold">
                            {item.request.securities
                              .map((security) => security.securityId)
                              .join(" / ")}
                          </span>
                          <span className="mt-2 block text-xs text-muted-foreground">
                            {STATES[item.state]} ·{" "}
                            {new Date(item.createdAt).toLocaleString("zh-CN")}
                          </span>
                        </button>
                      ))
                    ) : (
                      <p className="text-sm text-muted-foreground">
                        还没有研究任务。
                      </p>
                    )}
                  </CardContent>
                </Card>
              </aside>
              <section className="min-w-0 space-y-5">
                {!task ? (
                  <Card>
                    <CardContent className="py-20">
                      <Database className="mb-4 h-8 w-8 text-primary" />
                      <h3 className="text-xl font-semibold">从两只ETF开始</h3>
                      <p className="mt-3 text-sm text-muted-foreground">
                        填写代码和日期，或打开一条已有研究。任务进度与结果会保存在研究服务中。
                      </p>
                    </CardContent>
                  </Card>
                ) : (
                  <Card>
                    <CardContent className="space-y-4 pt-6">
                      <div className="flex items-center justify-between gap-3">
                        <h3 className="font-semibold">
                          {task.request.securities
                            .map((item) => item.securityId)
                            .join(" / ")}
                        </h3>
                        <span className="rounded-full bg-primary/10 px-3 py-1 text-xs text-primary">
                          {STATES[task.state]}
                        </span>
                      </div>
                      <p className="text-xs text-muted-foreground">
                        {task.request.priceRange.start} 至{" "}
                        {task.request.priceRange.end} · 第{task.attempts}次执行
                      </p>
                      {task.errorCode && (
                        <p role="alert" className="text-sm text-destructive">
                          任务未完成：{task.errorCode}
                        </p>
                      )}
                      <div className="flex gap-2">
                        {owner && !TERMINAL.has(task.state) && (
                          <Button
                            variant="outline"
                            size="sm"
                            onClick={() => void cancel()}
                          >
                            <Square className="mr-2 h-3 w-3" />
                            取消任务
                          </Button>
                        )}
                        {result && (
                          <Button
                            variant="outline"
                            size="sm"
                            onClick={download}
                          >
                            <Download className="mr-2 h-4 w-4" />
                            下载研究结果
                          </Button>
                        )}
                      </div>
                      <ol className="flex flex-wrap gap-2">
                        {events.map((event) => (
                          <li
                            key={event.sequence}
                            className="rounded-md bg-muted px-2 py-1 text-xs text-muted-foreground"
                          >
                            {event.sequence}. {STATES[event.type] || event.type}
                          </li>
                        ))}
                      </ol>
                    </CardContent>
                  </Card>
                )}
                {result && (
                  <>
                    <div className="flex items-center justify-between">
                      <h3 className="text-lg font-semibold">研究结果</h3>
                      <span className="text-xs text-primary">
                        {result.demoData ? "合成演示数据" : "真实行情"}
                      </span>
                    </div>
                    <Card>
                      <CardContent className="pt-5">
                        <div className="overflow-x-auto">
                          <table className="w-full text-left text-sm">
                            <thead className="text-xs text-muted-foreground">
                              <tr>
                                {[
                                  "标的",
                                  "区间收益",
                                  "年化波动",
                                  "最大回撤",
                                ].map((label) => (
                                  <th
                                    key={label}
                                    className="whitespace-nowrap border-b px-3 py-4 font-normal"
                                  >
                                    {label}
                                  </th>
                                ))}
                              </tr>
                            </thead>
                            <tbody>
                              {result.series.map((item) => (
                                <tr key={item.securityId}>
                                  <th className="border-b px-3 py-4">
                                    {item.securityId}
                                  </th>
                                  {[
                                    "TOTAL_RETURN",
                                    "ANNUALIZED_VOLATILITY",
                                    "MAX_DRAWDOWN",
                                  ].map((key) => (
                                    <td
                                      className="border-b px-3 py-4"
                                      key={key}
                                    >
                                      {percent(item.metrics[key])}
                                    </td>
                                  ))}
                                </tr>
                              ))}
                            </tbody>
                          </table>
                        </div>
                        <p className="mt-4 text-xs leading-6 text-muted-foreground">
                          日简单收益率；年化因子252；样本标准差；回撤以正损失百分比显示。
                        </p>
                      </CardContent>
                    </Card>
                    <div className="grid gap-5 xl:grid-cols-2">
                      {[false, true].map((drawdown) => (
                        <Card key={String(drawdown)} className="min-w-0">
                          <CardHeader>
                            <CardTitle className="text-base">
                              {drawdown ? "历史回撤" : "区间收益走势"}
                            </CardTitle>
                            <div className="flex gap-4 pt-2 text-xs">
                              {result.series.map((item, index) => (
                                <span
                                  key={item.securityId}
                                  style={{ color: COLORS[index] }}
                                >
                                  {item.securityId}
                                </span>
                              ))}
                            </div>
                          </CardHeader>
                          <CardContent className="px-2">
                            <ResearchChart
                              series={result.series}
                              drawdown={drawdown}
                            />
                          </CardContent>
                        </Card>
                      ))}
                    </div>
                    <Card>
                      <CardHeader>
                        <CardTitle className="text-base">数据与来源</CardTitle>
                      </CardHeader>
                      <CardContent className="space-y-5">
                        {result.series.map((item) => (
                          <div
                            key={item.securityId}
                            className="space-y-2 border-b pb-5 last:border-0"
                          >
                            <h4 className="text-sm font-semibold">
                              {item.securityId}
                            </h4>
                            <p className="text-xs text-muted-foreground">
                              {item.fetchStatus === "SNAPSHOT_REUSED"
                                ? "复用已保存快照"
                                : "本次自动获取"}{" "}
                              · {item.coverage.observations}个样本 ·{" "}
                              {item.coverage.firstDate} 至{" "}
                              {item.coverage.lastDate}
                            </p>
                            {item.sourceRefs.map((ref) => (
                              <p
                                className="text-xs text-muted-foreground"
                                key={ref.uri}
                              >
                                来源：{ref.provider} · 获取于
                                {new Date(ref.fetchedAt).toLocaleString(
                                  "zh-CN",
                                )}
                              </p>
                            ))}
                            <details className="text-xs">
                              <summary className="cursor-pointer py-1 text-primary">
                                查看来源与数据版本
                              </summary>
                              <pre className="mt-2 whitespace-pre-wrap break-all rounded-lg bg-muted p-3 leading-6">
                                {JSON.stringify(
                                  {
                                    dataVersion: item.dataVersion,
                                    sourceRefs: item.sourceRefs,
                                    coverage: item.coverage,
                                  },
                                  null,
                                  2,
                                )}
                              </pre>
                            </details>
                          </div>
                        ))}
                        <p className="rounded-lg bg-amber-500/10 p-3 text-xs leading-6 text-amber-800 dark:text-amber-300">
                          交易日历完整性尚未验证，指标仅解释返回样本。本次取得的历史价格不等于当时公开版本的数据重放。
                        </p>
                      </CardContent>
                    </Card>
                  </>
                )}
              </section>
            </div>
          </>
        )}
      </main>
    </div>
  );
}
