# 路线图

## 原则

每个里程碑都有一个**可以摸到的东西**作为验收标准，不是功能列表。
一个人做，优先深度而非广度——一件事做扎实再开始下一件。

---

## M1：看到 system prompt（预计 3-5 周，以验收通过为准）

**验收标准**：在 Web UI 里打开一条 Claude Code 会话记录，能看到这次会话的 system prompt 全文和 token 消耗。

**做什么**：

1. 最小网关
   - Spring Boot + 虚拟线程（不用 WebFlux）
   - 监听 `/s/{sessionId}/anthropic`
   - 缓冲请求体，抽出 `body.system` 存 **Postgres**（和 M2 同一个库，避免中途迁移）
   - SSE 响应逐事件透传，不聚合完整响应（附加 p95 TTFB ≤ 50ms）
   - `/actuator/health` 端点，网关挂了能立刻知道

2. Claude Code 接入
   - Session 创建只接受 `providerRouteId` 与 `workspaceRootId + relativeCwd`，禁止任意 URL/绝对路径
   - provider/model/credential/CLI/cwd preflight 后，通过 WorkerClient 启动进程
   - 启动脚本注入 `ANTHROPIC_BASE_URL` 和 session 专属 gateway capability token
   - 网关剥离内部 token，并用服务端当前 credential reference 重写为 Anthropic `x-api-key`
   - Session/Invocation/Exchange 分层，至少两次 Invocation 共用一个逻辑 Session
   - 验证对话、语义完成、进程退出与 transport 断开各自状态正确

3. 最简 Web UI
   - session 列表（时间、模型、token 用量）
   - 点进去看 system prompt 全文 + 完整对话

**不做**：Task/G4C+E、Internal Dispatch/A2A Adapter、Artifact/Evidence、Inbox、MCP、记忆、调度、远程 Worker WebSocket/replay、
精确美元成本和任何 UI 美化。Postgres/Flyway 在 M1 就装好，但只迁移当前 slice 实际使用的表；M2 用后续
migration 演进，不预建未来表。

**实施切片（顺序 gate，不并行铺模块）**：

| Slice | 可独立验证的出口 |
|---|---|
| M1.0 接入 spike | Claude Code 经最小受控代理完成真实流式请求，认证头替换与 base URL 行为有 fixture/抓包证据；并实测 stream-json result 后能否接收第二条 stdin，确定 ProcessReusePolicy；spike 不直接演化成生产 Controller |
| M1.1 数据面 | 有界请求读取、响应 streaming、Exchange 录制/partial、token usage 和协议 contract test 通过 |
| M1.2 运行面 | bootstrap → standalone Session → 本机 Process generation → 两次单活 Invocation → 中心重启 reconcile |
| M1.3 产品面 | 管理认证/CSRF、REST transcript、publication SSE/snapshot 收敛、最小 UI 和性能验收通过 |

任何 slice 未通过其自动化出口，不提前实现下一里程碑功能。原“3–5 周”只是目标窗口，若安全、恢复或真实
Claude Code contract 尚未通过，不以删验收项换日期。

**M1 结束后你会知道**：
- 网关附加延迟是否达到 p95 TTFB ≤ 50ms（以相同请求直连为基线）
- 从现有直连或第三方中继迁移到 Hearth 有没有隐藏问题
- system prompt 里实际有什么（对后续记忆设计有影响）

---

## M1 可执行验收规格

M1 不是“页面看起来能用”就完成。验收在全新 PostgreSQL 16 数据库上执行，并把命令输出、HTTP 响应和
UI 截图保存为 CI/build Evidence artifact（带 URI+sha256 的验收 manifest）。Hearth 自身的 Artifact API 是
M2 能力，M1 验收不能反过来依赖尚未实现的 M2 模块。

### Given：干净、可重复的环境

- 给定全新数据库，Flyway 从 0 执行 V001/V002，一次成功且 `validate` 通过；Task/Dispatch/Evidence 表尚不存在。
- 从环境变量加载 `HEARTH_DB_PASSWORD`、`ANTHROPIC_API_KEY`、`HEARTH_WORKSPACE_ROOT` 和
  `HEARTH_ANTHROPIC_ALLOWED_MODELS`；缺失或路径/模型列表无效时启动明确失败。
- 网关只监听配置的本机地址；测试日志和数据库检索确认没有 secret 或 capability token 明文。
- LocalWorkerClient child environment 已剥离真实 provider/数据库/IM/admin credential，只含 session capability；
  验收用 hash/变量名检查，不把 secret 本身打印进 Evidence。
- 创建 standalone session 时 application service 签发一次性的 gateway capability token，明文只进入
  受保护的 `LaunchCommand` 环境，浏览器响应不含 token，数据库只存在 SHA-256 hash。

### When：运行真实 Claude Code 对话

1. 通过 WorkerClient 启动 Claude Code，注入 session 路径与 gateway token。
2. 在同一 Session 发出至少两次 Invocation，包含至少两轮 user 消息和一次 tool use/tool result。
3. 用 Adapter fixture 模拟 `semantic_completed` 后 stdout 仍打开，验证 Invocation 完成不依赖 EOF；真实 CLI
   再按已验证的 ProcessReusePolicy 断言“同代进程回 READY”或“进程正常退出、逻辑 Session 可换代 resume”。
