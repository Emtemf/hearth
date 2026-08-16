# Spec delta

## ADD / MODIFY

- `docs/01-architecture.md`：明确 API、Worker daemon 与 Agent 的本机身份边界，以及 LocalWorkerClient 的本机 transport 边界。
- `docs/02-modules.md`：把 ProcessBuilder、Adapter 和 Agent 进程责任收敛到 `hearth-worker`。
- `docs/03-schema.md`：补充 connection/launch fencing、deadline 快照、task-scoped artifact contract 和 V006 Schedule 持久化草案。
- `docs/04-platform-mcp.md`：补充 ExistingSession、Artifact 和 Claim 的授权/可见性规则。
- `docs/05-a2a-and-loops.md`：增加 Dispatch target authorization 与 task-tree scope 规则。
- `docs/07-roadmap.md`：把本机三身份、Worker IPC、授权和 Schedule schema 验收写入对应 slice。
- `docs/08-operations.md`：修正同机部署拓扑并定义 `hearth-api`/`hearth-worker`/`hearth-agent` 身份与权限。
- `docs/11-resilience.md`：增加有效 deadline 传播和 Worker event fencing 的不可变处理顺序。
- `docs/12-worker.md`：LocalWorkerClient 改为受认证本机 transport；event transaction 增加 connectionId/launchId 校验。
- `docs/14-automation.md`：增加 Schedule、ScheduleRun、TaskTemplateVersion 与 delivery claim 的持久化要求。
- `AGENTS.md`、`README.md`：同步执行隔离与跨 Task 授权摘要。

## REMOVE / REJECT

- 不再把 LocalWorkerClient 描述为在 Hearth API 进程内直接调用 `ProcessBuilder`。
- 不允许使用 Session ID、Artifact ID 或 Schedule ID 的存在性作为授权替代。
- 不允许在 deadline 已过后重试、reconcile 或补发可能产生副作用的命令。

## Compatibility

本变更不改变现有 wire enum、WorkerClient 方法签名或 Task 生命周期名称。未来 migration 必须 append-only，并在空库和已有 M1 数据库上分别执行 migrate/upgrade/validate。
