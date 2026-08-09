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

**不做**：多 agent、记忆、调度、任何 UI 美化。Postgres 在 M1 就装好（和 M2 共用，不迁移）。

**M1 结束后你会知道**：
- 网关附加延迟是否达到 p95 TTFB ≤ 50ms（以相同请求直连为基线）
- 从现有直连或第三方中继迁移到 Hearth 有没有隐藏问题
- system prompt 里实际有什么（对后续记忆设计有影响）

---

## M1 可执行验收规格

M1 不是“页面看起来能用”就完成。验收在全新 PostgreSQL 16 数据库上执行，并保存命令输出、
HTTP 响应和 UI 截图为 Artifact。

### Given：干净、可重复的环境

- 给定全新数据库，Flyway 从 0 执行 V001，一次成功且 `validate` 通过。
- 仅从环境变量加载 `HEARTH_DB_PASSWORD`、`ANTHROPIC_API_KEY`；缺失时启动明确失败。
- 网关只监听配置的本机地址；测试日志和数据库检索确认没有 secret 或 capability token 明文。
- 创建 standalone session 后获得一次性的 gateway capability token，数据库只存在 SHA-256 hash。

### When：运行真实 Claude Code 对话

1. 通过 WorkerClient 启动 Claude Code，注入 session 路径与 gateway token。
2. 在同一 Session 发出至少两次 Invocation，包含至少两轮 user 消息和一次 tool use/tool result。
3. 模拟 adapter `semantic_completed` 后 stdout 仍打开，验证 Invocation 完成而 Session 进程可继续复用。
4. 网关转发真实流式 Anthropic Messages 请求；测试代理或受控上游断言：
   - Hearth `Authorization` 未转发。
   - 静态 Anthropic key 仅出现在 `x-api-key`。
   - `anthropic-version` 被保留。
5. 人为执行一次包含重复完整 history 的后续请求，验证 transcript 不重复旧 turn。
6. 人为制造一次录制存储失败，验证响应仍持续流式返回且 exchange 标为 partial/failed。
7. 中断 Worker event transport，在断线期间产生事件，重连后按 `(processGeneration,eventSeq)` 重放。
8. 提交 durable 状态后丢弃 final SSE frame，验证 UI 通过 snapshot + watermark 最终收敛。

### Then：数据库与 API 断言

- `session` 记录的 `task_id IS NULL`，provider route/workspace root/observability 字段完整；两次 Invocation 各有唯一终态。
- Session 仍存活但无非终态 Invocation 时，API/UI 不显示为“正在回复”。
- 每次上游调用恰有一个 exchange 且归属于 Invocation；响应结束后 token、延迟、状态和 `updated_at` 已更新。
- `/api/v1/sessions/{id}` 返回最新完整 system prompt 和实际 recording status。
- `/transcript` 顺序包含两轮 user/assistant/tool 内容且无 history 重复；重复文本发生在不同 turn 时仍保留。
- `/exchanges` token 汇总与 Anthropic usage 一致；录制失败的 exchange 带 gap reason。
- `/raw-request` 只能经用户管理权限下载，已脱敏；agent token 调用返回 403。
- `domain_event.event_id` 严格递增；带 `Last-Event-ID` 重连 SSE 只补发之后事件。
- Worker 重连按 eventSeq 去重重放；旧 process generation 的迟到事件不改变新代状态。
- Session API 无法提交任意 upstream URL、绝对 cwd 或 executable；目录逃逸与非白名单模型被拒绝。
- M1 管理 API 要求本地管理员会话与 CSRF，gateway/MCP token 不能越权调用。

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

所有 Then 断言均须有独立 Artifact；缺任一 Evidence，M1 不算完成。

---

## M2：两个 agent 协作完成一个真实任务（目标 4-6 周）

**验收标准**：通过 Hearth 分配一个真实编码任务（比如"帮我给这个项目加一个功能"），architect 和 coder 两个 agent 自动协作完成，Web UI 里能看到完整的调用图和每个 agent 的 system prompt。

**做什么**：

1. Agent 管理
   - Profile 配置（system prompt、工具白名单、约束）
   - Profile 版本管理
   - 启动时注入 session 专属 base_url

2. 任务编排（最小版）
   - 接收任务 → planner 拆解 → 分配给对应 Profile
   - A2A 消息收发（request / consult / notify 三种类型）
   - 预算继承（ratio 划拨，几何衰减）
   - 祖先链环检测（存 sessionId，不存 role name）

3. 约束与监察
   - 工具调用前检查是否在白名单
   - `auto_terminate` + `suspend_wait` 两种处置
   - 操作日志（undo 清单的基础）

4. Artifact 存储
   - 代码变更（diff）、文档、审查报告
   - A2A 消息携带 artifactId 传递上下文

5. 调用图可视化
   - 甘特图（谁在什么时间干活）
   - DAG（节点=session，边=A2A 消息，颜色=状态）

**迁移**：M1 的 V001 已创建完整核心关系结构，M2 不新增 Task/A2A 核心表，只启用对应模块和入口。
pgvector 与 `memory_card` 由 M3 的 V002 引入。

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
   - 任务完成后评估拆解质量
   - 历史拆法进 L2 记忆

---

## 有意推迟的功能

下面这些**不进前三个里程碑**，但已经在设计里预留了接口：

| 功能 | 为什么推迟 |
|---|---|
| Codex / Gemini / opencode 接入 | M1 只需要 Claude Code 验证核心假设；其他 CLI 按适配器接口添加即可 |
| Skill 市场 | 记忆系统先跑起来，看哪些行为值得固化成 skill。**到时候必须有 sandbox 规格（seccomp + cgroups + overlayfs）**，OpenClaw 2026 年的供应链攻击教训：sandbox 是强制项，不是可选项 |
| Skill 提案捕获 | M3 记忆系统稳定后加。机制：任务完成后检测「可重复 step sequence」，生成候选 Skill 进 Inbox 让用户确认，不自动创建 |
| Agent Council 模式 | M2 A2A 基础设施完成后加，fan-in 合并逻辑不复杂 |
| 自动回滚 | 操作日志（M2）是前提；自动回滚复杂且容易出错，手动清单够用 |
| 多用户 / 多 workspace | 自用阶段单 workspace，开源后再加隔离层 |
| Web UI 完整设计 | M1 用最简 UI，M3 之后再投入前端设计 |

---

## 现在最该做的一件事

验证迁移路径：把现有 Claude Code 直连或第三方中继链路，改成 Claude Code → Hearth 网关 → Anthropic。

```bash
# 找到 cc-switch 写的 base_url 在哪
grep -r "baseUrl\|base_url\|apiUrl\|anthropic" ~/.config/claude* ~/.claude.json 2>/dev/null | grep -v ".git"
```

找到配置位置，M1 就有了第一个具体的起点。
