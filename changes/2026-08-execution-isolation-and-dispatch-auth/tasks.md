# Tasks and acceptance

## 已完成

- [x] 记录同 UID Agent 隔离、Dispatch 授权、Artifact scope、event fencing、deadline 和 Schedule persistence 的旧缺口与决策。
- [x] 定义 canonical owner 文档和派生摘要的更新范围。

## 文档任务

- [x] `LocalWorkerClient` 只通过本机受认证 transport 调用 `hearth-worker`。
- [x] 明确 `hearth-api`、`hearth-worker`、`hearth-agent` 身份、权限和 secret 边界。
- [x] 为 ExistingSession、Dispatch Artifact、Claim Artifact 增加 workspace/Task Tree/Evidence scope 授权。
- [x] 为 Worker event transaction 增加 connectionId、processGeneration、launchId 和 eventSeq fencing。
- [x] 为 Invocation/Worker command 增加 deadline snapshot 与 retry/reconcile 规则。
- [x] 为 V006 增加 TaskTemplateVersion、Schedule、ScheduleRun、delivery claim 与幂等约束。

## 文档验收

- [x] `git diff --check` 无输出。
- [x] 所有本地 Markdown 相对链接可解析。
- [x] JSON 示例在标记为 `json` 时均为单个有效 JSON 文档。
- [x] drift check 未命中旧的 LocalWorkerClient/ProcessBuilder 或授权术语；命令自身排除在扫描范围外。
- [x] credential-like scan 未发现真实密钥、token 或私钥。

## 实现前验收

- [ ] Architecture/schema/API contract tests 覆盖跨树 Session、Artifact scope、旧 Worker connection/launch 和 deadline gate。
- [ ] 全新 PostgreSQL 上按 V001+ 增量 migration 执行 migrate/upgrade/validate。
- [ ] M1 验收不同 UID、文件权限、child environment 与本机 Worker transport。
- [ ] M2 验收 Worker/Task/Dispatch/Evidence 核心后再执行 Pi compatibility slice。
- [ ] M3 验收 Schedule 双实例 claim、misfire/overlap、QUEUE_ONE、lease recovery 和 notification idempotency。
