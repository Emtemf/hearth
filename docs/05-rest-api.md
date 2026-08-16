# REST API 契约

Web UI 和后端之间的接口定义。所有响应用统一信封：
所有响应的结构如下；具体示例见下文的成功响应和错误响应。

信封适用于 JSON 业务 API；SSE 使用 `text/event-stream`，Artifact/raw 下载使用对应内容类型，Actuator 保持
Spring Boot 健康检查契约。它们的错误仍使用稳定 code，但不能为了套信封破坏各自的 wire protocol。

成功响应示例：
```json
{ "data": {"example": "value"},  "error": null }
```

错误响应示例：
```json
{ "data": null,   "error": { "code": "module.error_type", "message": "..." } }
```

M1 首次启动先由受信环境配置幂等创建 local workspace/root/provider route/coder profile/local worker，详见
`docs/08-operations.md`。浏览器不负责创建这些安全边界记录。

## API 安全基线

以下规则适用于所有管理、网关、MCP、Worker、SSE 和 Actuator 入口，不允许由单个 Controller
选择性实现：

- 在读取业务对象前完成身份认证、audience 校验和 workspace 授权；默认拒绝未知身份。
- 对 path/query/header/body 使用 schema、长度、枚举、分页上限和内容类型校验；拒绝未知或越界输入。
- 所有数据库访问使用 JPA/JdbcClient 的参数绑定或等价 prepared statement，禁止拼接外部输入生成 SQL。
- 按管理员会话、session capability、Worker identity 和来源地址分别限流；在建立 SSE/流式上游连接前完成配额占用，
  并限制并发连接数。健康检查也设置独立的低成本速率上限，429 响应携带 `Retry-After`。
- 浏览器只把 prompt、Transcript、工具输入和错误作为文本或结构化 JSON 渲染；禁止 `innerHTML`/
  `dangerouslySetInnerHTML`，并配置限制脚本来源的 CSP。下载内容使用 attachment + `nosniff`。
- 对外错误只返回稳定 code 和可操作但不含 secret、token、绝对路径、SQL、堆栈或上游原文的 message；
  详细上下文仅进入脱敏后的服务端诊断记录，system prompt 和 raw body 不写日志。
- 修改状态的管理 API 使用管理员会话、Origin 校验和 CSRF token；gateway/MCP/Worker token 不具备管理权限。

限流存储允许 Redis 提供临时计数，但它不是授权或业务状态的真相来源。Redis 不可用时，入口使用进程内保守上限，
不能退化为无限请求。

---

## Session

### GET /api/v1/sessions
列出所有 session，分页。

Query: `?page=0&size=20&status=ACTIVE&workspaceId=xxx`

Response data:
```json
{
  "content": [{
    "id": "uuid",
    "agentRole": "coder",
    "status": "ACTIVE",
    "observabilityLevel": "full",
    "effectiveModel": "claude-opus-4",
    "inputTokens": 24391,
    "outputTokens": 1847,
    "usdTotal": 0.34,
    "createdAt": "2026-08-09T10:00:00Z",
    "endedAt": null
  }],
  "totalElements": 42,
  "page": 0,
  "size": 20
}
```

### GET /api/v1/sessions/:id
Session 详情，含最近一次 exchange 的 system prompt 预览。

Response data:
```json
{
  "id": "uuid",
  "agentRole": "coder",
  "status": "ACTIVE",
  "observabilityLevel": "full",
  "observabilityReason": "proxied",
  "effectiveModel": "claude-opus-4",
  "requestedModel": "claude-sonnet-5",
  "providerRouteId": "uuid",
  "pricingStatus": "known",
  "wireProtocol": "anthropic",
  "workerId": "worker-A",
  "workspaceRootId": "uuid",
  "relativeCwd": "project",
  "traceId": "uuid",
  "spanId": "uuid",
  "depth": 1,
  "createdAt": "2026-08-09T10:00:00Z",
  "endedAt": null,
  "taskId": null,
  "latestSystemPrompt": "# Hearth\n## 愿景...",
  "latestSystemPromptRecordingStatus": "complete"
}
```

