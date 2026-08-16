# 架构：控制面与数据面分离

## 核心判断

绝大多数同类项目（含 clowder-ai）把「驱动 CLI」和「观测提示词」当成一件事做，导致两者互相拖累：
想要完整观测就必须接管进程，想接管进程就必须解析各家易变的 stdout 格式。

Hearth 把它们拆成两个**正交平面**，各自独立演进、独立失败。

```
                    ┌──────────────────────────────────────┐
                    │           Orchestrator               │
                    │ Task/Spec/Plan / Dispatch / Budget   │
                    └───────┬──────────────────┬───────────┘
                            │                  │
             控制面 Control │                  │ 数据面 Data
                            ▼                  ▼
                ┌───────────────────┐   ┌─────────────────────┐
                │  Agent Adapters   │   │   Gateway (proxy)   │
                │  spawn / send /   │   │  多协议兼容反向代理  │
                │  normalize events │   │  观测 / 路由 / 治理  │
                └─────────┬─────────┘   └──────────┬──────────┘
                          │                        │
                          ▼                        ▼
                ┌───────────────────┐   ┌─────────────────────┐
                │ claude / codex /  │──▶│  上游模型 API        │
                │ gemini / opencode │   │  anthropic/openai/…  │
                │ pi (RPC adapter)  │   └─────────────────────┘
                └───────────────────┘
                            └── 进程的 BASE_URL 指向 Gateway ──┘
```

| | 控制面 | 数据面 |
|---|---|---|
| 职责 | 启动/驱动 agent，喂输入，收生命周期事件与产物 | 拦截 agent ↔ 模型流量 |
| 实现 | 进程 supervisor + 每家一个 `AgentAdapter` | 一个反向代理，按 wire protocol 分派 |
| 拿到 | 工具调用、文件变更、退出码、resume token | **system prompt、tools 定义、全量 messages、token、成本** |
| 易变性 | 高（CLI 格式常变）→ 靠 contract test 兜底 | 低（模型 API 是公开契约，变更缓慢） |
| 失败影响 | 该 adapter 的新 Invocation 不可启动；已运行进程进入对账/终止策略 | 录制子系统失败时转发继续并标 partial；Gateway 转发本身失败时请求失败，不伪装成 sidecar |

**这个拆分的直接收益**：数据面稳定，所以就算某个 CLI 升级把控制面打挂了，你的历史观测数据、成本核算、记忆提炼管线全都不受影响。

---

## 数据面：网关接入点

系统提示词只能在数据面拿到。各家的接入方式：

| Agent | 接入方式 | Wire Protocol | system prompt 位置 |
|---|---|---|---|
| Claude Code | `ANTHROPIC_BASE_URL` + `ANTHROPIC_AUTH_TOKEN` | anthropic | `body.system` (string 或 block[]) |
| Codex CLI | `~/.codex/config.toml` → `[model_providers.x] base_url` | openai-responses | `body.instructions` |
| Gemini CLI | `GOOGLE_GEMINI_BASE_URL`（**必须 API key 模式**） | gemini | `body.systemInstruction` |
| opencode | provider 配置 `baseURL` | 随所选 provider | 同上 |
| Pi Agent（可选，M2 后） | `PiRpcAdapter` 用隔离 `PI_CODING_AGENT_DIR/models.json` 编译 provider/model/base URL；内部 capability 走经 contract test 的 provider credential carrier | 随所选 provider | 对应协议字段 |

> **Gemini 注意**：OAuth / Code Assist 登录模式不走 `generativelanguage.googleapis.com`，网关拦不到。
> 必须用 `GEMINI_API_KEY` 模式才能进全代理。这个限制要在 UI 上显式提示。

### Session Overlay：CLAUDE_CONFIG_DIR 已验证支持

`CLAUDE_CONFIG_DIR` 环境变量**已验证**被 Claude Code 支持（v2.1.226 测试通过）。
每个 session 使用独立的 config 目录，多个 session 同时跑不会互相覆盖：

