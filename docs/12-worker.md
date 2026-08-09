# 蜂窝架构：Worker 节点设计

---

## 核心思路

Hearth 运行在中心节点（任意一台机器），Worker 守护进程运行在每台有 agent CLI 的机器上。
Worker 主动连中心，不是中心主动连 Worker——这样 Worker 可以在 NAT/防火墙后面，不需要公网 IP。

```
┌─────────────────────────────────────────────┐
│   Hearth 中心                               │
│   - 编排、网关（M1 127.0.0.1:4517）、数据库、UI │
└──────────────────┬──────────────────────────┘
                   │ WebSocket（Worker 主动连入）
       ┌───────────┼───────────┐
       ▼           ▼           ▼
  Worker A     Worker B     Worker C
  笔记本        台式机        服务器
  claude-code   codex        gemini
  │             │             │
  agent 进程   agent 进程    agent 进程
  MODEL_API_BASE_URL=https://hearth.internal/s/{sid}/{protocol}
```

---

## WorkerClient 接口（M1 就要定好）

**这是最关键的设计决策**：控制面通过这个接口操作 agent，不直接用 `ProcessBuilder`。
M1 只有本地实现，M2 加远程实现，核心代码不动。

```java
public interface WorkerClient {

    /**
     * 在目标 Worker 上启动一个 agent session。
     * 返回 handle，用于后续操作和事件订阅。
     */
    AgentHandle launch(LaunchSpec spec);

    /**
     * 取消指定 session。必须一定生效：先 SIGTERM，8s 后 SIGKILL，
     * 最迟 10s 确认进程死亡。
     * 幂等：session 已终止时调用不报错。
     */
    void cancel(String sessionId);

    /**
     * 检查 session 对应的进程是否还活着。
     */
    boolean isAlive(String sessionId);

    /**
     * 订阅 session 的事件流（归一化的 EventEnvelope ndjson）。
     * 使用 JDK 9+ 的 java.util.concurrent.Flow.Publisher，
     * 不依赖任何具体的响应式框架（Reactor/RxJava 等），
     * 保持 hearth-core 对框架无依赖。
     * LocalWorkerClient 使用虚拟线程 + BlockingQueue；
     * RemoteWorkerClient 使用 JDK WebSocket Client、Jakarta WebSocket
     * 或 Spring MVC WebSocket。任何实现都不得引入 Reactor/WebFlux。
     */
    java.util.concurrent.Flow.Publisher<EventEnvelope> streamEvents(
        String sessionId,
        long processGeneration,
        long afterEventSeq
    );

    /** Worker 的标识和能力信息。 */
    WorkerInfo info();
}
```

```java
public record WorkerInfo(
    String workerId,
    String version,
    Set<String> capabilities,  // "claude-code", "codex", "gemini", "opencode"
    String hostname,
    WorkerStatus status        // ONLINE, OFFLINE, DRAINING
) {}
```

**本地实现**：`LocalWorkerClient` 用 `ProcessBuilder` 直接启动进程。
**远程实现**：`RemoteWorkerClient` 通过 WebSocket 发指令给远端 Worker 守护进程。

---

## Worker 守护进程（hearth-worker）

轻量，职责极简：

```
启动时：
  1. 读本地受限权限的 secret 配置，获取中心地址和 Worker opaque token
  2. 建立 TLS WebSocket，中心按 `worker_credential` 的 SHA-256 hash 认证后注册：
     {workerId, version, capabilities, hostname}
  3. 等待指令

运行时：
  收到 LAUNCH 指令 → 校验 providerRoute/workspaceRoot → 写 session overlay 文件 → 设环境变量 → spawn agent 进程
  收到 CANCEL 指令 → 仅当 processGeneration 等于本地当前代次才执行
                    → SIGTERM → 8s → SIGKILL → 最迟 10s 回执
  每代进程事件使用从 1 单调递增的 eventSeq；未确认事件持久化到 Worker 本地受限目录
  每 30s 发心跳：{workerId, aliveProcesses: [{sessionId, processGeneration, lastEventSeq}]}

断线时：
  指数退避重连（base=5s，max=60s）
  本地 agent 进程继续运行，不因断线停止
  重连后立刻上报本地存活进程与最后事件序号；中心返回 ack 水位，Worker 重放更大的 eventSeq
```

---

## 状态对账（防裂脑）

Worker 和中心之间 WebSocket 断了，但 Worker 上的 agent 进程还在跑。
重连时必须对账，不能假装断线期间什么都没发生。

```
Worker 重连后立刻发：
  {
    type: "reconcile",
    aliveProcesses: [
      {sessionId: "sid-A", processGeneration: 3, lastEventSeq: 184}
    ]
  }

中心处理：
  foreach 中心认为 READY/ACTIVE/LAUNCHING 的进程代次：
    if 同一 `(sessionId, processGeneration)` 存活 → 返回已确认 eventSeq，接收重放并继续
    if session 存活但 generation 不同 → 拒绝旧代事件，按 fencing 规则对账
    if 目标代次不在 Worker → 中心标记该代进程已死，补齐非终态 Invocation

  foreach Worker 上报的存活进程：
    if 中心认为同代有效 → 无操作
    if 中心认为 CANCELLED/FAILED → 发精确 generation 的 CANCEL（补偿）
    if 中心不认识 sessionId/generation → 发 CANCEL（孤儿进程）

Worker 事件统一携带 `{workerId, connectionId, sessionId, processGeneration, eventSeq, eventType, payload}`。
中心先按 `(sessionId, processGeneration, eventSeq)` 幂等落水位，再把业务事件写为全局单调
`domain_event.event_id`。语义完成、stream EOF、process exit、transport disconnect 使用不同 eventType。
```

