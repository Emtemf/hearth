# ADR: Pi Agent 作为可选 Agent Adapter

## 状态

已决策（2026-08-09）

## 背景

Hearth 需要支持多种编码 Agent CLI（Claude Code、Codex、Gemini CLI、opencode），
并考虑是否可以利用 [Pi Agent](https://github.com/earendil-works/pi) 的成熟运行时能力。

Pi Agent 是一个 TypeScript/Node.js 编码 Agent 框架（MIT 许可证，v0.84.1），
具有以下特点：

- 四种运行模式：interactive TUI / print / RPC（JSONL stdin/stdout）/ SDK
- 统一多 Provider LLM API（`@earendil-works/pi-ai`）
- Agent Runtime（`@earendil-works/pi-agent-core`）：工具调用、状态管理
- Extension 系统：TypeScript 插件，可注册自定义工具、命令、事件处理器
- Skills（基于 prompt 的工具）和 Prompt Templates
- 内置工具：read、write、edit、bash、grep、find、ls
- RPC 模式天然适合子进程集成：LF-delimited JSONL，有 prompt/steer/follow_up 命令
- **没有**内置权限系统（需外部容器/沙箱）
- **没有** MCP 支持（由 Extension 实现）
- **没有**内置 sub-agent / plan mode / permission popups

## 决策

### Pi Agent 不作为 Hearth 的基础运行时

**理由：**

1. **语言栈冲突**：Pi 是 TypeScript/Node.js 生态，Hearth 核心是 Java/Spring Boot。
   嵌入 Pi SDK（`createAgentSession`）要求 Hearth 运行 Node.js 进程，
   与虚拟线程 + Spring MVC 的控制面设计冲突。

2. **控制权倒置**：如果以 Pi 为地基，Agent Loop、工具调度、上下文管理都在 TypeScript 层，
   Java 控制面退化为"启动器 + 存储"，失去对执行循环的观测和干预能力。

3. **上游迭代快**：Pi v0.84.1，活跃开发中。魔改后合并上游变更的成本会随时间急剧增长。

4. **Provider 路由冲突**：Pi 自带多 Provider 系统（`pi-ai`），Hearth 的核心价值之一是
   透明网关控制 Provider 路由和凭证。两套路由并存会导致混淆和安全边界不清。

### Pi Agent 作为可选 Agent Adapter 接入

**方式：**

通过 WorkerClient 以子进程方式启动 Pi，使用 **RPC 模式**（`pi --mode rpc`）通信。

```
Hearth 控制面（Java）
  │
  ├─ WorkerClient.launch(PiLaunchSpec)
  │    └─ ProcessBuilder: pi --mode rpc --no-session
  │         --provider anthropic --model claude-sonnet-5
  │         --tools read,bash,edit,write,grep,find,ls
  │
  ├─ Gateway: ANTHROPIC_BASE_URL 指向 Hearth 网关
  │    └─ Pi 的所有 LLM 调用经过 Hearth 网关透传
  │
  ├─ RPC 双向通信（stdin/stdout JSONL）
  │    ├─ 向 Pi 发送 prompt / steer / follow_up
  │    └─ 从 Pi 接收 tool_call / assistant_message / usage 事件
  │
  └─ Provider 凭证
       └─ Pi 不使用自身 Provider 系统，全部走 Hearth 网关
```

**关键约束：**

- Pi 的 `--api-key` 由 Hearth 签发的 session capability token 替代，
  通过 `ANTHROPIC_BASE_URL` 指向 Hearth 网关
- Pi 的 Provider 配置（`models.json`、`/login`）在 Adapter 层被覆盖，
  不允许 Agent 自行配置 Provider
- Pi 的 Extension 和 Skill 系统在 M1 不启用；M2+ 评估是否通过
  Hearth 的 Profile overlay 注入自定义 Extension

### 不做的事

| 不做 | 原因 |
|---|---|
| 不把 Pi 的 TypeScript Agent Runtime 作为 Hearth 核心 | 语言栈冲突 + 控制权倒置 |
| 不 fork Pi | 上游活跃，分叉维护成本高 |
| 不使用 Pi 的多 Provider 系统 | 与 Hearth 网关路由冲突 |
| 不在 M1 启用 Pi Extension | 范围控制，M1 只验证 Claude Code |
| 不让 Pi 自行管理 session 存储 | Session 状态由 Hearth Postgres 管理 |
| 不把 Pi 作为唯一 Adapter | Claude Code/Codex/Gemini 仍是主要 CLI |

## 对现有设计的影响

### 无破坏性影响

- Worker command protocol 不因 Pi 改变，Pi 是另一种 Adapter/`LaunchSpec`
- Gateway 透明转发不变，Pi 的 LLM 调用与其他 CLI 走同一条路径
- Session/Invocation/Exchange 模型不变
- G4C+E 和 Evidence 要求不变

### 需要新增/调整

- **`docs/15-pi-agent-adr.md`**（本文档）：Pi Agent 集成决策
- **`AGENTS.md`**：有意推迟的功能表中删除 Pi，改为"可选 Adapter（M2+）"
- **`docs/02-modules.md`**：Agent Adapter 能力列表补充 Pi RPC 模式
- **`docs/07-roadmap.md`**：M2 或 M3 增加 Pi Adapter 验证里程碑
- **`docs/12-worker.md`**：Adapter 示例增加 Pi RPC 模式
- **`README.md`**：项目目标和文档索引补充 Pi Agent

## 兼容性

- Pi 的 MIT 许可证与 Hearth 的 Apache-2.0 兼容
- Pi 的 RPC 协议（LF-delimited JSONL）简单且稳定
- Pi 在没有受审 Extension 或 OS sandbox 时只能声明 `OBSERVE_ONLY`，不得运行要求前置权限阻断的 Profile
- Pi 的 Extension 系统不影响 Hearth 的 Skill 记忆进化机制

## 验证标准

M2+ 阶段验证：

1. 通过 Hearth 启动 Pi（RPC 模式），完成一次真实编码任务
2. Hearth 网关正确观测 Pi 的 system prompt 和 token 用量
3. 受审 Extension 或 sandbox 启用后，Pi 的工具调用（read/edit/bash）正确经过 Hearth 权限检查；启用前
   preflight 必须拒绝要求 `HOOK_ENFORCED`/`BROKERED` 的 Profile
4. Worker ONLINE 且控制通道可达时，取消任务在 10s 内终止 Pi 进程
5. Pi 的 Extension 不绕过 Hearth 的预算和防套娃约束
