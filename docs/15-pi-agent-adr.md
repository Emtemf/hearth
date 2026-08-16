# ADR: Pi Agent 作为可选 Agent Adapter

## 状态

已决策（2026-08-09）；2026-08-16 补充协议、安全和恢复边界。

## 背景

Hearth 需要支持 Claude Code、Codex、Gemini CLI、opencode 等编码 Agent，并评估是否复用
[Pi Agent](https://github.com/earendil-works/pi) 的运行时能力。本文调研基线为 Pi v0.84.1（MIT），其运行要求为
Node.js `>=22.19.0`。

Pi 是 TypeScript/Node.js 编码 Agent 工具包，提供：

- provider-native 的统一 LLM transport（`@earendil-works/pi-ai`）；
- Agent loop、状态管理和内置 `read/write/edit/bash/grep/find/ls` 工具；
- interactive、print、JSON、RPC 运行模式和可嵌入 SDK；
- LF-delimited JSONL RPC，支持 `prompt`、`steer`、`follow_up`；
- Extension、Skill 和 Prompt Template 扩展机制；
- 原生 JSONL session、分支、fork、resume 和 compaction。

Pi 本身没有 Hearth 所需的完整权限、预算、Evidence、Worker fencing 和多 Agent 编排语义；Extension 以 Pi
进程权限执行，也不是安全边界。Pi 没有内置 MCP、sub-agent、plan mode 或 permission popup，这些能力可以由
Extension 实现，但不能因此把第三方 Extension 视为受信代码。

## 决策

### 1. Pi 不作为 Hearth 的基础运行时

Hearth 核心继续使用 Java 21、Spring Boot、Spring MVC 和虚拟线程。Spring AI 只用于记忆提炼、Embedding、
pgvector 和 Hearth MCP Server，不实现透明网关或通用 Agent loop。

不把 Pi 作为地基，原因是：

1. **控制权倒置**：Task、Session、Invocation、预算、取消、Evidence 和恢复必须由 Hearth 控制面持有；嵌入
   Pi SDK 会把核心执行语义转移到 TypeScript 层。
2. **语言与生命周期边界**：Java 主进程不加载 Pi SDK，也不在同一内存空间维护两套运行时。
3. **上游变化频繁**：Pi 仍在快速迭代；fork 会长期承担合并和安全维护成本。
4. **Provider 职责不同**：Pi 负责生成某种 provider wire request；Hearth 决定允许的 route、模型、凭证和
   观测。两者必须通过明确 Adapter 边界组合，不能各自独立选路由。

### 2. Pi 作为 Worker 上的可选 RPC Adapter

Pi 通过 `WorkerClient` 启动为独立子进程，控制通道使用 `pi --mode rpc`：

```text
Hearth Orchestrator
  │  normalized Worker commands/events
  ▼
WorkerClient → PiRpcAdapter → Pi child process
               │ stdin: LF-delimited RPC commands
               │ stdout: LF-delimited RPC responses/events only
               └ stderr: redacted diagnostics

Pi provider transport → Hearth Gateway session path → wire-compatible upstream
```

Hearth 领域层不 import Pi SDK/type。`PiRpcAdapter` 只负责：

- 构造锁定版本允许的 argv、受限环境和 Worker 管理的 overlay；
- 将 Hearth `commandId` / `invocationId` 与 Pi RPC request ID 稳定关联；
- 解析 Pi RPC 并归一化为 Hearth `EventEnvelope`；
- 声明并执行 `ProcessReusePolicy`、工具治理等级和 `ResumeDescriptor` 校验；
- 在取消时遵守 `processGeneration + launchId` fencing 和 10 秒终止契约。

Pi 不是 M1 依赖。首个 Pi slice 只能在 M2 的 Worker、Profile、Policy 和 Evidence 基础设施完成后进入，且不属于
M2 核心验收的阻塞项。

### 3. Provider 路由仍由 Hearth 控制

Pi 仍使用其 provider transport 生成 Anthropic/OpenAI/Google 等 wire request；“接入 Hearth”不表示移除这层
transport。Hearth 负责把服务端选定的 `providerRouteId` 编译成 Pi 的 provider、model 和 base URL。由于 Pi 没有
通用 `--base-url` CLI 参数，首期实现应在隔离的 Worker-owned `PI_CODING_AGENT_DIR/models.json` 中生成受审配置，
或使用同等受控机制；禁止读取或接受项目/用户全局 `models.json`、交互式 `/login` 或 Agent 自行选择的上游地址。

约束如下：

- 只允许 route 声明的 wire protocol；跨协议转换必须使用独立、经过 contract test 的协议 Adapter。
- Pi 子进程不获得真实上游 Provider secret，只获得 session gateway capability。
- capability 通过 Adapter 验证过的环境变量或权限受限临时文件注入，禁止使用 `--api-key` 等 argv 参数。
- Pi Anthropic transport 若只能把内部 capability 放入 `x-api-key`，Gateway 必须使用服务端绑定的
  `GatewayIngressAuthBinding` 从该 carrier 认证并立即剥离；不能把内部 token 当上游 API key 转发。
- 每个 adapter/protocol 组合只能启用 contract test 验证过的一种内部 credential carrier；出现多个或冲突
  carrier 时 fail closed。Gateway 随后从服务端 credential reference 注入真正的上游凭证。

路径只负责 Session 归因，不替代 capability 认证。

### 4. RPC 是不受信协议边界

Pi stdout 只能承载 RPC。Adapter 按字节 `0x0A` 分帧，移除可选的前置 `0x0D`；不得使用会把 U+2028/U+2029
当换行符的泛用 line reader。每帧先检查大小，再做严格 UTF-8、JSON 和版本化 schema 校验。初始
`maxRpcFrameBytes` 为 8 MiB，超过限制、无效 UTF-8、缺少必需字段、未知 event/command type 或 malformed JSON
都产生明确的 `adapter.protocol_failed` 事件并进入终止/对账流程，不能继续猜测解析。兼容矩阵明确允许的可选未知
字段应原样保存在诊断 payload 中，而不是因通用 JSON 反序列化被静默丢失。

RPC `{"success": true}` 只表示命令已接受或排队，不等于 Invocation 语义完成。Adapter 至少归一化：

```text
Pi command accepted       → invocation.command_accepted
assistant/message end     → assistant.message_observed（不能结束 Invocation）
agent end                 → adapter.run_ended（retry/compaction/queued work 仍可能继续）
agent settled             → semantic_completed（Pi v0.84.1；必须由版本 contract test 固定）
tool call/result          → tool.observed（治理等级另行声明）
usage                     → diagnostic usage；最终计量仍以 Gateway Exchange 为准
malformed/oversized frame → adapter.protocol_failed
stdout EOF                → stream_eof
child exit                → process_exited
pipe break                → transport_disconnected
```

`semantic_completed`、`stream_eof`、`process_exited` 和 `transport_disconnected` 仍是不同信号。stderr 不与 stdout
混流，只保存脱敏摘要；M2 可把需要长期引用的诊断固化为受限 Artifact。

### 5. 默认不信任 Extension、Skill、Template 和项目配置

首个 Pi Adapter 默认：

- 使用 Worker 构造的固定 argv allowlist；调用方不能追加未允许的 flag、`-e`/`--extension`、`--skill`、
  `--prompt-template`、provider 配置、session path 或可执行文件；未知参数直接 preflight 失败；
- 设置 Worker 专属、隔离的 `PI_CODING_AGENT_DIR`，不继承用户的 `~/.pi/agent`、全局 auth/config、packages
  或 resources；必要时同时设置隔离的 `PI_CODING_AGENT_SESSION_DIR`；
- 使用 `--no-extensions`、`--no-skills`、`--no-prompt-templates`、`--no-context-files` 和
  `--no-approve`（或锁定版本中语义等价的非交互 trust 参数）；这些 no-* 开关不能替代对显式 resource path
  的拒绝，参数不存在或语义变化时 preflight 失败；
- 使用 `--offline`（当选定 provider route 和 package policy 允许时），禁止自动安装 package；
- `models.json` 只能由 Worker 在隔离 `PI_CODING_AGENT_DIR` 内生成并校验，provider、protocol、host、model、
  credential carrier、headers 和 base URL 全部来自 Hearth `providerRoute`；Pi 没有可供调用方任意提交的通用
  `--base-url` 参数，不得把 project/global models.json 或配置中的 shell expression（例如 `!` 前缀）带入；
- overlay 由 Worker 写入受限目录，进程退出后按 retention policy 清理。

`--no-extensions`、`--no-skills`、`--no-prompt-templates` 不能阻止显式 `-e`、`--skill` 或
`--prompt-template` 路径，所以“禁用发现”不是完整隔离。固定 argv、隔离 agent directory 和受限环境必须同时存在。

后续 Hearth-owned Extension 必须固定版本和内容 hash、经过代码审查与 contract test，并从 Worker 受信目录只读
加载。即便如此，Extension 仍按任意代码执行处理，必须同时受 OS/container sandbox 或等价 Worker 边界约束。

### 6. 工具可见不等于工具受控

Pi 内置工具在 Pi 进程内执行。RPC 中看见 `tool_call` 只能证明 Hearth 可以审计，不能证明副作用已被阻断。

Pi Adapter 支持的治理模式为：

| 模式 | Pi 配置 | 可声明等级 |
|---|---|---|
| 审计模式 | 受限 built-in tool allowlist；无前置 broker | `OBSERVE_ONLY` |
| 沙箱模式 | built-in tools 仍在进程内执行，但 OS/container policy 限制文件、进程、网络和凭证 | `SANDBOX_ENFORCED` |
| Broker 模式 | 禁用 built-in tools，只加载 Hearth-owned Extension，把工具调用交给 Worker Tool Broker / Hearth MCP | `BROKERED` |

在 Broker 或 sandbox 经过验收前，要求 `HOOK_ENFORCED`、`BROKERED` 或 `SANDBOX_ENFORCED` 的 Profile 必须在
preflight 失败。不得仅凭 Extension 的 `tool_call` 事件声称 `HOOK_ENFORCED`。`OBSERVE_ONLY` 只能运行显式允许的
低风险 Profile，UI 必须显示“仅审计”。

### 7. Hearth 是 Session 真相源，Pi session 只是恢复材料

Hearth Postgres 仍保存 Session/Invocation/Exchange 的权威状态。Pi 原生 JSONL session 可以作为 Adapter 专属恢复
载体，但不能成为另一套控制面数据库：

- 一次性、不可恢复的进程可使用锁定版本验证过的 `--no-session`。
- 需要 resume 时，Pi session 只能写入 Worker 管理的 0700 目录；Worker 必须在启动前建立或检查目录和 session
  carrier 的权限（目标为目录 0700、文件 0600），并在创建/恢复后再次验证；这不是 Pi upstream 默认保证；
- `ResumeDescriptor` 至少保存 `adapterType=pi-rpc`、descriptor schema version、Pi CLI version、
  external session ID、Worker 内相对 carrier、内容 hash 和 `lastVerifiedAt`。
- LAUNCH 前重新验证 descriptor、版本、路径边界和 hash；不兼容时明确失败，不把裸字符串传给 Pi。
- 中心恢复成功与否以 Worker 归一化事件和 Postgres 状态为准，不以 Pi session 文件存在为准。

## 明确不做

| 不做 | 原因 |
|---|---|
| 不 fork Pi | 上游活跃，分叉维护和安全修补成本高 |
| 不把 Pi SDK/Agent Runtime 嵌入 Hearth Java 核心 | 控制权倒置，形成双运行时 |
| 不把 Pi 作为唯一 Adapter | Hearth 的价值是统一管理多种 Agent CLI |
| 不让 Pi 自行选择 provider/model/credential | 绕过 Hearth route、预算和凭证边界 |
| 不把 capability token 放进 argv | argv 可被同机进程观察 |
| 不把 project-local Extension/Skill 当受信代码 | 它们拥有 Pi 进程权限，可绕过 prompt 约束 |
| 不因观察到 tool call 就宣称权限已执行 | 观察与前置阻断是不同保证 |
| 不为 Pi 新建核心领域表 | 使用既有 Session、Invocation、Exchange 和 ResumeDescriptor |

## 对现有设计的影响

- **控制面/领域模型不变**：Pi 只新增 Adapter 实现和兼容性矩阵。
- **Gateway 增加内部认证 carrier 绑定**：由服务端按 adapter/protocol 选择，认证后统一剥离，再注入上游凭证。
- **Worker preflight 增加 Pi 版本、RPC、trust flags、治理等级和 resume 校验**。
- **Profile preflight 依赖真实治理等级**：默认 Pi 为 `OBSERVE_ONLY`，不能冒充 broker/hook。
- **数据库不新增 Pi 专属表**：版本和恢复信息进入既有 Worker capability / ResumeDescriptor。
- **路线图不变**：M1 仍只验证 Claude Code；Pi 是 M2 基础设施完成后的可选兼容性 slice。

## 版本和升级策略

- 调研/首个 contract-test 基线：Pi v0.84.1，Node.js `>=22.19.0`，MIT。
- Pi 不是 Maven 依赖，也不是 Hearth 前端 npm 依赖；它是 Worker 上的外部 runtime capability。
- Worker 注册时上报 Pi 精确版本和可执行文件 hash；不在兼容矩阵内时不宣称 `pi-rpc` capability。
- 升级前重跑固定 argv/resource isolation、RPC framing/schema、`agent_settled` completion、provider carrier、tool
  governance、resume 和 cancel contract tests。未通过时保留旧版本或禁用该 capability，不以“能启动”代替兼容性验证。

## 验证标准

Pi compatibility slice 必须提供可寻址 Evidence：

1. Worker 通过固定 executable、隔离 `PI_CODING_AGENT_DIR` 和版本启动 Pi RPC，完成至少两次 Invocation；命令接受、
   `agent_settled` 语义完成、EOF 和进程退出映射正确，`message_end`/`agent_end` 不会提前结束 Invocation。
2. Pi 的真实 LLM 请求经过 Hearth Gateway；内部 capability 未转发，上游只收到服务端 credential；Gateway 正确
   录制 system prompt 和模型 token usage。
3. malformed JSON、U+2028/U+2029、CRLF、超大帧、stdout EOF 和 stderr 噪声 fixture 均按契约处理，不发生
   frame 混淆或无限缓冲。
4. 默认配置不能加载项目或用户全局 Extension/Skill/Template、AGENTS/CLAUDE context、任意 provider 配置或任意
   session path；孤立 `PI_CODING_AGENT_DIR` 内的 Worker-owned `models.json` 才能生效；敏感值不出现在 argv、日志、
   Artifact 和数据库明文中，resource path 和 shell expression fixture 必须被拒绝。
5. `OBSERVE_ONLY` Profile 只能事后审计；要求前置阻断的 Profile 被拒绝。若验收 sandbox 或 Broker 模式，使用
   真实越界读写、进程和网络 fixture 证明策略 fail closed。
6. Worker ONLINE 且控制通道可达时，精确 generation 的取消在 10 秒内终止 Pi 进程；旧 generation 的迟到事件
   不改变当前 Session 状态。
7. 中心和 Worker 重启后，合法 `ResumeDescriptor` 可以恢复；篡改 hash、越界路径或不兼容 Pi 版本必须明确拒绝。
8. Pi runtime 升级只有在全部 contract tests 通过并更新兼容矩阵后才可启用。
