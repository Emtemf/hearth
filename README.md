# Hearth

Hearth 是一个面向 AI 编码 Agent 的自托管编排与可观测平台。它计划统一协调 Claude Code、Codex、Gemini CLI、opencode 等工具，同时让会话路由、证据、预算、生命周期和记忆始终处于使用者的控制之下。

> **当前状态：架构与规格设计阶段。** 仓库目前主要包含设计文档和工程约束，运行时与 Web UI 尚未实现。

## 项目目标

- 通过字节级透明的模型网关观测完整 Agent 会话。
- 为每个 Session 路由到显式配置且 wire protocol 兼容的模型端点。
- 编排具有不同职责的 Agent，并提供预算约束、防套娃和人工升级机制。
- 使用 G4C+E，要求重要完成声明必须附带可独立核验的 Artifact 证据。
- 在多台已认证的 Worker 机器上运行 Agent，同时避免向 Agent 暴露上游凭证。
- 提供持久记忆、定时任务和通讯集成，但不允许 Agent 创建失控的自动化。

## 里程碑

- **M1——可观测 Session：** 通过 Hearth 启动 Claude Code，并在 Web UI 查看 system prompt、完整对话、token 用量和录制缺口。
- **M2——多 Agent 执行：** 把真实编码任务分配给协作 Agent，并查看 A2A 消息、证据、预算消耗和取消生命周期。
- **M3——助理自动化：** 通过通讯软件触发任务和提醒，引入持久调度、记忆检索与人工决策门。

可执行的验收标准见[路线图](docs/07-roadmap.md)。

## 核心架构约束

- 使用 Java 21、Spring Boot 4、Spring MVC 和虚拟线程。
- PostgreSQL 是持久状态的唯一真相源；Redis 不保存编排主状态。
- 网关以流式方式转发响应字节，不缓冲完整 SSE 响应。
- Spring AI 仅用于记忆提炼、Embedding、pgvector 集成和 Hearth MCP Server，不用于实现透明网关。
- Agent 进程只能通过 `WorkerClient` 抽象启动。
- Provider 凭证只能来自环境变量或 Secret Manager，禁止写入源码或进程参数。
- M1 只支持 wire protocol 兼容的上游路由；跨协议转换必须由显式 Adapter 实现。

完整的项目宪法与技术约束见 [AGENTS.md](AGENTS.md)。

## 文档索引

建议从以下文档开始：

- [技术栈决策](docs/00-stack-decision.md)
- [总体架构](docs/01-architecture.md)
- [模块边界](docs/02-modules.md)
- [数据库 Schema](docs/03-schema.md)
- [REST API 契约](docs/05-rest-api.md)
- [G4C+E 任务模型](docs/09-g4c.md)
- [依赖版本](docs/10-dependencies.md)
- [韧性设计](docs/11-resilience.md)
- [Worker 蜂窝架构](docs/12-worker.md)
- [Web UI 设计](docs/13-ui.md)
- [自动化设计](docs/14-automation.md)

## 安全状态

Hearth 尚未达到生产环境或不可信网络的部署条件。M1 设计要求本地服务只绑定 loopback，但仍必须提供本地认证和 CSRF 防护。多机部署必须使用加密私网或受信 TLS，并同时保留应用层认证。

禁止提交真实凭证。开始实现后，只能将 `.env.example` 复制为本地且已被 Git 忽略的 `.env` 文件，再填写本机配置。

## 许可证

本项目使用 [Apache License 2.0](LICENSE) 开源许可证。
