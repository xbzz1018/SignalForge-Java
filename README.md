# SignalForge-Java | 智能量化投研工作台

SignalForge 是量化投研与公告证据工作台。Java / Spring Boot 负责项目权限、研究任务、SSE、ETF 取数和风险计算、公告采集及 PDF 证据校验。Next.js 提供工作台和知识问答的 PI Agent 适配。

- **分时行情 API** 查询沪深股票和 ETF 的最新可用交易日数据，支持 Redis 缓存与按需刷新。
- **ETF 风险研究**：比较收益、年化波动率和最大回撤，可查看数据来源与历史快照。
- **公告与图谱**：采集公告、定位 PDF 证据，审核模型候选后发布到 Neo4j。
- **Agent 问答**：限定在已发布证据中选取引用，展示原文、页码和模型用量。

Java 版可以独立运行，不需要 Python、FastAPI 或另一版的配置和数据库。分时查询使用项目级 Java API。策略回测和生成式看板尚未接入。

## Windows 本地启动

准备 PowerShell 7、JDK 21、Maven 3.9 和开启 Linux 容器模式的 Docker Desktop。启动 Next.js 工作台还需 Node.js 22.19+ 和 npm 10+。使用 PowerShell 7（`pwsh`）克隆并进入仓库，首次执行：

```powershell
git clone https://github.com/xbzz1018/SignalForge-Java.git
cd SignalForge-Java
.\scripts\init-dev.ps1
.\scripts\start-infra.ps1
# 使用 Next.js 工作台时执行
.\scripts\setup-frontend.ps1
```

`setup-frontend.ps1` 安装锁定的依赖，在项目 PostgreSQL 中初始化网页库，部署 Prisma 迁移并准备工作台账号。首次安装需要访问软件包和 Docker 镜像仓库。

基础服务就绪后，在两个终端分别从仓库根目录运行。首次启动 Java 会构建 JAR：

```powershell
# 终端 1：Spring Boot
.\scripts\start-backend.ps1 -Demo
# 终端 2：Next.js 工作台（可选）
.\scripts\start-frontend.ps1
```

打开 `http://127.0.0.1:58872`，用 `developer@signalforge.local` 登录，默认密码为根目录 `.env` 的 `SIGNALFORGE_DEV_PASSWORD`。同一密码用于连接 Java 研究服务。Spring Boot 基础页在 `http://127.0.0.1:58870`；可载入两只演示 ETF。`-Demo` 允许显式选择合成证券，真实证券始终从行情源取数。公告和行情访问公开数据源；模型抽取与有证据问答的配置见 [Agent 接入](docs/Agent知识问答接入.md)。

| 默认入口 | 用途 |
| --- | --- |
| `58872` | 完整工作台，风险研究入口 `/java-research`，图谱与问答入口 `/knowledge-graph` |
| `58870` | Java 服务与基础页 |
| `45432` / `46379` / `47474` / `47687` | PostgreSQL、Redis、Neo4j HTTP 与 Bolt |

Docker Compose 项目名为 `signalforge-java-dev`，新安装数据库为 `signalforge_java` 和 `signalforge_java_web`。分时行情通过 Redis 短期缓存，ETF 风险分析通过 PostgreSQL 复用日线快照。分时查询和刷新入口见 [Java API](docs/Java后端接入指南.md#分时行情接口)。已有 Java 版安装保留原数据库和账号；升级后重跑 `setup-frontend.ps1` 并重新登录。业务配置参见 [.env.example](.env.example)。

与 Python 版并行运行时，请分别使用各自的目录和 `.env`。上述 PowerShell 脚本按本仓库配置连接数据库和缓存；调整 Java 基础设施端口时，修改根目录 `.env`，并同步工作台的 `frontend/.env` 数据库端口。启动基础设施请使用 `scripts/start-infra.ps1`，停止使用 `scripts/stop-infra.ps1`。

## 验证

```powershell
.\scripts\check-backend.ps1
.\scripts\smoke-research.ps1
Push-Location frontend
npm.cmd run build
Pop-Location
```

Java 集成测试使用 Docker 临时测试库。冒烟脚本仅启动临时 JVM，用合成行情验证登录、任务、快照和重启恢复；加 `-Live` 可验证真实行情，无需模型密钥。生产构建须在已生成 Prisma Client 后运行。

Windows 和 Linux 均可运行工作台测试。使用 Node.js 24，在 `frontend` 目录安装锁定的依赖并执行：

```sh
npm ci --ignore-scripts --no-audit --no-fund
npx --no-install prisma generate
node node_modules/vitest/vitest.mjs run --maxWorkers=3
```

默认测试使用隔离配置。PostgreSQL 集成测试需提供独立测试库，并运行 `npm run test:pi-agent:postgres`。Linux 的预览进程接管和命名空间沙箱有专门用例，Windows 验证启动新预览的流程。Windows 会在工作区文件重命名前同步文件内容，目录项的断电持久性由操作系统管理。

服务边界与接口见 [架构](docs/Java后端架构.md) 和 [API 指南](docs/Java后端接入指南.md)。发布来源与版权见 [第三方声明](THIRD_PARTY_NOTICES.md)。请勿上传 `.env`、`.runtime`、依赖目录、缓存、本机验收记录或私有数据。
