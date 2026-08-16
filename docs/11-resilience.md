# 韧性设计

韧性不是一个模块，是所有模块必须遵守的横切约束。
集中在这里定义，各模块实现时参照，不分散。

---

## 超时配置

| 场景 | 超时值 | 超时后动作 |
|---|---|---|
| 网关上游 API（Anthropic/OpenAI） | 120s | 返回 504，agent 收到后自行决定 |
| Dispatch 消息投递 | 30s | 重试，见重试策略 |
| Session 进程代次运行 | 60min（Task 级可配） | 进入进程终止流程；非终态 Invocation → TIMED_OUT |
| Invocation first event | 30s | → FAILED，保存已脱敏 stderr 诊断 |
| Invocation idle | 5min | → TIMED_OUT；关闭等待通道并按策略终止/保留 Session 进程 |
| Checkpoint 等待 | 10min | 按当前 PlanVersion.correctionPolicy 处置 |
| Inbox suspend_wait（ephemeral 任务） | 24h 发二次提醒，72h 自动取消 | → CANCELLED |
| Inbox suspend_wait（durable 任务） | 永不自动取消，24h 发提醒 | 等人处理 |
| 强制停机等待（Worker ONLINE/可达） | SIGTERM 后 8s，随后 SIGKILL；总终止确认 ≤10s | 仍无法确认则保持 CANCELLING 并告警 |
| Worker 心跳超时 | 90s 无心跳 → Worker 标记 OFFLINE | 该 Worker 的 session 进 Inbox |

---

## 重试策略

**可重试的错误**：网络超时、5xx、429（限流）
**不可重试的错误**：401/403（鉴权）、400（请求格式错）、业务规则违反

**策略**：指数退避 + jitter
```
base = 1s，max = 30s，最多 3 次
第1次重试：1s ± random(0, 0.5s)
第2次重试：2s ± random(0, 1s)
第3次重试：4s ± random(0, 2s)
超过 3 次 → 死信队列 → Inbox task_failed（附带完整错误证据 Artifact）
```

**429 特殊处理**：读取响应头 `Retry-After`，按指定时间等待，不用指数退避。

---

## 幂等

| 操作 | 幂等机制 |
|---|---|
| Dispatch 创建 | 可信 MCP/Adapter 边界签发或确定性派生 `commandId`，`(from_session_id, command_id)` 唯一，重试返回原 messageId |
| Dispatch 投递 | 已创建消息的 `messageId` 作为接收幂等键，接收方 upsert |
| 有副作用的工具/IM 操作 | 调用前持久化 claim；稳定 `commandId` 跨重试复用，返回已提交结果 |
| Invocation 启动 | `(session_id, command_id, attempt)` 唯一，只有 CAS winner 可发 LAUNCH/CONTINUE |
| 任务触发 | 优先 `(source, externalEventId)` 唯一；无原生 ID 时才用带短 TTL 的 fallback fingerprint，并保留可能重复的人工裁决路径 |
| 预算扣除 | `SELECT FOR UPDATE` 行锁，扣款和记录原子完成 |
| 网关录制 | 不要求幂等，丢了就丢了 |
| Claim 验证 | 每次 attempt 新增 VerificationRecord；source state 不同则旧 PASSED 转 STALE，不得复用 |

---

## 状态机

### TaskRequest / TaskExecution

```
TaskRequest:
INTAKE → CLARIFYING → SPECIFIED
                  └→ REJECTED / CANCELLED

TaskExecution（固定引用 immutable TaskSpecVersion + PlanVersion）:
READY
  → RUNNING
RUNNING
  → AWAITING_HUMAN（suspend_wait 触发 / 预算达到 100% / escalate）
  → VERIFYING（执行 Claim 已提交）
  → FAILED（不可恢复错误，超重试上限）
  → FAILED（超过 deadline，terminal reason=TIMED_OUT）
  → CANCELLING（收到取消信号）
VERIFYING
  → SUCCEEDED（当前 spec 的所有 Goal Claim 有非 STALE 的 PASSED VerificationRecord）
  → RUNNING（按 Correction retry / try_alternative）
  → AWAITING_HUMAN（需要 HUMAN verifier 或决策）
  → FAILED（不可恢复）
AWAITING_HUMAN
  → RUNNING（人工批准且 expectedTaskVersion CAS 成功）
  → CANCELLED（人工拒绝 / ephemeral 72h 超时）
  [全局 gate：launch/continue/retry/resume/Dispatch request/schedule continuation 全部拒绝]
CANCELLING
  → CANCELLED（所有子 Session 已终止）
任意终态之前 → CANCELLING（强制取消，见强制取消）
```

### Session 与 Invocation

