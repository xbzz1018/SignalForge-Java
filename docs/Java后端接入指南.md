# Java 研究任务接口

Java 默认监听 `127.0.0.1:58870`。本阶段支持结构化请求 `ETF_RISK_COMPARISON`、两只不同 ETF、日线、NONE 复权及确定性执行。自然语言解析、持仓比较和 BOUNDED_AGENT 尚未接入，提交相应类型返回 422。

## 身份与会话

1. GET `/api/auth/csrf`，保留响应 cookie，并读取 `token` 与 `headerName`。
2. POST `/api/auth/login`，Content-Type 为 `application/x-www-form-urlencoded`，字段为 username、password，并携带上述 CSRF 头；成功返回 204。
3. 登录后重新 GET `/api/auth/csrf`，使用新 token。GET `/api/me` 获取可访问项目。
4. 后续写请求携带 session cookie 与 CSRF 头。POST `/api/auth/logout` 也需要 CSRF。

初始账号由 `scripts/init-dev.ps1` 生成，凭据在本地 `.env`；Next工作台仅为显式配置的单开发账号提供Better Auth到Java的会话桥接，详见[原前端联调](原前端联调.md)。bootstrap 仅创建不存在的用户，重复启动不重置数据库密码。OWNER 可以创建、取消和读取；READER 只读。业务接口依据已验证账号检查项目成员身份，不能传入 userId 代替授权。

## 任务接口

基础路径为 `/api/projects/{projectId}/research/tasks`，projectId 来自 `/api/me`。

| 方法与后缀 | 行为 |
| --- | --- |
| POST 基础路径 | 创建，必填 Idempotency-Key；新建202，重复同请求200 |
| GET 基础路径 | 最近100条任务，按创建时间倒序 |
| GET `/{taskId}` | 状态、原请求、执行次数、截止时间、错误码 |
| POST `/{taskId}/cancel` | 非终态直接取消；已终态返回原状态 |
| GET `/{taskId}/result` | 返回已经验收的 JSON 结果；尚未成功返回409 |
| GET `/{taskId}/snapshots` | 返回该已验收任务使用的行情输入及来源；沿用项目授权 |
| GET `/{taskId}/events` | SSE；Last-Event-ID 是当前任务已收到的整数序号 |

请求体见 [ETF 风险对比样例](../contracts/examples/risk-comparison.fixture.json)。幂等键限定1至128个字母、数字、点、下划线、冒号或连字符，在项目内唯一。请求按 DTO 字段序列化后计算摘要；数组顺序属于合同的一部分。同键不同参数返回409，客户端修改参数后应换键。

任务状态为 `QUEUED → RUNNING → VERIFYING → SUCCEEDED`，非终态可进入 FAILED 或 CANCELLED。浏览器关闭事件连接不取消任务。SSE 的 id 是事件序号，event 是状态事件，data 包含 sequence、type、payload、createdAt。连接约50秒后主动结束；客户端带最后序号重连。终态事件发送完毕后关闭连接，客户端应停止重连。

## 分时行情接口

基础路径为 `/api/projects/{projectId}/market/intraday/{market}/{code}`。`market` 为 `SSE` 或 `SZSE`，`code` 为六位证券代码，例如 `SSE/510300` 或 `SZSE/159915`。

| 方法 | 权限与行为 |
| --- | --- |
| GET 基础路径 | 项目成员可查询。Redis 未命中、过期或不可用时请求腾讯行情。 |
| POST `/refresh` | OWNER 携带 CSRF 头按需刷新。上游响应通过校验后写入 Redis 并重设 TTL。刷新失败时，原缓存按既有 TTL 到期。 |

响应包含 `market`、`code`、`name`、`tradeDate`、`provider`、`fetchedAt` 和 `points`。每个点有上海市场本地时刻 `time` 和十进制 `price`。`tradeDate` 是数据源提供的交易日期，休市时可能早于当前日期。服务在单个 Java 进程中合并同一证券的并发回源。无效证券格式返回 `400 UNSUPPORTED_QUOTE_SECURITY`，上游请求失败或响应无效返回 `502 INTRADAY_SOURCE_UNAVAILABLE`。

## 风险结果合同