`latestSystemPrompt` 是最近一次 exchange 的完整 system prompt；`latestSystemPromptRecordingStatus` 取
`complete`、`partial` 或 `failed`。

### GET /api/v1/sessions/:id/transcript
**M1 核心接口。** 按顺序返回完整的 user/assistant/tool 对话内容。
这是"完整对话"页面的数据来源，与 exchanges 接口（token 统计用）分开。

Response data:
```json
{
  "sessionId": "uuid",
  "turns": [
    {
      "role": "user",
      "content": "帮我给 auth 模块加单测",
      "createdAt": "2026-08-09T10:00:01Z"
    },
    {
      "role": "assistant",
      "content": "我来分析一下当前的 auth 模块结构...",
      "createdAt": "2026-08-09T10:00:05Z"
    },
    {
      "role": "tool_use",
      "toolName": "read_file",
      "toolInput": {"path": "src/auth/AuthService.java"},
      "createdAt": "2026-08-09T10:00:06Z"
    },
    {
      "role": "tool_result",
      "toolUseId": "call_xxx",
      "content": "public class AuthService { ... }",
      "createdAt": "2026-08-09T10:00:06Z"
    }
  ],
  "systemPrompt": "# Hearth\n## 愿景...",
  "totalExchanges": 3,
  "recordingStatus": "complete"
}
```

`systemPrompt` 在 `full` 模式返回，在 `sidecar` 模式为 `null`。

### Transcript 去重与顺序语义

Anthropic 请求会在每次 exchange 中重复发送此前完整 message history，不能把每个请求体直接拼接，
否则 UI 会把旧 turn 重复 N 次。M1 使用以下确定性规则构建 transcript：

1. 每个 exchange 解析为规范化 `TurnCandidate`，保留 `exchangeId`、message 数组位置和 block 位置。
2. 对每个 turn 计算 `contentFingerprint = SHA-256(canonical JSON)`；canonical JSON 包含
   `role/type/tool_use_id/tool_name/content/tool_input`，对象 key 排序，忽略传输时间与 exchangeId。
3. 若新 exchange 的 messages 是已存规范化序列的前缀扩展，只追加最长公共前缀之后的新 turn。
4. 若历史被 Claude Code 压缩或改写、无法形成前缀，则保留新分支并生成
   `conversation.compacted`/`conversation.diverged` 事件；不能误删不同位置的相同文本。
5. `tool_use` 与 `tool_result` 以 provider 的 tool-use ID 关联；无 ID 时才使用 fingerprint + 顺序兜底。
6. 展示顺序用 `event_publication.publication_seq` 和 exchange 内位置，不按多机时间戳排序。

因此，去重键不是“文本全局唯一”：用户重复说两次“继续”必须显示两次。持久化实现可用规范化 turn 表，
也可在 M1 查询时按上述算法投影；契约和测试必须相同。

`recordingStatus` 是所有参与 exchange 的最差状态：任一 exchange 为 `failed` 则 transcript 为 `failed`；
否则任一为 `partial` 则为 `partial`。响应同时返回 gaps，避免一个汇总枚举隐藏具体缺口。

```json
{
  "gaps": [
    {
      "exchangeId": "uuid",
      "reason": "parse_failure",
      "detail": "response event 14 malformed"
    }
  ]
}
```

### GET /api/v1/sessions/:id/exchanges
该 session 所有模型调用的统计信息（token/成本/延迟），用于 Exchange 时间线 Tab。

