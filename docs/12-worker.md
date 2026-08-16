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
     * 启动一个精确进程代次。相同 commandId 重试必须返回同一结果，不能重复 spawn。
     */
    WorkerCommandResult launch(LaunchCommand command);

    /**
     * 向已经存活的 Session 进程发起一次 Invocation（首轮 prompt 或后续 continue）。
     * 同一 Session 默认只允许一个非终态 Invocation。
     */
    WorkerCommandResult invoke(InvokeCommand command);

    /**
     * 取消精确进程代次。只匹配 sessionId/processGeneration/launchId 时才发送信号；
     * 已终止时幂等返回 COMMITTED，身份不匹配时返回 REJECTED_BEFORE_COMMIT。
     */
    WorkerCommandResult cancel(CancelCommand command);

    /** 查询精确进程身份，不能只按 sessionId 或 PID 判断。 */
    ProcessSnapshot inspect(ProcessIdentity identity);

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
        ProcessIdentity identity, long afterEventSeq);

    /** Worker 的标识和能力信息。 */
    WorkerInfo info();
}
```

```java
public record ProcessIdentity(
    UUID sessionId,
    long processGeneration,
    UUID launchId
) {}

public record LaunchCommand(
    UUID commandId,
    ProcessIdentity process,
    LaunchSpec spec
) {}

public record InvokeCommand(
    UUID commandId,
    UUID invocationId,
    int attempt,
    ProcessIdentity process,
    String content
) {}

public record CancelCommand(
    UUID commandId,
    ProcessIdentity process,
    Long cancellationVersion // Task cancel 时必填；standalone Invocation cancel 可空
) {}

public record WorkerCommandResult(
    UUID commandId,
    CommitState commitState,
    String resultCode,
    ProcessSnapshot process
) {}