---

## Artifact 传输

agent 在 Worker 本地产出文件（代码 diff、测试报告），需要传到中心 Artifact 存储。

传输路径：
```
agent 调用 hearth MCP tool: hearth_save_artifact(type, content/path)
  → Worker 端 MCP server 接收
  → Worker 通过 WebSocket 流式上传到中心
  → 中心存入对象存储，返回 artifactId
  → MCP tool 把 artifactId 返回给 agent
```

大文件（>1MB）用分块上传，小文件直接内嵌在 WebSocket 消息里。
上传失败重试 3 次，超限则 Artifact 标记 `UPLOAD_FAILED`，对应 Checkpoint 无法通过。

---

## 任务派发策略

**M1/M2**：手动指定 + 能力标签匹配
- Task 创建时可指定 `requiredCapabilities: ["claude-code"]`
- 编排层找在线的、有对应能力的 Worker，随机选一个
- 无可用 Worker → Task 进 `PENDING`，等 Worker 上线后自动触发

**推迟**：负载均衡（按 CPU/内存）、地理亲和性，开源后再加。

---

## 网关安全：Worker capability token 认证

单机时网关绑 `127.0.0.1`，蜂窝模式下必须验证来源。

**⚠️ 不能用可推导字符串作为 token**。`jg-{workerId}-{sessionId}` 这类格式一旦 sessionId 通过 UI/日志/URL 泄露，任何人都能重建 token。

**正确方案：opaque random capability token**

```
1. 每个 session 启动时生成两枚独立的 256-bit CSPRNG token：gateway 和 MCP 各一枚
2. token 是无结构的 opaque 字节串（Base64URL 编码），不携带、签名或暴露元数据
3. 数据库只存 SHA-256(raw token)；行字段存 workerId、sessionId、audience、expiresAt、revokedAt
4. 明文只在创建时返回一次，并通过 Worker 的已认证 WebSocket 下发
5. Gateway token 与 MCP token 不可互换；过期时间 = session 最大运行时间 + 5min
6. session 终止、被替换或 Worker 被撤销时立即设置 revokedAt
```

这里使用 SHA-256 而不是 bcrypt：capability token 具有完整 256-bit 随机熵，不面临低熵密码的
离线字典攻击；网关每次请求需要常数时间索引查询，慢哈希反而会放大延迟和 DoS 面。

网关收到请求时：
1. 从 URL 路径提取 `sessionId`
2. 从 `Authorization: Bearer <gatewayCapabilityToken>` 取出内部 token
3. 计算 SHA-256 并查询 `session_credential`
4. 验证 audience=gateway、sessionId/workerId 绑定、未过期且未撤销
5. 不匹配 → 403，记录不含 token 的安全告警

**关键安全步骤：协议专属上游凭证替换**

内部 capability token 仅用于 Hearth 认证，绝不能原样转发。网关通过
`CredentialRewriter` 按 `wire_protocol` 执行：

| 协议 | 先移除 | 注入上游凭证 | 必须保留/验证 |
|---|---|---|---|
| Anthropic Messages（静态 API key） | `Authorization`、任何客户端 `x-api-key` | `x-api-key: {ANTHROPIC_API_KEY}` | `anthropic-version`，允许配置的 `anthropic-beta` |
| Anthropic 短期身份 token | Hearth `Authorization` | `Authorization: Bearer {identityToken}` | token 类型必须由 credential 配置显式声明 |
| OpenAI | Hearth `Authorization`、客户端 API key | `Authorization: Bearer {OPENAI_API_KEY}` | `Content-Type` 与允许的组织/项目头 |
| Gemini | Hearth `Authorization`、客户端 key/query | provider 配置指定的 header 或 query key | API 版本与目标 host 白名单 |

处理顺序固定：认证 Hearth token → 删除所有内部/客户端 provider credential → 从服务端 secret
引用加载目标凭证 → 注入协议要求的 header/query → 校验目标 host → 转发。日志、Artifact、exchange
和错误消息都不得包含内部 token 或上游 secret。上游 secret 不下发给 agent，也不放进 argv。

Provider 差异必须由独立实现承载（如 `AnthropicCredentialRewriter`、
`OpenAiCredentialRewriter`、`GeminiCredentialRewriter`），禁止一个“统一 Bearer”分支猜测协议。

Worker 长连接 token 与 session capability token 是两套 credential：前者只认证 Worker WebSocket，
后者只认证具体 session 的 gateway/MCP。均为 256-bit opaque token、数据库只存 SHA-256，
但 audience、生命周期和撤销条件不得混用。