Response data:
```json
{
  "content": [{
    "id": "uuid",
    "systemPrompt": "# Hearth\n## 愿景...",
    "effectiveModel": "claude-opus-4",
    "rewritten": true,
    "inputTokens": 19293,
    "outputTokens": 354,
    "cacheReadTokens": 512,
    "usd": 0.09,
    "latencyMs": 4823,
    "stopReason": "end_turn",
    "createdAt": "2026-08-09T10:00:05Z"
  }],
  "totalElements": 7,
  "page": 0,
  "size": 20
}
```

### GET /api/v1/sessions/:sessionId/exchanges/:exchangeId
返回单次调用的请求/响应元数据、解析出的 messages/tool blocks、录制状态和 gap；默认不返回原始字节。

### GET /api/v1/sessions/:sessionId/exchanges/:exchangeId/raw-request
### GET /api/v1/sessions/:sessionId/exchanges/:exchangeId/raw-response

从 `body_ref` / `response_body_ref` 流式下载录制原文，仅用于本地调试。要求：
- 只允许当前 workspace 的显式用户操作，不允许 agent capability token 调用。
- 默认 `hearth.recording.raw-download-enabled=false`；用户显式开启后仍要求管理员会话、CSRF/Origin 和审计。
- 返回前按协议移除 header/query/已知结构字段中的 credential，并扫描当前已加载 secret 的指纹；解析失败或
  命中无法安全替换的已知 credential 时返回 `409 recording.raw_not_safe`。
- 平台不能可靠识别用户消息、文件或工具输出里任意形态的第三方 secret；开启 raw download 的页面和响应头
  必须明确标为“包含未脱敏会话内容”，不能用“已自动脱敏”制造虚假安全保证。
- 响应使用 `Content-Disposition: attachment`、`X-Content-Type-Options: nosniff`，UI 不以内联 HTML 渲染。
- 每次下载写入审计事件，日志不记录 body。
- body 不存在时区分 `404 recording.body_not_recorded` 与 `410 recording.body_deleted`。

### POST /api/v1/sessions
手动创建 session（M1 主要入口，还没有任务编排时用）。

Request body:
```json
{
  "agentRole": "coder",
  "profileVersionId": "uuid",
  "providerRouteId": "uuid",
  "requestedModel": "claude-opus-4",
  "workspaceRootId": "uuid",
  "relativeCwd": "project",
  "observabilityLevel": "full",
  "workerId": "local"
}
```

调用方不能提交 `upstreamBaseUrl`、Gateway auth carrier、绝对 cwd、CLI executable、Extension/session path 或
provider secret。服务端从 Profile/Worker capability 选择 Adapter，并按 adapter + wire protocol 生成
`GatewayIngressAuthBinding`；从 `providerRouteId` 解析协议、host 白名单、模型白名单和 credential reference；从
`workspaceRootId + relativeCwd` 解析 canonical path，并拒绝目录逃逸。M1 只允许 route 到与当前 CLI
wire protocol 兼容的 endpoint，不做隐式跨协议翻译。

Response data: session 对象。`gatewayBaseUrl` 和已启用 audience 的 capability token 由 Session application
service 直接写入 `LaunchCommand` 的受保护环境，不返回浏览器，也不进入普通 API DTO。若 Worker 返回
`UNKNOWN_COMMIT_STATE`，Session 保持 `LAUNCHING` 并进入 reconcile，API 返回已创建的 Session 和明确状态，
不能再发一次新的 LAUNCH。

### Invocation

```text
POST /api/v1/sessions/:id/invocations
GET  /api/v1/sessions/:id/invocations
GET  /api/v1/invocations/:id
POST /api/v1/invocations/:id/cancel
```

创建 Invocation 必须带客户端生成的稳定 `commandId`；网络重试复用同一值，不重复启动本次逻辑执行。
响应分别返回 Session 与 Invocation 状态，UI 不再用 Session 进程存活推断“正在回复”。

```json
{
  "commandId": "uuid",
  "content": "继续完成当前 checkpoint",
  "expectedSessionVersion": 4
}
```

