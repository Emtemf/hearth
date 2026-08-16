# Hearth 模块规则路由

不要在本文件复制模块职责或领域 invariant；实现前按改动范围读取 canonical spec：

- 模块边界与依赖方向：`docs/02-modules.md`
- TaskRequest / Spec / Plan / Execution 与 Evidence：`docs/09-g4c.md`
- 数据库表与 migration 分期：`docs/03-schema.md`
- Internal Dispatch、Task Tree / Session Graph 与 A2A Adapter：`docs/05-a2a-and-loops.md`
- WorkerClient、进程与远机协议：`docs/12-worker.md`
- Pi Agent 外部 runtime / RPC Adapter 边界：`docs/15-pi-agent-adr.md`
- 超时、重试、fencing、取消与恢复：`docs/11-resilience.md`
- 不可违反的项目 constitution：根目录 `AGENTS.md`

发现文档冲突时停止扩大改动，按 `docs/16-spec-governance.md` 的 owner 表确认 canonical source，并在同一变更
中修正派生摘要；不要把新的规则继续复制到本文件。