---

## Worker 版本管理

Worker 注册时必须带版本号：
```json
{ "workerId": "worker-A", "version": "0.2.1", "capabilities": ["claude-code"] }
```

版本兼容规则（语义化版本）：
- patch 版本差异：允许，记录日志
- minor 版本差异：允许，记录警告，提示升级
- major 版本差异：拒绝注册，Worker 必须升级后才能接入

M1/M2 阶段只有你自己用，只记录不拒绝，但版本字段从第一天就要有。

---

## 部署拓扑选项

**选项 A：同一局域网 + Tailscale/受信 TLS（M2 推荐）**
- M1 中心只绑 loopback；M2 多机通过 Tailscale 私网地址或受信 TLS endpoint 连接
- 禁止在普通局域网用明文 HTTP/WebSocket 传 capability token、prompt、Artifact 或模型响应

**选项 B：跨地点（M3 推荐方案）**
- 用 Tailscale 把所有机器组成虚拟局域网
- 中心和 Worker 仍验证 TLS/peer identity 与 capability token；Tailscale 只提供网络层，不替代应用认证
- 不需要公网 IP，不需要端口转发

**选项 C：公网（开源后）**
- 中心需要 HTTPS + 更严格的 Worker 认证
- 不在当前路线图范围内

---

## 命令与路径安全（防注入和目录逃逸）

Session 创建只接受 `providerRouteId`、`workspaceRootId` 和 `relativeCwd`。中心与 Worker 都必须：

1. 从受信配置解析 provider route，禁止 LAUNCH 携带任意 upstream URL 或 secret。
2. 将 `workspaceRoot.canonicalPath.resolve(relativeCwd).normalize()` 后重新解析真实路径。
3. 拒绝绝对 `relativeCwd`、`..`、符号链接逃逸和 allowed root 之外路径。
4. CLI 可执行文件由 Worker capability 配置固定，调用方不能传任意 executable。
5. 使用 `ProcessBuilder(List<String>)`，不经过 shell 拼接；每家 CLI 的 argv 由独立 Adapter 构造并做 contract test。

```java
Path resolveAllowedCwd(Path configuredRoot, String relativeCwd) {
    Path root = configuredRoot.toRealPath();
    Path candidate = root.resolve(relativeCwd).normalize().toRealPath();
    if (!candidate.startsWith(root)) {
        throw new DomainException("worker.cwd_outside_allowed_root");
    }
    return candidate;
}
```

`--` 是否可用及参数顺序由具体 CLI Adapter 定义，不能假设所有 CLI 都支持同一个通用 argv 模板。
禁止把 prompt 交给 `sh -c` 或拼接成单一命令字符串。

**禁止**：把 API key、session token 等敏感内容拼进 argv。只走环境变量或权限受限的临时文件，
并在进程退出后删除临时文件。环境变量仍属于同用户进程可读的敏感信息，不能视为 secret manager。

---

## 三类 Timeout 要分开追踪

agent 进程启动后有两个独立的超时：

| Timeout | 定义 | 默认值 | 超时后 |
|---|---|---|---|
| `firstEventTimeout` | Invocation 启动后多久没有第一条事件 | 30s | Invocation → FAILED，可能是 CLI 认证失败或配置错误 |
| `idleTimeout` | Invocation 运行中多久没有新事件 | 5min | Invocation → TIMED_OUT；按策略终止或保留 Session 进程 |
| `totalTimeout` | 指定进程代次/Task 的整体运行上限 | 60min（可配） | 非终态 Invocation → TIMED_OUT，进程进入终止流程 |

`firstEventTimeout` 超时 = invocation 根本没有启动成功，错误信息要去已脱敏 stderr 诊断 Artifact 里找。
`idleTimeout` 超时 = invocation 启动了但卡在某步，通常是工具调用没返回。
收到 adapter 的语义完成事件后立即完成 Invocation；不得继续等待 stdout EOF。CLI 进程是否保留复用由
Session 策略单独决定。取消/异常路径必须在 finally 中关闭 event publisher 并完成所有等待 future。

### Provider/CLI preflight

LAUNCH 前由中心和 Worker 分层验证：provider route 已启用、model 在白名单且与 wire protocol 兼容、
credential reference 当前可解析、CLI/Adapter 版本兼容、Worker capability 匹配、cwd 在 allowed root 内、
ResumeDescriptor 可由该 Adapter 解析。401/403、未知模型、无效 resume 和配置错误属于永久错误，不交给
CLI 无限重试；stderr 只保存脱敏摘要，且 CLI 内部重试不能突破 Hearth total deadline。



M1 只在本机运行，但必须做这一件事：

**用 `WorkerClient` 接口包装所有 agent 启动逻辑，不直接调 `ProcessBuilder`。**

```java
// M1 的实现，行为和直接用 ProcessBuilder 完全一样
// 但 M2 只需要加 RemoteWorkerClient，不改任何调用方代码
WorkerClient worker = new LocalWorkerClient();
AgentHandle handle = worker.launch(spec);
```

这是 M1 最重要的一个"为未来预留"的设计决策，其他都可以推迟。