public enum CommitState {
    COMMITTED,
    COMMITTED_WITH_WARNING,
    REJECTED_BEFORE_COMMIT,
    UNKNOWN_COMMIT_STATE
}
```

`launch`、`invoke`、`cancel` 都是**命令**而不是普通 RPC：调用前由中心持久化 claim，网络重试复用
同一 `commandId`。Worker 本地也要持久化最近命令结果；收到重复命令时返回原结果。只有
`REJECTED_BEFORE_COMMIT` 可以自动换新 command；`UNKNOWN_COMMIT_STATE` 必须先 reconcile，禁止猜测后重放。
每个可能产生副作用的命令还必须携带中心计算并持久化的 `deadlineAt` 快照；中心和 Worker 在 claim、backoff、
`Retry-After`、reconcile 前检查 deadline，过期后只能返回 `REJECTED_BEFORE_COMMIT`，不能因重连延长时间。
`AgentHandle` 可以作为 `LocalWorkerClient` 内部实现细节，但不得作为跨本机/远机的领域契约。

```java
public record WorkerInfo(
    String workerId,
    String version,
    Set<String> capabilities,  // "claude-code", "codex", "gemini", "opencode", "pi-rpc"
    Map<String, String> adapterVersions, // exact CLI/runtime version per capability
    Map<String, ToolGovernanceLevel> adapterGovernance,
    String hostname,
    WorkerStatus status        // ONLINE, OFFLINE, DRAINING
) {}
```

**本地实现**：`LocalWorkerClient` 通过本机受认证 transport 调用 `hearth-worker`，不在 Hearth API 进程内使用
`ProcessBuilder`。
**远程实现**：`RemoteWorkerClient` 通过 WebSocket/TLS 发指令给远端 Worker 守护进程。

只有 `hearth-worker` 可以使用 `ProcessBuilder`。它负责固定 executable、argv、validated cwd、child environment、
Adapter、事件和终止；Agent 以低权限 `hearth-agent` 身份运行。`hearth-api` 与 Worker/Agent 使用不同服务身份，
Agent 不得读取 API 的 provider/DB/IM/admin secret 或 Worker control credential。

每家 CLI 由独立 Adapter 实现 `launch/invoke/event normalization/tool governance/resume validation`。

### Pi RPC Adapter（M2 核心验收后的可选 slice）

`PiRpcAdapter` 以独立子进程运行锁定版本的 `pi --mode rpc`；Hearth 领域层不加载 Pi SDK/type。控制面和模型
数据面保持分离：stdin/stdout 只承载 RPC，Pi provider transport 的 LLM 请求则通过 Session base URL 进入
Gateway。

- **Framing**：只按字节 `0x0A` 分帧并移除可选的 `0x0D`，不把 U+2028/U+2029 当行结束；默认
  `maxRpcFrameBytes=8 MiB`，之后才做严格 UTF-8、JSON 和版本化 schema 校验。
- **状态映射**：RPC command `success=true` 只映射为 command accepted；`message_end` 只上报消息观察，`agent_end`
  只上报一次低层 run 结束；Pi v0.84.1 的 `agent_settled` 才能映射为 `semantic_completed`，并由版本 contract
  test 固定。stdout EOF、process exit 和 transport disconnect 分别上报。Gateway Exchange 是 token/成本的可信计量点。
- **stdout/stderr**：stdout 不允许混入日志；malformed/oversized frame 触发 `adapter.protocol_failed`。stderr 单独
  采集脱敏摘要，不能把 token、prompt 或任意原始环境写入日志。
- **Trust 默认值**：使用 Worker 构造的固定 argv allowlist；禁用 project/global Extension、Skill、Prompt Template、
  AGENTS/CLAUDE context 和 package 自动安装。设置 Worker-owned、隔离的 `PI_CODING_AGENT_DIR`（必要时
  `PI_CODING_AGENT_SESSION_DIR`），不继承用户普通 Pi 配置；调用方不能传 `-e`、`--skill`、
  `--prompt-template`、provider config、session path、executable 或未知 flag。启动参数必须包含 contract test
  验证过的 `--no-extensions`、`--no-skills`、`--no-prompt-templates`、`--no-context-files` 和
  `--no-approve`（或版本等价项）；这些 `--no-*` 只禁用发现，不能替代对显式 resource path 的拒绝。
- **Provider config**：Pi 没有通用 `--base-url` CLI 参数。Worker 在隔离 agent directory 生成受审 `models.json`
  或使用同等受控机制，provider/protocol/host/model/credential carrier/headers/base URL 全部来自 Hearth route；
  不接受 project/global models.json 或配置中的 shell expression。`--offline` 在 route/package policy 允许时启用。
- **工具治理**：Pi built-in tools 在子进程内执行，能观察 event 不代表能前置阻断。默认只能声明
  `OBSERVE_ONLY`；OS/container policy 通过验收后可声明 `SANDBOX_ENFORCED`；禁用 built-in tools 并只加载固定
  hash 的 Hearth-owned Broker Extension 后才可声明 `BROKERED`。Extension 仍按任意代码执行对待。
- **凭证**：Pi 仍使用 provider transport 生成 wire request，但 provider/model/base URL 由服务端 route 编译；
  子进程只拿 session gateway capability，不拿上游 secret。capability 只走经 contract test 验证的环境变量或
  0600 临时文件，禁止 `--api-key` 等 argv carrier。
- **恢复**：ephemeral 进程可使用锁定版本验证过的 `--no-session`；可恢复进程只写 Worker 管理的目录。Worker
  在启动前创建/检查 0700 目录和 0600 session carrier，并在 Pi 创建或恢复后重新验证；这些权限不是 Pi upstream
  默认保证。`ResumeDescriptor` 保存 adapter/schema/Pi 版本、external session ID、相对 carrier、hash 和
  `lastVerifiedAt`，不保存调用方提供的绝对路径。

完整决策和验收见 `docs/15-pi-agent-adr.md`。

Adapter 同时声明 `ProcessReusePolicy`：`STREAMING_STDIN` 表示 result 后可继续向同代进程写下一条 Invocation；
`RESUME_PER_INVOCATION` 表示每次 Invocation 使用新 process generation，并由 Adapter 生成/验证
ResumeDescriptor。调用方只依赖策略，不得因一次 CLI 实测可复用就把所有 Adapter 写死为长驻。

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
  收到 LAUNCH 指令 → 校验 providerRoute/workspaceRoot/Profile 要求与 Adapter 工具治理能力
                    → 写 session overlay 文件 → 用进程 API 设置 validated cwd 和环境变量 → spawn agent 进程
  收到 CANCEL 指令 → 仅当 processGeneration 等于本地当前代次才执行
                    → 控制通道可达时 SIGTERM → 8s → SIGKILL → 最迟 10s 回执
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
      {
        "sessionId": "sid-A",
        "processGeneration": 3,
        "launchId": "launch-A",
        "lastEventSeq": 184
      }
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

Worker 事件统一携带 `{workerId, connectionId, sessionId, processGeneration, launchId, eventSeq,
invocationId, eventType, payload}`。`invocationId` 对进程级事件可空，对 Invocation/Exchange 相关事件必填。

中心处理一条事件时必须在**同一数据库事务**中按固定顺序执行：锁定 `worker`，断言 envelope 的 `workerId` 与当前
`connectionId` 相等；锁定 `session_process`，断言 `sessionId + processGeneration + launchId` 精确匹配且 worker
一致；锁定 `worker_event_receipt`，断言 `eventSeq == last_event_seq + 1`；应用状态转换、插入 `domain_event`、
推进 receipt 水位；提交成功后才向 Worker ACK。旧 connection、旧 launch、旧 generation、重复或乱序事件全部
拒绝且不推进水位。禁止先推进水位再写业务事件，否则中心在两步之间崩溃会永久丢失已经确认的事件。语义完成、
stream EOF、process exit、transport disconnect 使用不同 eventType。重连 reconcile 必须携带 launchId。
```

