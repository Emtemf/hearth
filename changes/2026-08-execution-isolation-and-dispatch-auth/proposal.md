# 执行隔离与 Dispatch 授权收敛

## 状态

- 状态：规格修复，运行时实现尚未开始
- 日期：2026-08-16
- 范围：Worker 执行隔离、跨 Session 授权、事件 fencing、deadline 与 Schedule 持久化

## Context

上一轮规格已经定义了 capability、Worker command、Task Tree 和 Artifact，但仍有几个可被实现误读的缺口：

1. `LocalWorkerClient` 一处写成直接使用 `ProcessBuilder`，而另一处又声明存在独立 `hearth-worker`；同 UID 的 Agent 进程仍可能读取 Hearth API 的环境、文件或 provider secret。
2. `existingSessionId` 和 `artifactIds` 只有外键/存在性语义，没有把访问权限收敛到当前 workspace、Task Tree 和 Evidence scope。
3. Worker event receipt 只按 process generation 校验，未把当前 `connectionId` 和 `launchId` 写进不可绕过的接收事务。
4. deadline 只在 Task 草案中存在，Invocation、Worker command、重试和恢复没有共同的有效 deadline 快照。
5. Schedule 只有文档模型，缺少 V006 中可 claim、恢复、幂等和 `QUEUE_ONE` 的持久化结构。

## 决策

1. `LocalWorkerClient` 只通过本机受认证 transport 调用 `hearth-worker`；仅 Worker daemon 可使用 `ProcessBuilder`。
2. 本机明确三类服务身份：`hearth-api`、`hearth-worker`、低权限 `hearth-agent`。provider secret 只对 API/Gateway 身份可读，Agent 不构成与宿主用户等价的安全边界，但不再与 API 共享服务身份。
3. Dispatch 的 `ExistingSession` 必须属于同一 workspace 且处于当前 Task Tree 允许的祖先/当前/后代关系；应用层在创建消息的事务内执行授权检查。
4. Dispatch artifact 和 Claim artifact 必须通过 task-scoped visibility/evidence policy 检查，不能仅凭 UUID 外键引用任意 Artifact。
5. Worker 接收事件的固定事务先锁定 Worker 并验证 `workerId + connectionId`，再验证精确 `(sessionId, processGeneration, launchId)`，最后检查 event sequence；失败不得推进 receipt 水位。
6. Invocation 和 Worker command 保存不可变 `deadline_at` 快照；每次 claim、重试、`Retry-After`、reconcile 和恢复都使用父级 deadline 的最小值，过期后不得发送新副作用命令。
7. V006 增加不可变 TaskTemplateVersion、Schedule、ScheduleRun 和 notification delivery claim；`(schedule_id, scheduled_fire_at)` 是最终幂等键，`QUEUE_ONE` 通过数据库约束实现。

## Compatibility / migration

- 这是文档阶段的 contract 收敛，不执行数据库 migration，不修改已发布 Flyway 文件。
- 未来实现按 `docs/07-roadmap.md` 的 M1/M2/M3 slice 增量增加字段和表；现有目标 Schema 草案同步增加约束说明。
- WorkerClient 的领域接口保持不变；只替换 LocalWorkerClient 的进程启动实现为受认证本机 transport。
- API/MCP 对非法跨树 Session、越界 Artifact 和失效 deadline 返回结构化错误，不向 wire 层暴露内部 secret。

## 验收

- M1 contract test 证明 Agent 与 API/Worker 的 UID、环境和文件权限不同；Agent 环境无 provider、数据库、IM 或 admin secret。
- M2 API/MCP contract test 覆盖跨 workspace、跨 Task Tree、终止 Session、不可见 Artifact、Claim scope mismatch 和 `AWAITING_HUMAN` gate。
- Worker integration test 覆盖旧 connection、旧 launch、旧 generation、乱序/重复 event，以及提交前崩溃后的 replay。
- deadline test 覆盖重试等待超过 deadline、`Retry-After` 超时、中心重启 reconcile 和 UNKNOWN_COMMIT_STATE。
- Schedule integration test 在全新 PostgreSQL 上覆盖双实例竞争、SKIP、RUN_ONCE、QUEUE_ONE、claim lease 过期恢复和通知幂等。