4. 网关转发真实流式 Anthropic Messages 请求；测试代理或受控上游断言：
   - Hearth `Authorization` 未转发。
   - 静态 Anthropic key 仅出现在 `x-api-key`。
   - `anthropic-version` 被保留。
5. 人为执行一次包含重复完整 history 的后续请求，验证 transcript 不重复旧 turn。
6. 人为制造一次录制存储失败，验证响应仍持续流式返回且 exchange 标为 partial/failed。
7. 在进程存活时重启中心，验证本机 WorkerClient reconcile 后状态与 Postgres 收敛；远程 event replay 推迟到 M2。
8. 提交 durable 状态后丢弃 final SSE frame，验证 UI 通过 snapshot + watermark 最终收敛。

### Then：数据库与 API 断言

- `session` 记录的 `task_id IS NULL`，provider route/workspace root/observability 字段完整；两次 Invocation 各有唯一终态。
- 逻辑 Session 仍可用但无非终态 Invocation 时，API/UI 不显示为“正在回复”；是否有长驻进程不改变该判断。
- 每次上游调用恰有一个 exchange 且归属于 Invocation；响应结束后 token、延迟、状态和 `updated_at` 已更新。
- `/api/v1/sessions/{id}` 返回最新完整 system prompt 和实际 recording status。
- `/transcript` 顺序包含两轮 user/assistant/tool 内容且无 history 重复；重复文本发生在不同 turn 时仍保留。
- `/exchanges` token 汇总与 Anthropic usage 一致；录制失败的 exchange 带 gap reason。
- `/raw-request` 默认关闭；测试显式开启后仅管理员可下载，已知 credential 被移除且响应标记可能含敏感会话
  内容；agent token 调用返回 403，解析/已知 secret 无法安全处理时返回 409。
- `domain_event.event_id` 只作内部标识；提交后 `event_publication.publication_seq` 严格递增，带 `Last-Event-ID` 重连 SSE 只补发之后的 publication。
- 本机进程重对账不接受旧 process generation 的迟到结果；远程 Worker eventSeq 重放在 M2 验收。
- Session API 无法提交任意 upstream URL、绝对 cwd 或 executable；目录逃逸与非白名单模型被拒绝。
- M1 管理 API 要求本地管理员会话与 CSRF，gateway token 不能越权调用；MCP 尚未启用也不签发 token。

### Then：UI 与性能断言

- 1280px 宽桌面浏览器可打开 Session 列表与详情。
- 详情可查看完整 system prompt、Transcript 和 Exchange 时间线；partial 状态有明显 gap 提示。
- CodeMirror 长 prompt 可滚动、复制，不把内容作为 HTML 执行。
- 用固定 100 次请求的本机基准比较直连与网关：记录首字节延迟分布；M1 门槛为
  **网关附加 p95 TTFB ≤ 50ms，且流式事件不聚合后批量出现**。不宣称“零延迟”。
- 浏览器断开再连后 UI 通过 SSE 续传恢复，无重复事件；cursor 过期时显示 RECONCILING，
  通过 REST snapshot + event watermark 收敛，断线期间显示 STALE/最后同步时间。

### 自动化测试分层

- Unit：协议解析、model rewrite、CredentialRewriter、canonical transcript fingerprint、状态聚合。
- Integration：Testcontainers PostgreSQL + Flyway、真实 HTTP streaming stub、凭证剥离、SSE 续传。
- Contract：固定的 Anthropic 请求/SSE fixture，包含 string/block system、tool use、错误帧和 usage。
- E2E：浏览器启动 standalone session、完成对话、查看 prompt/transcript/gap；关键流程必须录屏或截图。
- 覆盖率：项目规则要求总体 ≥80%；安全与凭证重写分支必须全部覆盖。

所有 Then 断言均须在验收 manifest 中有独立 Evidence URI+sha256；缺任一 Evidence，M1 不算完成。

---

## M2：两个 agent 协作完成一个真实任务（目标 4-6 周）

**验收标准**：通过 Hearth 分配一个真实编码任务（比如"帮我给这个项目加一个功能"），architect 和 coder 两个 agent 自动协作完成，Web UI 里能看到完整的调用图和每个 agent 的 system prompt。

**做什么**：

1. Agent 管理
   - Profile 配置（system prompt、normalized capabilities、约束）
   - Profile 与 PolicyBundleVersion 版本管理
   - 启动时注入 session 专属 base_url

2. 任务编排（最小版）
   - `TaskRequest → TaskSpecVersion → PlanVersion → TaskExecution`，`CLARIFYING` 是一等阶段
   - Hearth Internal Dispatch：request 创建 Child Task；consult 只创建 Invocation
   - 预算继承（ratio 划拨，几何衰减）
   - 祖先链环检测（存 sessionId，不存 role name）

3. 约束与监察
   - normalized capability → adapter tool mapping；PolicyBundle 强制 CodeGraph/官方文档等项目策略
   - `auto_terminate` + `suspend_wait` 两种处置
   - 操作日志（undo 清单的基础）