---

## Artifact 传输

agent 在 Worker 本地产出文件（代码 diff、测试报告），需要传到中心 Artifact 存储。

传输路径：
```
agent 调用中心 Hearth MCP tool: hearth_save_artifact(type, content)
  → 中心 hearth-platform-mcp 验证 session capability 与大小
  → 中心存入对象存储，返回 artifactId
  → MCP tool 把 artifactId 返回给 agent
```

M2 首期的 MCP 只接受内容，不接受 Worker 文件 path。未来增加 path/大文件上传时，Worker 提供受限的
`ArtifactUploadRelay`（它不是第二个 MCP Server）：对 Session cwd 内相对路径做 real-path 校验，再通过带
chunk hash、总 sha256 和幂等 finalize 的协议流式上传中心。上传失败重试 3 次，超限则 Artifact 标记
`UPLOAD_FAILED`，依赖该材料的 Verification 无法通过。

---

## 任务派发策略

**M2 Task 编排**：手动指定 + 能力标签匹配（M1 standalone Session 由用户显式选本机 Worker）
- Task 创建时可指定 `requiredCapabilities: ["claude-code"]`
- 编排层找在线的、有对应能力的 Worker，随机选一个
- 无可用 Worker → Task 保持 `READY` 并记录 scheduling wait reason，等 Worker 上线后用同一 commandId 触发

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
1. 从 URL 路径提取 `sessionId`，加载服务端生成的 `GatewayIngressAuthBinding`
2. 只从该 Session 的预期 carrier 提取内部 capability；M1 Claude Code/Anthropic 使用
   `Authorization: Bearer`，Pi Anthropic transport 可在 contract test 通过后绑定 `x-api-key`
3. 缺失、重复或同时出现冲突 credential carrier 时 fail closed；不能在多个 header 间猜 token
4. 计算 SHA-256 并查询 `session_credential`，验证 audience=gateway、sessionId/workerId 绑定、未过期且未撤销
5. 不匹配 → 403，记录不含 token 的安全告警；匹配后立即移除所有内部/客户端 credential

`GatewayIngressAuthBinding` 只能由服务端按 adapter + wire protocol 选择，不能由 Session API、Agent 或 Worker
自由提交。路径只负责归因，不是认证；允许 Pi 使用 provider-native carrier 也不代表该值可以转发上游。

**关键安全步骤：协议专属上游凭证替换**

内部 capability token 仅用于 Hearth 认证，绝不能原样转发。网关通过
`CredentialRewriter` 按 `wire_protocol` 执行：

| 协议 | 先移除 | 注入上游凭证 | 必须保留/验证 |
|---|---|---|---|
| Anthropic Messages（静态 API key） | 绑定的 Hearth auth carrier、任何客户端 `Authorization`/`x-api-key` | `x-api-key: {ANTHROPIC_API_KEY}` | `anthropic-version`，允许配置的 `anthropic-beta` |
| Anthropic 短期身份 token | 绑定的 Hearth auth carrier、任何客户端 provider credential | `Authorization: Bearer {identityToken}` | token 类型必须由 credential 配置显式声明 |
| OpenAI | 绑定的 Hearth auth carrier、客户端 API key | `Authorization: Bearer {OPENAI_API_KEY}` | `Content-Type` 与允许的组织/项目头 |
| Gemini | 绑定的 Hearth auth carrier、客户端 key/query | provider 配置指定的 header 或 query key | API 版本与目标 host 白名单 |

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

Worker 注册时必须带 Worker 版本，以及每个外部 Adapter/runtime 的精确版本：
```json
{
  "workerId": "worker-A",
  "version": "0.2.1",
  "capabilities": ["claude-code", "pi-rpc"],
  "adapterVersions": {"claude-code": "2.1.226", "pi-rpc": "0.84.1"}
}
```

