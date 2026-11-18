# SignalForge Java 开发约定

- Spring Boot 负责行情计算、任务、公告证据和图谱。Next.js 提供工作台和 PI Agent 知识问答适配；不要引入 Python 后端或其他仓库的运行时配置。
- 使用 JDK 21 和 Maven。`.runtime` 中的旧环境仅为本机兼容，初始化和验证命令以 README 为准。
- Java 的 `sf_` 表只由 Flyway 管理；网页数据库由 Prisma 管理。只追加迁移，不改写已执行的迁移。
- 项目写操作校验权限。任务终态必须同时检查执行令牌、租约、状态和截止时间。外部请求不持有任务锁。
- 数据快照保留来源与演示标记。模型调用未知时不自动重发；只有经过证据校验和人工审核的来源陈述可以发布。
- 密钥只在本机 `.env` 或进程环境；不打印、不提交。本机输出应放 `.runtime/` 或其他忽略的目录。
- 编辑 Next.js 代码前阅读 `frontend/AGENTS.md` 指定的版本文档。用户要求保持单代理。

接口手册：[Java API](docs/Java后端接入指南.md)；架构：[Java 后端](docs/Java后端架构.md)；模型配置：[Agent 问答](docs/Agent知识问答接入.md)。