4. Artifact 存储
   - 代码变更（diff）、文档、审查报告
   - EvidenceClaim + VerificationRecord；Artifact 只是材料，语义评审使用独立 reviewer Session
   - Dispatch 消息携带 artifactId 传递上下文

5. 调用图可视化
   - 甘特图（谁在什么时间干活）
   - Session Graph（边=Internal Dispatch）与独立 Task Tree

**迁移**：M2 用 V003–V005 引入 Task/Spec/Plan、Policy、Dispatch/Budget、Artifact/Evidence/Inbox/Ops；必须
验证从带真实 Exchange 的 M1 数据库无损升级。pgvector 与 `memory_card` 在 M3 的后续 migration 引入。

**M2 后兼容性 slice（不阻塞 M2 核心验收）**：Pi Agent 仅在 Worker、Profile、Policy 和 Evidence 基础设施
通过后接入。隔离 Pi config/resource、RPC framing、`agent_settled` completion、Gateway 内部认证 carrier、
`OBSERVE_ONLY`/sandbox/Broker 治理等级、resume 和取消必须按 `docs/15-pi-agent-adr.md` 单独验收；不能为了展示
第二种 CLI 缩减 M2 的 architect+coder 验收。

---

## M3：Jarvis 体验（目标再 6-8 周）

**验收标准**：在飞书发一条消息分配任务，agent 自动执行，需要你决策时推通知，完成后把结果发回飞书。

**做什么**：

1. 触发层
   - 飞书 Bot 适配器（消息 → Task，Inbox → 飞书通知）
   - Telegram 适配器（同上）
   - cron 定时任务（Quartz + ShedLock）

2. 记忆提炼管线
   - session 结束后，用 Haiku 级模型抽取候选记忆卡片
   - 价值打分（不可推导性 + 纠错性 + 复用性）
   - L2 入库 + 冲突检测
   - pgvector 相似检索

3. assistant Profile 完善
   - 日常助理能力（搜索、提醒、信息整理）
   - 快速通道（`!` 前缀跳过 planner）

4. Jarvis 自动化模板
   - 每日 AI 资讯：来源白名单、去重、相关性与 Evidence
   - 量化学习与英语学习：提交证据、不过度追补、周报
   - P0/P1/P2 通知、quiet hours、Feishu/Telegram 幂等投递
   - schedule 只能由人/管理 API 创建，自动化 Task 不得递归创建 cron
   - 详细规格见 `docs/14-automation.md`

5. Planner 反馈回路
   - 用 checkpoint failure、rework、human correction、Goal Verification 和 final acceptance 评估拆解质量
   - 不以 planner/reviewer 自评分为主；高价值历史拆法经记忆管线提炼后进入 L2

---

## 不进入当前里程碑核心验收的功能

下面这些能力已有扩展边界，但不会为了展示广度而阻塞或稀释 M1–M3 的核心验收：

| 功能 | 为什么推迟 |
|---|---|
| Codex / Gemini / opencode Adapter | M1 只用 Claude Code 验证核心假设；后续 CLI 按同一 Worker/Adapter contract 独立验收 |
| Pi Agent RPC Adapter | 等 M2 Worker、Profile、Policy 和 Evidence 基础设施通过后再做兼容性 slice；默认只允许 `OBSERVE_ONLY`，完整边界见 `docs/15-pi-agent-adr.md` |
| Skill 市场 | 记忆系统先跑起来，看哪些行为值得固化成 skill。**到时候必须有 sandbox 规格（seccomp + cgroups + overlayfs）**，OpenClaw 2026 年的供应链攻击教训：sandbox 是强制项，不是可选项 |
| Skill 提案捕获 | M3 记忆系统稳定后加。机制：任务完成后检测「可重复 step sequence」，生成候选 Skill 进 Inbox 让用户确认，不自动创建 |
| Agent Council 模式 | M2 Internal Dispatch 基础设施完成后加，fan-in 使用独立 synthesizer/reviewer Session |
| 自动回滚 | 操作日志（M2）是前提；自动回滚复杂且容易出错，手动清单够用 |
| 多用户 / 多 workspace | 自用阶段单 workspace，开源后再加隔离层 |
| Web UI 完整设计 | M1 用最简 UI，M3 之后再投入前端设计 |

---

## 现在最该做的一件事

当前只推进 M1 迁移路径：把现有 Claude Code 直连或第三方中继链路，改成 Claude Code → Hearth 网关 → Anthropic。

Pi 不属于当前实现前置项。M2 核心验收通过后，再按独立 compatibility slice 验证隔离 Pi resource、RPC framing、
`agent_settled` completion、Gateway 内部 credential carrier、准确的工具治理等级、resume 和 10 秒取消；不得把
观察到 tool call 写成“已经过权限检查”。

```bash
# 找到 cc-switch 写的 base_url 在哪
grep -r "baseUrl\|base_url\|apiUrl\|anthropic" ~/.config/claude* ~/.claude.json 2>/dev/null | grep -v ".git"
```

找到配置位置，M1 就有了第一个具体的起点。
