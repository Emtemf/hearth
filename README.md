# Hearth

Hearth 是一个面向 AI 编码 Agent 的自托管编排与可观测平台。它计划统一协调 Claude Code、Codex、Gemini CLI、opencode、Pi Agent 等工具，让会话路由、证据、预算、生命周期和记忆始终处于使用者控制之下。

> **当前状态：架构与规格设计阶段。** 仓库目前包含设计文档和工程约束，Hearth 运行时与 Web UI 尚未实现，暂时没有可执行的安装或快速开始流程。

## 现在可以做什么

当前仓库用于在实现前公开评审 Hearth 的架构、安全边界和验收标准。你可以：

- 从[总体架构](docs/01-architecture.md)了解控制面、数据面和透明网关的设计。
- 从[路线图](docs/07-roadmap.md)查看 M1～M3 的范围与可执行验收标准。
- 从 [AGENTS.md](AGENTS.md)检查项目必须遵守的技术决策和明确不做的事项。
- 通过 Issue 提出设计矛盾、遗漏的失败场景、安全风险或可验证的替代方案。

`docker-compose.yml` 和 `.env.example` 目前只描述规划中的本地依赖与配置边界，不代表 Hearth 应用已经可以启动。
默认 Compose 只启动 M1 的 PostgreSQL；`--profile m2` 增加 Redis，`--profile m3` 增加 Ollama。

## 适合谁

Hearth 面向希望自托管 AI Agent 基础设施，并需要会话级模型路由、完整可观测性、多 Agent 协作、证据验证和跨机器 Worker 管理的个人开发者或小型团队。

如果你需要的是已经可用的 Agent 框架、生产级网关或开箱即用的 Jarvis 助理，当前阶段的 Hearth 还不适合直接使用。

## 设计概览

```text
Web UI / 通讯触发 / Schedule
             │
             ▼
     Task 编排与 G4C+E 验证
             │
             ▼
         WorkerClient
             │
             ▼
Worker 上的 Agent CLI ──> Hearth 透明网关 ──> wire-compatible Provider
             │                    │
             └──── Artifact ──────┴──── Session / Invocation / Exchange
                                      持久化到 PostgreSQL
```

- **控制面**负责 Task、Session、Invocation、预算、取消、重试和人工决策门。
- **数据面**负责模型请求的字节级透传、system prompt 观测、路由和 token/成本记录。
- **Worker**只负责启动本机 Agent 进程并上报状态；所有调用方只能依赖 `WorkerClient`。
- **Artifact**是 G4C+E 中 Evidence 的物质载体；没有可独立核验的 Artifact，完成声明不成立。

## 项目目标

- 通过响应流字节保真、请求未知字段保留的透明模型网关观测完整 Agent 会话。
- 为每个 Session 路由到显式配置且 wire protocol 兼容的模型端点。
- 编排具有不同职责的 Agent，并提供预算约束、防套娃和人工升级机制。
- 使用 G4C+E，要求重要完成声明必须附带可独立核验的 Artifact 证据。
- 在多台已认证的 Worker 机器上运行 Agent，同时避免向 Agent 暴露上游凭证。
- 提供持久记忆、定时任务和通讯集成，但不允许 Agent 创建失控的自动化。

## 里程碑

- **M1——可观测 Session：** 通过 Hearth 启动 Claude Code，并在 Web UI 查看 system prompt、完整对话、token 用量和录制缺口。
- **M2——多 Agent 执行：** 把真实编码任务分配给协作 Agent，并查看 A2A 消息、证据、预算消耗和取消生命周期。
- **M3——助理自动化：** 通过通讯软件触发任务和提醒，引入持久调度、记忆检索与人工决策门。

每个里程碑以验收证据为完成条件，而不是以功能清单或 Agent 自报结论为准。完整规格见[路线图](docs/07-roadmap.md)。

## 核心架构约束

- 使用 Java 21、Spring Boot 4、Spring MVC 和虚拟线程。
- PostgreSQL 是持久状态的唯一真相源；Redis 不保存编排主状态。
- 网关以流式方式转发响应字节，不缓冲完整 SSE 响应。
- Spring AI 仅用于记忆提炼、Embedding、pgvector 集成和 Hearth MCP Server，不用于实现透明网关。
- Agent 进程只能通过 `WorkerClient` 抽象启动。
- Provider 凭证只能来自环境变量或 Secret Manager，禁止写入源码或进程参数。
- M1 只支持 wire protocol 兼容的上游路由；跨协议转换必须由显式 Adapter 实现。

完整的项目宪法与技术约束见 [AGENTS.md](AGENTS.md)。

## 仓库结构

```text
AGENTS.md          项目愿景、技术栈、边界和验收约束
CLAUDE.md          Claude Code 项目配置入口
.claude/rules/     Hearth 的架构与模块硬约束
docs/              架构、协议、Schema、韧性、UI 与路线图
.env.example       仅含占位值的环境变量模板
docker-compose.yml 规划中的 PostgreSQL、pgvector 与 Ollama 本地依赖
```

## 文档索引

### 架构与边界

- [技术栈决策](docs/00-stack-decision.md)
- [总体架构](docs/01-architecture.md)
- [模块边界](docs/02-modules.md)
- [依赖版本](docs/10-dependencies.md)

### 数据与接口

- [数据库 Schema](docs/03-schema.md)
- [Hearth MCP 工具](docs/04-platform-mcp.md)
- [REST API 契约](docs/05-rest-api.md)
- [A2A 与防套娃](docs/05-a2a-and-loops.md)

### 生命周期与产品设计

- [记忆系统](docs/06-memory.md)
- [路线图与验收](docs/07-roadmap.md)
- [部署与运维](docs/08-operations.md)
- [G4C+E 任务模型](docs/09-g4c.md)
- [韧性设计](docs/11-resilience.md)
- [Worker 蜂窝架构](docs/12-worker.md)
- [Web UI 设计](docs/13-ui.md)
- [自动化设计](docs/14-automation.md)
- [Pi Agent 集成决策](docs/15-pi-agent-adr.md)

## 当前阶段如何参与

当前最有价值的贡献是：

- 指出跨文档矛盾、不可执行的验收条件和遗漏的故障恢复场景。
- 补充有真实事故或上游文档支撑的安全、协议兼容性与多 Agent 失败案例。
- 提议更小、更可验证的 M1 实现切片。
- 改进文档的准确性、可读性和术语一致性。

请先通过 [GitHub Issues](https://github.com/Emtemf/hearth/issues) 讨论较大的实现或架构改动。M1 实现尚未开始，不建议直接提交大规模功能代码。Issue 和附件中不得包含 API key、capability token、真实 system prompt、原始对话或其他敏感数据。

## 安全状态

Hearth 尚未经过安全审计，也未达到生产环境或不可信网络的部署条件。不要把当前设计或未来的早期实现暴露到公网，也不要用它处理生产凭证或敏感会话。

M1 设计要求本地服务只绑定 loopback，但仍必须提供本地认证和 CSRF 防护。多机部署必须使用加密私网或受信 TLS，并同时保留应用层认证。

禁止提交真实凭证。开始实现后，只能将 `.env.example` 复制为本地且已被 Git 忽略的 `.env` 文件，再填写本机配置。报告安全问题时不要在公开 Issue 中粘贴 secret、原始录制内容或可复现的私密数据；当前仓库尚未建立私密漏洞接收渠道。

## 许可证

本项目使用 [Apache License 2.0](LICENSE) 开源许可证。
