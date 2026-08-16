# Spec delta

本变更更新以下 canonical owner 文档，并同步其派生摘要：

## ADD / MODIFY

- `docs/01-architecture.md`：明确控制面/数据面分离和 Pi RPC Adapter 边界。
- `docs/02-modules.md`：明确 Worker、Adapter 与 Pi runtime 的模块责任。
- `docs/03-schema.md`：收敛 M1/M2/M3 migration slice、Task 版本、Worker、credential、Evidence 和事件结构。
- `docs/04-platform-mcp.md`：收敛 Platform MCP 工具、幂等结果、Evidence 和任务上下文契约；补充 wire/domain enum casing 映射。
- `docs/05-a2a-and-loops.md`：收敛 Internal Dispatch、预算和环检测语义。
- `docs/05-rest-api.md`：收敛 REST/SSE、Session、Transcript、认证和授权边界。
- `docs/07-roadmap.md`：闭合 M1/M2/M3 验收和 Pi compatibility slice 范围。
- `docs/08-operations.md`：收敛部署、认证、retention 和运行配置边界。
- `docs/09-g4c.md`：收敛 G4C+E 对象、EvidenceClaim 和 VerificationRecord。
- `docs/10-dependencies.md`：锁定技术栈和 Pi 外部 runtime 基线。
- `docs/11-resilience.md`：收敛 timeout、retry、fencing、cancel、reconcile 和 resume。
- `docs/12-worker.md`：定义 WorkerClient、Worker command/event protocol、credential 和 Pi RPC Adapter。
- `docs/13-ui.md`：收敛 UI 状态和 transcript/recording 展示语义。
- `docs/14-automation.md`：收敛 Schedule、deadline、通知和递归隔离规则。
- `docs/15-pi-agent-adr.md`：确定 Pi 不嵌入 Hearth 核心，仅作为 Worker 上可选 RPC Adapter。
- `docs/16-spec-governance.md`：增加 automation/Schedule owner，并定义本变更流程。
- `AGENTS.md`、`CLAUDE.md`、`README.md`、`.claude/rules/*`：同步项目宪法、入口和 canonical source 路由。

## REMOVE / REJECT

- 不采用 `.codex/hooks.json` 作为项目契约；它不是当前已记录的 Codex 配置入口，且提醒式 hook 不构成治理约束。
- 不新增 Pi 专属核心数据库表。
- 不把 provider secret 或 capability token 放入 argv、日志、Artifact 或数据库明文。

## Compatibility

- 文档阶段无数据库 schema 或 wire API migration 执行。
- 未来实现必须按 `docs/07-roadmap.md` 的 M1/M2/M3 slice 增量创建 Flyway migration。
- MCP lowercase wire enum 与 core uppercase domain enum 的映射必须由 Adapter/transport 层统一实现；REST 和数据库维持领域值。