```bash
CLAUDE_CONFIG_DIR=~/.hearth/sessions/{sessionId}/.claude \
ANTHROPIC_BASE_URL=http://127.0.0.1:4517/s/{sessionId}/anthropic \
ANTHROPIC_AUTH_TOKEN={gatewayCapabilityToken} \
claude -p --input-format stream-json --output-format stream-json --verbose \
  --replay-user-messages --session-id {sessionId}
```

cwd 不通过不存在或版本易变的 `--project-dir` 参数传递；`LocalWorkerClient` 通过本机受认证 transport 调用
`hearth-worker`，由 Worker daemon 在其受限权限下执行 `ProcessBuilder.directory(validatedCwd)`；远程 Worker 使用等价的无
shell 工作目录设置。Prompt 通过 NDJSON stdin 发送，不进入 argv。Adapter contract test 必须针对锁定的 Claude Code
版本验证第二条输入能否在 result 事件后继续复用同一进程；不能复用时采用“每 Invocation 新进程 + 带版本 resume”策略，
但逻辑 Session ID 不变，禁止假设所有 CLI 都是长驻进程。

本机部署明确分成三类服务身份：`hearth-api` 运行控制面/Gateway 并可读取 provider secret；`hearth-worker` 只读取
Worker credential、启动配置和 session capability；`hearth-agent` 是低权限 CLI 子进程，只能访问允许的 workspace root
和本次 Session 所需的 overlay。Agent 不继承 API 的完整环境，也不能读取 API 的 secret、数据库凭证、Artifact/raw
recording 或 Worker control credential。低权限 UID/文件权限是 M1 的最小进程边界；若宿主机上的用户本身是 root 或能调试
其他进程，则不宣称这是多用户安全边界。

每个 session 的 overlay 目录结构：
```
~/.hearth/sessions/{sessionId}/.claude/
  CLAUDE.md          ← @AGENTS.md + 任务 Goal + 相关记忆摘要
  rules/
    _platform.md     ← 平台约束（约束检查、G4C+E 规范）
    _task.md         ← 本次任务专属规则
  settings.json      ← hooks（串联用户原有 hooks）
  hearth-mcp.json    ← M2 生成的 session 专属 MCP 配置，不写入项目目录
```

M2 启用 Platform MCP 时，Claude Code Adapter 追加
`--strict-mcp-config --mcp-config {overlay}/hearth-mcp.json`。Overlay compiler 把 Profile 允许的用户 MCP 与
Hearth MCP 合并后生成该文件；`--strict-mcp-config` 防止项目 `.mcp.json` 或用户配置绕过 normalized
capability / PolicyBundle 约束。文件
权限为 0600，Session 终止时撤销 token 并删除。M1 不启用 MCP，不传这两个参数。
内置工具另由锁定版本支持的 `--tools`/`--disallowedTools` 和本地 fail-closed hook wrapper 双层约束；MCP
strict config 不能替代 Bash/Edit/Read 等内置工具治理。

### Pi RPC Adapter：控制通道与模型数据通道仍然分离

Pi 只作为 Worker 上的可选外部 runtime，不嵌入 Java 核心。`PiRpcAdapter` 用 LF-delimited JSONL stdin/stdout
驱动 `prompt/steer/follow_up` 并把事件归一化；Pi 自身的 provider transport 继续生成对应 wire request，再把
base URL 指向 Hearth Gateway。Orchestrator 不理解 Pi JSONL，Gateway 也不解析 Pi RPC。

Pi project/global Extension/Skill/Template/context 默认禁用；Worker 使用固定 argv allowlist、隔离的
`PI_CODING_AGENT_DIR` 和 Worker-owned `models.json` 编译 route，因为 Pi 没有通用 `--base-url` 参数。RPC 看见
tool call 只构成 `OBSERVE_ONLY`；只有 OS/container sandbox，或“禁用 built-in tools + Hearth-owned Broker
Extension”通过验收后，Adapter 才能分别声明 `SANDBOX_ENFORCED` 或 `BROKERED`。Pi v0.84.1 只有
`agent_settled` 映射为 Invocation `semantic_completed`；`message_end`/`agent_end` 不能提前结束。Pi 原生 session
文件只能作为 Worker 管理的 ResumeDescriptor carrier，Session/Invocation/Exchange 的真相源仍是 Postgres。
完整边界见 `docs/15-pi-agent-adr.md`。