风险计算在 Spring Boot 进程内完成。内部计算模块接受任务请求及已保存的快照；不另起服务，也不开放单独的公共计算入口。

结果字段见[风险对比响应样例](../contracts/examples/risk-response.fixture.json)。每只证券提供输入 bars、sourceRefs、获取时间、dataVersion、metrics 与 coverage。金额以十进制字符串保留；指标单位 PERCENT，年化因子252，日简单收益率，样本标准差 ddof=1，最大回撤为正损失百分比。Java 验收参数、摘要、来源与覆盖字段，并按输入行情复算比对指标；数值用 JUnit 固定数据测试校验。

摘要文本为 `market|securityId|adjustment` 加换行，再逐行追加 `YYYY-MM-DD|close` 和换行，UTF8、SHA256。close 不用指数表示且去除小数末尾零。

当前未校验交易日历完整性，coverage.complete 与 calendarVerified 均为 false；指标只解释实际返回的样本。真实行情只接受 SSE/SZSE 基金代码，并核对提供方返回的证券、市场、ETF 类型与复权口径。当前抓取的历史价格不能证明在 knowledgeAsOf 当时可取得同一版本，结果明确提示此限制。

## 演示与错误

合成证券必须为 FIXTURE 市场的 fixture:ETF_A、fixture:ETF_B，且通过 Java 的 `-Demo` 开关启用。禁止真实与合成市场混合。结果始终带 demoData=true，URI 使用 fixture 协议。范围仍按请求过滤，少于3条价格直接失败。

| 状态码或业务码 | 含义 |
| --- | --- |
| 400 INVALID_REQUEST | 字段、日期、类型、未知字段或必填头错误 |
| 401 UNAUTHENTICATED / BAD_CREDENTIALS | 未登录或凭据错误 |
| 403 PROJECT_FORBIDDEN / FORBIDDEN_OR_CSRF | 项目权限不足或 CSRF 无效 |
| 404 TASK_NOT_FOUND | 所选项目内不存在该任务 |
| 409 IDEMPOTENCY_CONFLICT / RESULT_NOT_READY | 幂等冲突或结果尚未验收 |
| 422 UNSUPPORTED_RESEARCH / INVALID_RESEARCH_PARAMETERS | 功能未启用或业务参数不支持 |
| DEADLINE_EXCEEDED / RECOVERY_EXHAUSTED | 整体超时或接管次数耗尽 |
| FINANCE_UNAVAILABLE | 行情获取或风险计算失败 |
| EVIDENCE_REJECTED | 结果合同、摘要或参数验收失败 |

## 基础网页与快照复算（2026-11-10）

GET `/` 提供工作台；静态HTML/CSS/JS允许未登录加载，研究与配置API仍需session。GET `/api/workspace/config`返回当前演示开关及已实现能力，不包含凭据。

Java 优先按项目和精确区间查找未过期输入，验证快照后重新计算。没有快照的证券从供应商获取。

series新增fetchStatus：PROVIDER_FETCH为本次取数，SNAPSHOT_REUSED为复算已保存输入，SYNTHETIC为合成样例。来源fetchedAt不因复用更新。响应其余合同保持兼容，交易日历完整性仍为false。

## 证券资料与知识接口

基础路径为 `/api/projects/{projectId}/knowledge`。使用 `POST /collections` 采集证券资料，`GET /collections/{id}` 查询状态，`POST /collections/{id}/cancel` 取消。`GET /releases/{id}` 返回 Neo4j 关系和证据，`GET /releases/{id}/source` 读取不可变来源。`GET /model` 查看模型配置，`POST /model/probe` 执行限频连接测试。授权、幂等和发布语义见《知识图谱第一版》。


## 正文、候选与审核接口

同一知识基础路径支持 `POST /documents`、`POST /documents/{id}/extractions`、`POST /candidates/{id}/review` 和 `POST /publications`。入队接口需要幂等键，审核需要 3 至 500 字理由，全部沿用项目授权和 CSRF。完整请求、查询端点和错误码见《公告正文与关系审核》。

任务历史 GET /api/projects/{project}/research/tasks 支持 researchType=ETF_RISK_COMPARISON 或 KNOWLEDGE_QA。先按类型过滤，再返回最近 100 条。