外部 runtime 还要在 Worker 本地 capability 配置中绑定 canonical executable path 和文件 hash。注册信息可以
只暴露版本，不暴露本机绝对路径；中心根据兼容矩阵决定是否接受 capability。Pi 基线和升级 gate 见
`docs/10-dependencies.md` 与 `docs/15-pi-agent-adr.md`。

以下语义化版本规则只适用于 Hearth Worker 守护进程协议；外部 Adapter/runtime 不按它自动放行，必须命中各自的
compatibility matrix：
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

`LocalWorkerClient` 不能直接继承 Hearth 中心进程的全部环境。构造 child environment 时先移除所有已知
provider/IM/数据库/管理员凭证（至少 `ANTHROPIC_API_KEY`、数据库密码、Feishu/Telegram token），再按
Adapter allowlist 复制必要的 PATH/locale/terminal 变量，并只注入 session capability token。具体变量名/临时文件
由服务端 `GatewayIngressAuthBinding` 与锁定 Adapter 版本决定：例如 Claude Code 可使用 auth-token carrier，Pi 的
provider transport 可使用其原生 API-key 环境变量承载**内部** capability；两者到达 Gateway 后都先认证并剥离，
不能原样发给 Provider。M1 contract test
必须让测试 CLI 打印**环境变量名列表和敏感值 hash 扫描结果**，证明真实上游 key 不在 child environment；
测试输出本身也不得打印 secret。未 sandbox 的同用户进程仍可能读取宿主其他进程信息，因此这是最小隔离，
不是多用户安全边界。

---

## 三类 Timeout 要分开追踪

agent 进程启动后有两个独立的超时：

| Timeout | 定义 | 默认值 | 超时后 |
|---|---|---|---|
| `firstEventTimeout` | Invocation 启动后多久没有第一条事件 | 30s | Invocation → FAILED，可能是 CLI 认证失败或配置错误 |
| `idleTimeout` | Invocation 运行中多久没有新事件 | 5min | Invocation → TIMED_OUT；按策略终止或保留 Session 进程 |
| `totalTimeout` | 指定进程代次/Task 的整体运行上限 | 60min（可配） | 非终态 Invocation → TIMED_OUT，进程进入终止流程 |

`firstEventTimeout` 超时 = invocation 根本没有启动成功。M1 把已脱敏 stderr 摘要写入受限诊断存储并记录
`invocation.diagnostic_ref`；M2 可进一步把需要长期引用的诊断固化为 Artifact。
`idleTimeout` 超时 = invocation 启动了但卡在某步，通常是工具调用没返回。
收到 adapter 的语义完成事件后立即完成 Invocation；不得继续等待 stdout EOF。CLI 进程是否保留复用由
Session 策略单独决定。取消/异常路径必须在 finally 中关闭 event publisher 并完成所有等待 future。

### Provider/CLI preflight

LAUNCH 前由中心和 Worker 分层验证：provider route 已启用、model 在白名单且与 wire protocol 兼容、
credential reference 当前可解析、Gateway ingress auth carrier 已由该 Adapter 的 contract test 覆盖、CLI/Adapter
版本和 executable hash 在兼容矩阵内、Worker capability 匹配、cwd 在 allowed root 内、ResumeDescriptor 可由该
Adapter 解析，以及 Profile 要求的工具治理等级可满足。Pi 等可扩展 runtime 还必须验证使用隔离的 Worker-owned
agent/session directories，project/global Extension/Skill/Template/context 已禁用，显式 resource path 与未知 flag
被拒绝，或只加载受信 hash 且具备要求的 sandbox/Broker 边界。401/403、未知模型、
无效 resume、无法执行权限策略、未知 runtime 版本和配置错误属于永久错误，不交给 CLI 无限重试；stderr 只保存
脱敏摘要，且 CLI 内部重试不能突破 Hearth total deadline。



M1 只在本机运行，但必须做这一件事：

**用 `WorkerClient` 接口包装所有 agent 启动逻辑，不直接调 `ProcessBuilder`。**

```java
// M1 的实现，行为和直接用 ProcessBuilder 完全一样
// 但 M2 只需要加 RemoteWorkerClient，不改任何调用方代码
WorkerClient worker = new LocalWorkerClient();
WorkerCommandResult result = worker.launch(command);
```

这是 M1 最重要的一个"为未来预留"的设计决策，其他都可以推迟。