给每个 agent 进程发一个**独立的 base_url 路径**，而不是靠 header 猜：

```
http://127.0.0.1:4517/s/{sessionId}/anthropic
http://127.0.0.1:4517/s/{sessionId}/openai
http://127.0.0.1:4517/s/{sessionId}/gemini
```

理由：所有首期 Adapter 都必须通过 contract test 证明其 base URL 能携带路径；对自定义 header 的支持则参差不齐
（Claude Code 有 `ANTHROPIC_CUSTOM_HEADERS`，Codex 有 `http_headers`，Gemini CLI 没有）。路径负责稳定归因，
但**不等于认证**。

Gateway 为每个 Session 持有服务端生成的 `GatewayIngressAuthBinding`，按 adapter + wire protocol 固定唯一内部
credential carrier。例如 M1 Claude Code/Anthropic 使用 `Authorization: Bearer`；Pi Anthropic transport 若只能通过
provider API-key 环境变量发出 `x-api-key`，该 Session 就显式绑定 `x-api-key` carrier。Gateway 先从预期 carrier
验证 gateway capability，拒绝缺失、重复或冲突 credential，然后移除所有内部/客户端 credential，再从服务端
credential reference 注入上游密钥。不能把“Provider 原生 header 中有一个 token”误当成允许它直通上游。

Base URL 后仍会追加 provider API path。M1 的 Anthropic protocol handler 只允许经过 contract test 的
`POST /v1/messages` 和 `POST /v1/messages/count_tokens`（以及明确加入 allowlist 的后续路径），拒绝 path
traversal、重复编码、未知 method/path 和把绝对 URL 塞进 path。Messages 请求创建 Exchange；纯 token-count
调用记录为 protocol diagnostic/usage probe，不伪装成一次模型 Exchange。上游目标始终由 provider route
重建，不能把入站 Host/path 原样拼接成任意 URL。

### 会话级模型路由：M1 只做同协议路由

网关按 sessionId 和受信 `providerRouteId` 重写 `model`，因此同一 CLI 的不同 Session 可以选择不同模型。
**M1 的边界是上游必须兼容该 CLI 的 wire protocol**：例如 Claude Code 的 Anthropic Messages 请求可以
路由到兼容 Anthropic 协议的 endpoint，但不能只改 model 字段就转成 OpenAI Responses 或 Gemini 协议。

跨 provider 如果 wire protocol 相同，可以由 provider route 直接切换；wire protocol 不同则需要独立、
可测试的协议翻译 Adapter，明确处理 tools、streaming、usage、errors、cache 和私有扩展。该翻译不属于 M1，
也不能由通用 JSON 字段改写冒充支持。

对比 CLI 的 `--model` 参数：Gateway route 可以绕开 CLI 的 endpoint 固定限制，但不会绕开 wire protocol
语义。Session API 只提交 `providerRouteId`，不能提交任意 upstream URL；route 固定 host 白名单、协议、
模型白名单和 credential reference。

---

## 观测等级：因为「混合计费」，它必须是一等公民

订阅（Max/Plus）走 OAuth，把 `BASE_URL` 指走会退化成 API 计费。所以观测等级
**按 session 选择**，并在数据模型里显式记录——不能假装这个 tradeoff 不存在。

| 等级 | 机制 | 拿得到 | 拿不到 | 计费 |
|---|---|---|---|---|
| `full` | 全代理，流量过网关 | system prompt 全文、tools 定义、全量 messages、精确 token；配置版本化 pricing 时计算成本 | — | API |
| `sidecar` | Claude Code hooks（`UserPromptSubmit`/`PreToolUse`/`PostToolUse`/`Stop`）+ 读 `~/.claude/projects/*.jsonl` 转录 | 用户输入、助手输出、工具调用与结果、**准确 token/成本**（转录文件实测确认） | system prompt 全文、tools schema | 订阅 |
| `none` | 仅控制面生命周期 | 开始/结束/退出码 | 其余全部 | 订阅 |