```text
Session:    PENDING → LAUNCHING → READY ↔ ACTIVE
            ACTIVE/READY → TERMINATING → TERMINATED
            ACTIVE/READY → CRASHED（进程意外退出或确认丢失）

Invocation: PENDING → RUNNING → SEMANTIC_COMPLETED
            RUNNING → FAILED / TIMED_OUT
            RUNNING → CANCELLING → CANCELLED
```

Session 是可跨多轮和进程代次的逻辑会话；Invocation 是一次 user turn / agent activation。
以下信号独立记录，不得互相推导：

```text
semantic_completed      本次 Invocation 的业务工作完成
stream_eof              当前事件流关闭
process_exited          指定 processGeneration 的进程退出
transport_disconnected  Worker 或本地事件 transport 断开
```

`semantic_completed` 到达后 Invocation 可以终态，即使可复用 CLI 进程与 stdout 仍存活；transport 断开只触发
reconcile，不能立即假定进程死亡。UI 的“正在执行”以 Invocation 为准，不以 Session 进程是否存活为准。

创建 Invocation 的短事务同时执行 `Session READY → ACTIVE`、插入 PENDING Invocation、durable
`worker_command` 和 domain event；提交后发送 INVOKE。语义完成/失败/超时的 CAS winner 写 Invocation 终态，
若 `STREAMING_STDIN` 同代进程仍可复用，或 `RESUME_PER_INVOCATION` 已正常保存 ResumeDescriptor 并退出，
则同时执行 `Session ACTIVE → READY`；只有异常退出/无法建立连续性时才进入 `TERMINATING/CRASHED`。因此
Session 状态与“是否正在回复”保持一致，但 UI 仍以非终态 Invocation 作最终判断。

### Internal Dispatch 消息

```
DISPATCHED → DELIVERED → ACKNOWLEDGED
DELIVERED → FAILED（30s 超时）→ 重试 → 死信
```

### Inbox 条目

```
OPEN → RESOLVED（人工处理）
OPEN → ESCALATED（24h 无响应）→ RESOLVED / CANCELLED（72h）
OPEN（durable）→ ESCALATED（24h）→ ESCALATED（无限循环直到人处理）
```

---

## 任务重要性：persistence 字段

```
ephemeral（默认）  超时后自动取消。适合：cron 报告、日常助理任务
durable            永不自动取消，必须人工处理。
                   适合：生产部署审批、财务操作
```

设置方式：
- 触发时决定，不是运行时决定
- 飞书/Telegram 消息加 `!important` 前缀 → `durable`
- Web UI 手动创建时可显式选择
- cron 任务默认 `ephemeral`

---

## 强制取消：必须一定生效

取消信号走独立的高优先级路径，不受任务繁忙影响。

**M2 验收含义**（精确定义，不是模糊的"10s内生效"）：
- cancel 指令 **accepted**：≤ 1s
- Worker 为 ONLINE 且控制通道可达时，进程确认终止：SIGTERM 后等 8s，超时 SIGKILL，**≤ 10s**
- Task 标记 CANCELLED：进程终止后 ≤ 1s

网络分区时中心不可能证明远端 PID 已在 10s 内死亡。此时仍必须在 ≤1s 内持久化取消、撤销 gateway/MCP
capability（阻止新的模型/MCP 调用）、Task 保持 `CANCELLING` 并告警；Worker 重连后第一优先级执行 durable
cancel，再确认进程死亡。若未来要求“网络分区也必须 10s 杀进程”，必须引入 ≤10s Worker execution lease，
并接受短暂断网会杀掉所有进程；该策略与当前“断线继续运行”不能同时成立。

```
1. 数据库事务中锁 Task：`cancellation_version + 1`、Task → CANCELLING，
   并为当时每个非终态后代 Session 写一条 cancellation_outbox
   （幂等键=`taskId+cancellationVersion+sessionId+processGeneration`）
2. 编排层有独立虚拟线程消费 outbox，不走普通任务队列
3. Worker 只在命令 `processGeneration ==` 本地当前进程 generation 时执行
4. 向每个目标进程发 SIGTERM；8s 后仍存活 → SIGKILL
5. 取消中禁止启动新 session；竞态启动的 session 追加到同一 cancellationVersion
6. 所有后代进程确认死亡后 Task → CANCELLED，与 domain_event 在同一短事务提交
7. 无论成功、失败还是取消，必须在 finally/等价清理路径中完成所有等待通道：Worker event stream、
   tool future、checkpoint waiter、Dispatch waiter 与 Invocation completion；不得留下永久等待 consumer
```