`semanticCompletedAt`、`processExitedAt` 和 `transportStatus` 是独立字段。取消 Invocation 会结束当前执行
和所有等待通道；是否保留可复用 Session 进程由 adapter/session policy 决定。

同一 Session 默认只允许一个非终态 Invocation。服务端在短事务中创建 Invocation 并建立 active binding，
提交后才调用 `WorkerClient.invoke`。冲突返回 `409 invocation.already_active`。Gateway 只把请求归属给该
唯一 active Invocation；没有 active binding 时返回 `409 gateway.no_active_invocation`，不按时间猜测。

---

## Task Request / Spec / Plan / Execution（M2）

### POST /api/v1/task-requests
创建原始需求，只要求 `title/brief/source`；服务端优先用 `source + externalEventId` 幂等。响应可能处于
`INTAKE` 或 `CLARIFYING`，不强迫调用方伪造完整 Goal。

### GET /api/v1/task-requests/:id
### POST /api/v1/task-requests/:id/clarifications
追加带 `human_stated` audit ref 的澄清；使用 request version CAS。

### GET /api/v1/task-requests/:id/spec-versions
### GET /api/v1/task-spec-versions/:id
### GET /api/v1/task-spec-versions/:id/plan-versions
Spec/Plan 均为 immutable；改需求或重规划创建新版本，不提供 PATCH 原地修改。

### GET /api/v1/tasks
### GET /api/v1/tasks/:id
### POST /api/v1/tasks
只接受 `requestId/specVersionId/planVersionId` 和运行参数。服务端校验版本链一致；Task 是 Execution，创建后
不得切换 spec/plan。
### POST /api/v1/tasks/:id/cancel

### GET /api/v1/tasks/:id/sessions
该任务下所有 session。

### GET /api/v1/tasks/:id/dispatch-messages
该任务树的 Hearth Internal Dispatch 消息（调用图数据源）。外部 A2A wire payload 由 adapter 单独审计，
不作为核心 API DTO。

Response data:
```json
{
  "nodes": [{
    "id": "session-uuid",
    "agentRole": "architect",
    "status": "TERMINATED",
    "terminalReason": "completed",
    "usd": 0.34,
    "startedAt": "...",
    "endedAt": "..."
  }],
  "edges": [{
    "id": "msg-uuid",
    "fromSessionId": "uuid",
    "toAgentRole": "coder",
    "kind": "REQUEST",
    "childTaskId": "uuid",
    "depth": 1,
    "createdAt": "..."
  }]
}
```

---

## Inbox（M2）

### GET /api/v1/inbox
Query: `?status=OPEN&page=0&size=20`

### POST /api/v1/inbox/:id/resolve
```json
{
  "action": "approve",
  "expectedInboxVersion": 3,
  "expectedTaskVersion": 17
}
```

批准/拒绝与 Task 状态转换使用版本 CAS；旧通知、重复点击或迟到 IM 回调返回 409，不得恢复已取消或
被其他操作推进的 Task。Task 为 `AWAITING_HUMAN` 时，所有 launch/continue/retry/resume/Dispatch request
入口都受同一个 application-level gate 拦截，而不是只让 UI 隐藏按钮。

---

## Artifact（M2）

### GET /api/v1/artifacts/:id
返回元数据和 `status`：`PENDING_UPLOAD / AVAILABLE / UPLOAD_FAILED / DELETED / SECURITY_PURGED`。
`DELETED` 仍返回 tombstone（sha256、sizeBytes、deletedAt），不会伪装成从未存在。

### GET /api/v1/artifacts/:id/content
仅 `AVAILABLE` 返回内容，Content-Type 按 type 决定，并设置 `nosniff` 与安全下载头。
`PENDING_UPLOAD` 返回 409，`UPLOAD_FAILED` 返回 424，`DELETED/SECURITY_PURGED` 返回 410；后者只暴露最小
安全审计 tombstone。