UI 上每个 session 必须标注当前观测等级。降级要有明确原因
（`subscription_auth` / `oauth_only_provider` / `user_opt_out` / `gateway_unreachable`），
写进 `Session.observabilityReason`，否则事后没法解释「为什么这条记录缺 system prompt」。

### observability_level vs recording_status：两件不同的事

这两个经常被混淆，但解决的是不同问题：

- **observability_level** = 接入机制（full/sidecar/none），表示"你配置了什么"
- **recording_status** = 实际结果（complete/partial/failed），表示"实际拿到了什么"

处于 `full` 模式但录制队列溢出时，exchange 必须标记为 `recording_status=partial`，
不能静默丢失后仍显示"完整"。UI 里看到 `full` 模式的 exchange 显示"完整对话"，
如果 recording_status=partial，必须明确提示"部分数据丢失，原因：queue_overflow"。

```
observability_level=full + recording_status=complete  → 完整数据，最理想
observability_level=full + recording_status=partial   → full 接入但录制有丢失（queue 满/存储失败）
observability_level=sidecar + recording_status=complete → sidecar 范围内完整
```

exchange 表中用 `recording_status` + `recording_gap_reason` 字段记录，见 `docs/03-schema.md`。

---

## 流式透传：不聚合响应是硬要求

网关在 agent 的关键路径上，缓冲整段响应会变成肉眼可见的卡顿。M1 验收以直连为基线，
要求网关附加 p95 TTFB ≤ 50ms，并确认 SSE 事件逐条到达；不使用无法证伪的“零延迟”表述。实现约束：

- Java 实现：读响应 `InputStream` 时，`write` 同时到客户端 `OutputStream` 和录制缓冲，用虚拟线程做阻塞 IO，代码直白无 DataBuffer 泄漏风险
- 录制管线必须是**异步且失败不影响转发**——录制炸了就丢观测，绝不阻断 agent（用独立线程 + 有界队列，队列满直接丢弃而不是阻塞）
- 不做完整 SSE 聚合后再转发；解析器必须跨任意网络 chunk 增量解码 UTF-8，支持 CRLF、注释、一个事件
  多个 `data:` 行和事件大小上限，不能假设一次 read 或一行就是一个完整事件
- 转发侧在完整 SSE event 边界主动 `flush()`；对尚未形成事件的长 chunk 设置最大 flush interval，避免
  Servlet/container 缓冲造成可见延迟
- 请求默认硬上限 32 MiB；≤1 MiB 可内存缓冲，超过后 spool 到权限 0600 的临时文件并在 finally 删除，
  禁止无限读取完整 history 直到 OOM
- 背压：录制侧消费慢时队列满丢弃，而不是拖慢转发侧

---

## 存储

```
Postgres 一把梭   关系 + JSONB(事件载荷) + pgvector(记忆检索)
对象存储          大 artifact（diff、构建产物、截图）
Redis             只做队列和临时状态，不做主存
事件总线          Postgres LISTEN/NOTIFY 起步，量大再上 NATS
```

不建议把运行时状态放 Redis 做主存（clowder-ai 的做法）：agent 编排的状态需要事务性和
可回溯查询，Redis 两者都弱，且崩溃丢状态会导致长任务无法恢复。

---

## 运行生命周期：Session 不等于一次执行

```text
Task        业务目标（M2）
Session     长期逻辑 agent 会话，可包含多轮 Invocation 和多个进程代次
Invocation  一次 user turn / agent activation
Exchange    Invocation 内一次模型 API 调用
```

控制面必须分别处理 `semantic_completed`、`stream_eof`、`process_exited`、`transport_disconnected`。
收到语义完成事件即可结束 Invocation；不能等待长驻 CLI 的 stdout 关闭。Worker 事件使用
`(sessionId, processGeneration, eventSeq)` 重放，中心持久化 `domain_event`，再由单 publisher 在提交后分配
全局可见的 `event_publication.publication_seq`。

---

## 相关文档

- [模块划分](./02-modules.md)
- [Internal Dispatch、A2A Adapter 与防套娃](./05-a2a-and-loops.md)
- [记忆分层](./06-memory.md)