**Worker 断线时的补偿**：
- 取消指令持久化到 Postgres，不只放内存队列
- Worker 重连后，cancellation_outbox 里未确认的指令重发
- 两个 fencing 维度不能混用：Task `cancellation_version` 标识哪次取消，Session
  `fencing_generation` 标识哪代进程
- 迟到命令 generation 小于当前值时拒绝，防止误杀 replacement session；大于当前值时触发对账

**级联取消**：取消父任务必须自动取消所有子任务，包括子任务的子任务（递归向下），防止孤儿进程在后台烧钱。

---

**执行状态事务规则**：聚合状态 CAS/行锁、`domain_event` 和必要 outbox 在同一短事务提交；只有 CAS winner
能执行后续动作。事务内禁止 Worker/provider/对象存储/IM 网络 I/O，统一使用“短事务 claim → 事务外 I/O
→ 新短事务 CAS 写回”。后台 reconciler 扫描长期非终态 Invocation 并补齐唯一终态，终态写回失败不得
只打日志后放弃。

---

## 部分失败（Partial Failure）

多个子任务中部分失败，默认策略：
- **保留已成功的**：不回滚已完成的子任务
- **只重试失败的**：幂等前提下可安全重试
- **重试超限**：整体进 Inbox，附带已成功子任务的 Artifact + 失败原因 Evidence
- **人来决定**：继续（修复后重试失败部分）/ 回滚（手动按 undo 清单操作）/ 接受部分结果

---

## 进程崩溃检测

控制面每 30s 检查每个 `READY/ACTIVE/LAUNCHING` Session 当前进程代次是否存活：
- 本机：`process.isAlive()`
- 远机：Worker 心跳（见 `12-worker.md`）

崩溃时：Session → CRASHED，对应 Task 进 Inbox，附带最后已知的 Artifact 列表。

---

## 优雅停机

收到 SIGTERM 时：
1. 停止接收新任务（拒绝新的 Task 创建请求）
2. 等待正在运行的任务到达最近的 checkpoint（不强制中断）
3. 超过 30s → 强制进入 CANCELLING 流程
4. 所有 Session 终止后停机

---

## 中心服务重启恢复（Cold Start Recovery）

Hearth 中心重启后，Postgres 里可能有非终态的任务。
**启动时必须执行恢复扫描**，否则这些任务永远卡在中间状态：

```
启动流程：
1. Flyway migration（先于任何业务逻辑）
2. 恢复扫描（扫全部非终态 Task/Session/Invocation）：
   当前 session_process 为 LAUNCHING/ALIVE/TERMINATING → 查 Worker 在线状态 + `(sessionId, processGeneration)` 进程身份
     ├─ Worker 在线 + 同代进程存活 → M1 本机 reconcile 当前状态；M2 远机按 last_event_seq 重放并继续监控
     ├─ Worker 在线 + 同代进程已死 → Session → CRASHED；非终态 Invocation 补写 FAILED
     └─ Worker 离线             → Session 保持待对账并显示 STALE；超过恢复期限才标 CRASHED
   READY + RESUME_PER_INVOCATION + 当前无存活进程 → 正常可恢复状态，不标 CRASHED
   非终态 Invocation           → 检查 semantic completion/进程/事件水位，terminal reconciler 只写一个终态
   AWAITING_HUMAN               → 保持不动，人还没回复；所有自动执行入口继续受 gate 拦截
   CANCELLING                   → 继续执行取消流程（重发 SIGTERM）
   CLARIFYING 的 TaskRequest    → 保持等待或恢复 context-gathering command（幂等）
   READY 的 TaskExecution      → 重新执行尚未 committed 的 launch command，不重新生成 spec/plan
3. 恢复扫描完成后，开始接受新请求
```

恢复扫描是阻塞启动的（不完成不接新请求），目的是保证对外可见的状态是一致的。
扫描超时（默认30s）则继续启动，但受影响 Session 保持非终态并在 API/UI 投影为 `STALE`，禁止杜撰终态；
超过可配置 recovery grace period 后，确认无法对账的 Session 才转 `CRASHED`，有关 Task 按 correction
进入 `AWAITING_HUMAN` 或 `FAILED`。`STALE` 是新鲜度投影，不是持久化 Session status。

---

## AWAITING_HUMAN → RUNNING 恢复细节

挂起时 agent 进程的处置按 `persistence` 决定，不是统一策略：

| persistence | 挂起时 | 批准后恢复 |
|---|---|---|
| `ephemeral` | **进程保持运行**，等待回复（省资源用 `suspend_wait` 的超时兜底） | 直接 notify agent 继续，上下文完整 |
| `durable` | 超过 30min 未批准 → **进程终止**，释放资源 | 重新 spawn，从最后的 checkpoint 重建上下文，重新注入 Goal + 已有 Artifact 列表 |

