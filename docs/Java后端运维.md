# Java 后端运维

首次安装和日常启动见 [README](../README.md)。Spring Boot 默认监听 `127.0.0.1:58870`；工作台默认 `58872`，可分别用 `start-backend.ps1 -Port` 和 `start-frontend.ps1 -Port -BackendPort` 指定备用端口。修改工作台端口后，在后端启动前同步 `SIGNALFORGE_AGENT_URL`。已构建 JAR 时可传 `-NoBuild`。

| 配置 | 行为 |
| --- | --- |
| `POSTGRES_DB` / `POSTGRES_PORT` | 根 `.env`；新装用 `signalforge_java` 和 `45432`。脚本从本文件构造 JDBC 地址。 |
| `DATABASE_URL` | 从 `frontend/.env` 读取；必须用同一 PostgreSQL 端口和单独网页库 `signalforge_java_web`，兼容已有 `signalforge_web`。 |
| `REDIS_PORT` | 分时行情缓存默认端口 `46379`。 |
| `SIGNALFORGE_INTRADAY_CACHE_TTL` | 分时缓存默认 20 秒，支持 `30s` 等 Spring Duration 格式。Redis 失败时请求回源，排查时可开启 `com.signalforge.finance.IntradayQuotes` 的 DEBUG 日志。 |
| `NEO4J_BOLT_PORT` / `NEO4J_PASSWORD` | 默认 Bolt 端口 `47687`，用于图谱投影和查询。 |
| `SIGNALFORGE_DEV_USERNAME` / `SIGNALFORGE_DEV_PASSWORD` | 本地初始化凭据；已有数据库账号不会被脚本重置。 |
| `SIGNALFORGE_ALLOW_FIXTURES` | 默认关闭，`start-backend.ps1 -Demo` 启用合成 ETF。正式行情请求仍从真实来源取数。 |
| `SIGNALFORGE_WORKER_ENABLED` | 默认开启研究任务派发；冒烟脚本会先关闭，模拟重启后恢复。 |

Flyway 自动部署 `sf_*` 迁移，Prisma 管工作台数据库。停止 Java/工作台用 Ctrl+C，停止基础设施用 `stop-infra.ps1`，保留数据卷。不要用另一项目的 `.env` 或行情 SQL 初始化 Java 数据库。

```powershell
.\scripts\check-infra.ps1
.\scripts\check-backend.ps1
.\scripts\smoke-research.ps1
# 可选：请求两只真实 ETF 的历史数据
.\scripts\smoke-research.ps1 -Live
```

Maven 的集成测试使用临时数据库。冒烟在备用端口启动和停止自己的 JVM，在开发库保留测试记录；进程日志写入 `.runtime/java-smoke`。不调用模型。外部行情失败时检查公开数据源和日期范围；快照复用保留实际来源和原抓取时间。遇到 `EVIDENCE_REJECTED`，检查证券、价格区间、来源和摘要。重启后重新登录，SSE 可用最后事件 ID 重放。

公告采集和 PDF 解析由 Java 执行；Neo4j 失败时可在修复后重新触发采集。模型参数、证据和未知调用结果的处理见 [公告审核](公告正文与关系审核.md) 和 [Agent 问答](Agent知识问答接入.md)。