Artifact 引用由 `artifact_reference` 管理。被 verification、Inbox、operation log、memory 或事件引用时，
普通删除接口/清理器返回冲突。管理员安全清除是独立 API/权限，执行后关联 Verification 变为 INVALIDATED。

## Evidence（M2）

### GET /api/v1/tasks/:id/claims
### GET /api/v1/evidence-claims/:id/verifications

Claim 响应包含 statement、subject、sourceStateRef；Verification 包含 verifierKind/ref、method、Artifact links、
status 与验证时 sourceStateRef。API/UI 禁止把“有 artifactIds”投影成“已验证”。

---

## Worker（M2）

### GET /api/v1/workers
```json
{
  "content": [{
    "workerId": "worker-A",
    "hostname": "MacBook-Pro",
    "version": "0.1.0",
    "capabilities": ["claude-code"],
    "status": "ONLINE",
    "aliveProcesses": 2,
    "lastHeartbeat": "2026-08-09T10:01:30Z"
  }]
}
```

---

## 实时推送（SSE）

### GET /api/v1/sse
全局事件流。

### GET /api/v1/sse?traceId=xxx
过滤到指定任务的事件流。

可选 `after={publicationSeq}` 仅用于拉取 snapshot 后新建连接；普通断线由同一个原生 `EventSource` 通过
`Last-Event-ID` 自动恢复。两者同时出现时必须一致，否则返回 `400 sse.cursor_conflict`，禁止猜测游标。

SSE 事件格式：
```
id: {publicationSeq}
event: {eventType}
data: {EventEnvelope JSON}
```

前端用 `EventSource` 订阅，断线自动重连，服务端从 `Last-Event-ID` 续传。这里的 SSE `id` 是
`event_publication.publication_seq`，不是 `domain_event.event_id`。事件 payload 必须携带内部 `eventId`、
`publicationSeq`、`aggregateId`、`aggregateVersion` 和相关 `invocationId`；前端按 `publicationSeq` 去重。

原生 `EventSource` 不向业务代码暴露可可靠解析的 410 响应体。若 `Last-Event-ID` 已超过事件保留窗口，
服务端建立正常 `text/event-stream` 响应，发送一条控制事件后关闭：

```text
event: cursor.expired
data: {"snapshotUrl":"/api/v1/snapshot?traceId=xxx"}
```

前端收到后主动关闭旧 EventSource，拉 snapshot，原子替换 React Query 缓存，再用
`?after={eventWatermark}` 建立新连接。因此 durable 状态已提交但 final SSE 丢失时，UI 最终仍能收敛。

### GET /api/v1/snapshot

Query 可选 `?traceId=xxx`。返回当前授权范围内的 Task/Session/Invocation/Exchange 汇总投影和
`eventWatermark`。服务端在一个 `REPEATABLE READ` 只读事务中读取聚合快照，并读取该事务快照可见的最大
`event_publication.publication_seq`；前端必须先替换快照，再从该 watermark 续传。

M1 本地 Web API 仍需随机管理员会话 cookie、`HttpOnly`/`SameSite=Strict`、Origin 检查和 CSRF token。
绑定 `127.0.0.1` 只是网络暴露限制，不是认证；gateway/MCP capability token 不能调用管理 API 或下载 raw body。

支持的事件类型（前端关心的）：
```
task.status_changed
session.ready / session.active / session.terminated / session.crashed
exchange.response          ← 每次模型调用结束，含 token 用量
dispatch.dispatched        ← 调用图新增边
inbox.created              ← Inbox 新条目，触发 badge 更新
budget.consumed            ← 预算消耗更新
gateway.degraded           ← 观测等级降级警告
```

---

## Health

### GET /actuator/health
Spring Boot Actuator，M1 必须有。

Response:
```json
{"status":"UP"}
```

未认证探活只返回总体状态；带管理员会话的诊断请求才返回 db/gateway components 和脱敏详情。
