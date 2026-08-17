# Spec delta

## ADD / MODIFY

- `docs/07-roadmap.md`：将 M1.0 的最低实现物明确为独立、可重复的 Claude Code subprocess contract harness；fixture 通过不等同于真实 CLI 验收。
- `docs/12-worker.md`：为 M1.0 spike 增加 `ProcessReusePolicy` 的证据记录要求，确认真实 CLI 后才固定策略。
- 根 Maven reactor：只增加 `hearth-worker` 模块；该模块只包含 M1.0 harness，不代表 Worker daemon 已实现。
- `hearth-worker`：增加进程命令形状、严格 NDJSON line parser、受控 child environment builder、fixture harness 与测试。

## Compatibility

- 不产生数据库 migration、REST/wire API 或持久化兼容性变化。
- 生产 Worker 仍必须在后续 M1.2 slice 中实现 `WorkerClient`、本机认证 transport、进程 generation 与 durability。
- 真正的 Claude Code smoke test 仅在显式 opt-in 下运行，且不读取或输出 secret 内容。