**上下文重建（durable 恢复时）**：
```
新 session 的 system prompt 追加：
  ## 恢复上下文
  任务已被人工暂停后批准继续。上次完成到 checkpoint: {lastCheckpointId}。
  已有产出 artifacts: {artifactIds}。
  继续从下一个 checkpoint 开始。
```

这样 agent 不需要重做已完成的工作，但也不依赖它自己的内部记忆（已丢失）。

---

## Adapter 专属恢复描述符

Session 只保存带版本的 `ResumeDescriptor`，不保存可被任意 CLI 解释的裸 token：

```json
{
  "adapterType": "claude-code",
  "descriptorSchemaVersion": 1,
  "adapterVersion": 1,
  "cliVersion": "2.1.226",
  "externalSessionId": "provider-issued-id",
  "carrier": "native-jsonl",
  "carrierRef": "worker-managed-relative-reference",
  "carrierSha256": "hex",
  "issuedAt": "...",
  "lastVerifiedAt": "..."
}
```

恢复前由对应 Adapter 校验类型、schema/CLI 版本和 carrier，并重新解析 `providerRouteId` 当前的 credential
reference。Pi 等外部 runtime 的原生 session 只能作为恢复材料，Hearth Postgres 仍是 Session/Invocation/Exchange
真相源；`carrierRef` 必须是 Worker 管理的相对引用，且 hash 校验失败或越界时 fail closed。原生恢复失败时创建新 continuity generation，用 Goal、Checkpoint、Transcript 与 Artifact 重建
上下文，UI 标记 `continuity=degraded`；禁止静默假装原生上下文已恢复。进程 owner lease 必须包含 generation、
expiry 和 CAS takeover，避免重启后旧 lease 永久阻塞。

---

## Claude Code 上下文压缩检测

Claude Code 在长会话中会自动压缩上下文（把历史总结成摘要，细节丢失）。
平台需要检测这个事件并重新注入关键上下文，否则 agent 会"忘记"当前 Goal。

**检测方式**：
- `sidecar` 模式：转录文件里有特殊压缩事件类型，hooks 可以捕获
- `full` 模式：网关观测到单次请求的 message 数量从 N 骤降到 1-2（说明发生了压缩）

**检测到压缩后**，通过 hooks / MCP notify 触发平台注入当前上下文摘要：

```
## 上下文提醒（系统自动注入）
当前任务 Goal：{task.goal}
当前进行到 Checkpoint：{currentCheckpointId}
相关 Artifacts：{artifactIds}
预算剩余：{budget.remaining}
```

这条消息作为 user turn 注入（不修改 system prompt，因为那需要重启 session），
agent 读到后能重新锚定任务目标。

注入时机：检测到压缩后的**下一个 user turn 之前**，不是立刻——
立刻注入会打断 agent 当前的工具调用链。

---

事件先随聚合状态提交为 `domain_event`，再由单 publisher 为已提交事件分配单调递增的
`event_publication.publication_seq`；UI/SSE 只按 publication sequence 排序，**不用时间戳排序，也不直接把
BIGSERIAL event_id 当提交顺序**。原因：多机时钟会漂移，而 PostgreSQL sequence 的分配顺序也不等于事务
提交顺序。时间戳只用于展示和超时计算。

wall time 不作为 budget node 的可划拨余额。根 Task 保存中心时钟计算的绝对 `deadline_at`，Child deadline
只能相同或更早；Session/Invocation 仍有独立 timeout。token/USD 才进入可消费预算和划拨账本。

---

## 分布式追踪

所有链路统一传播 `traceId`：
- 应用日志：MDC 注入 `traceId`，每条日志都带
- Hearth 内部 HTTP/WebSocket：请求头或 envelope 携带 `X-Trace-Id: {traceId}`
- Provider 上游：默认剥离 `X-Trace-Id` 等 Hearth 内部头，Gateway 通过本地 Exchange 关联追踪；只有
  provider route 明确声明并测试过的 vendor metadata 字段才可发送，避免泄露内部拓扑标识
- Internal Dispatch/A2A Adapter：TraceContext 已含 traceId
- Worker 日志：Worker 向中心上报日志时带 traceId

查一个任务的全部日志：`grep traceId=xxx` 或 SQL `WHERE trace_id = 'xxx'`。

---

## Schema 演进

- 事件 payload 用 JSONB，消费者忽略未知字段（向前兼容）
- envelope 里有 `schema_version` 字段，用于未来需要 migration 时区分
- Flyway 管理表结构变更，JSONB 内部 schema 通过代码版本控制
- 禁止在 Flyway migration 里做数据修改（只做结构变更），数据 migration 用独立的一次性脚本
