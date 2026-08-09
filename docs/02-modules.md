# 模块划分

## 总览

```
┌─────────────────────────────────────────────────────────────┐
│                        入口层（触发层）                        │
│          飞书 / Telegram / Web UI / cron / webhook            │
└─────────────────────────┬───────────────────────────────────┘
                          │ Task 对象
┌─────────────────────────▼───────────────────────────────────┐
│                       任务编排模块                             │
│          接收 → 拆解（planner）→ 分配 → 生命周期管理           │
└──────┬──────────────────────────────────────┬───────────────┘
       │ 启动进程、喂任务                        │ 观测 / 路由
┌──────▼──────────┐                  ┌─────────▼──────────────┐
│   Agent 管理模块  │                  │       网关模块           │
│  Profile / 版本  │                  │  流量拦截 / system prompt│
│  约束 / 工具白名单│                  │  模型路由 / 成本追踪      │
└──────┬──────────┘                  └────────────────────────┘
       │ 产出
┌──────▼──────────┐
│  Artifact 存储   │  ←── agent 之间传递上下文靠这里
│  代码/文档/报告  │
└──────┬──────────┘
       │ 提炼
┌──────▼──────────┐
│  记忆与知识库    │
│  L1 情节 / L2   │
│  语义（高价值）  │
└─────────────────┘
```

---

## 模块一：网关

**职责**：数据面。拦截 agent ↔ 模型的流量，观测内容，路由模型，追踪成本。

详见 [01-architecture.md](./01-architecture.md)。

关键决策：
- 每个 agent 进程拿到唯一的 `http://gw/s/{sessionId}/{protocol}` 路径，天然并发隔离
- 请求体可缓冲（抽 system prompt），响应体**逐事件透传、不整段聚合**（M1 附加 p95 TTFB ≤ 50ms）
- 录制失败不能阻断转发——丢观测可以，卡 agent 不行
- 实现用 Spring Boot 虚拟线程，不用 WebFlux（调试 DataBuffer 泄漏代价太高）

---

## 模块二：Agent 管理

**职责**：维护每个 agent 的 Profile（人格 + 工具 + 约束），以及 Profile 版本。

### Profile 的四个要素

```
名称 / 角色标识    architect | coder | reviewer | assistant | ...（可自定义）
System prompt    该 agent 的"人格"和专长描述
工具白名单        可以调用哪些工具（read_file / write_file / bash / search / ...）
约束列表          每条约束 + 处置方式（auto_terminate / suspend_wait）
首选模型          opus / sonnet / haiku / deepseek / ... 按角色需求选
记忆范围          该 agent 能看到哪些 L2 记忆（按 workspace + role + tag 过滤）
```

### 内置 Profile 示例

| Profile | 擅长 | 禁止 | 首选模型 |
|---|---|---|---|
| architect | 系统设计、技术选型 | 直接写代码、git 操作 | opus（深度推理） |
| coder | 实现代码、写测试 | git push（需审查） | sonnet |
| reviewer | 代码审查、安全检查 | 写文件（只读） | **不同厂商**（防共同盲点） |
| assistant | 信息整理、搜索、提醒 | 代码执行 | haiku（速度优先） |

reviewer 刻意使用与 coder 不同厂商的模型：Claude 监察 Claude 可能有共同盲点，
用 Gemini 或 GPT 检查 Claude 的行为，失败模式不同，更难被绕过。

### Profile 版本管理

每次修改 Profile 生成新版本（版本号 + 时间戳）。每个 session 记录使用的 Profile 版本。
这解决"为什么上周 agent 做了这个决定"的可追溯问题。

---

## 模块三：任务编排

**职责**：控制面。拆解任务、分配给 agent、管理生命周期、汇总已验证 Artifact；自身不直接调用模型。

运行实体固定为：Task（业务目标）→ Session（长期逻辑会话）→ Invocation（一次激活）→ Exchange（一次模型调用）。
编排层只通过 WorkerClient 启动/驱动 Session/Invocation，所有需要模型推理的 planner、reviewer、synthesizer
都必须是受 Profile、预算、Gateway 观测和 Evidence 约束的 Agent Session。

### 任务拆解

所有任务先经过 planner agent 分析，决定：
- 单 agent 直接执行（简单任务）
- 并行扇出（多个 agent 独立做，汇总结果）
- 树形委托（A 分配给 B，B 可以再分配给 C）

**快速通道**：日常助理类任务（提醒、搜索、简短问答）跳过 planner，直接给 assistant profile，目标 1-2 秒响应。触发方式：消息前缀 `!` 或 `/q` 显式声明，例如 `! 提醒我三点打电话`。不做自动判断——系统猜消息类型必然误判，用户明确声明最可靠。

### 约束与监察

监察 agent 只做**约束检查**，不做质量判断：

```
约束检查（客观，可靠）：这个操作有没有超出白名单？
质量判断（主观，不可靠）：这个方案好不好？← AI 会说"非常好！"
```

处置方式：
- `auto_terminate`：违反约束 → 立刻停 → 进 Inbox 留记录（事后通知）
- `suspend_wait`：违反约束 → 挂起 → 推通知 → 等你批准（实时审批）

**原则**：高风险不可逆操作（git push、删文件、外部付费 API）默认 `auto_terminate`，而不是 `suspend_wait`。原因：挂起等批准的等待时间里任务已经停止，结果与直接终止相同，多一步操作没有意义；真正需要你批准的是"发布前确认"这类场景，明确配置 `suspend_wait`。

**防通知疲劳**：同一类约束在 24 小时内触发超过 3 次，向你发一条"建议调整 agent 配置"的提示，**不自动改变处置方式**。系统偷偷改行为会导致信任丧失——用户不明白为什么任务突然开始被直接终止而不是等待批准。

### Planner 反馈回路

任务完成后，评估 agent 给这次拆解打分，分数和拆法进 L2 记忆。下次 planner 遇到类似任务，检索到历史拆法作为参考。不做这个，planner 永远不进化。

### Undo 机制

agent 每次调用工具前，编排层记一条操作日志，对于文件写入操作保存操作前的内容快照。任务被 `auto_terminate` 后，Inbox 条目里包含"需要手动撤销的操作清单"。不做自动回滚（复杂且容易出错），但提供清单让你知道要手动撤销什么。

---

## 模块四：触发层

**职责**：把所有外部输入统一转换成 Task 对象，编排层不关心任务从哪来。

```
来源                   转换
─────────────────────────────────────────
飞书消息          →    Task（立即 / 快速通道）
Telegram 命令     →    Task（立即 / 快速通道）
cron 表达式       →    Task（定时触发）
webhook           →    Task（事件触发）
Web UI 手动       →    Task（手动触发）
```

### 飞书：双重身份（触发层 + 工具面）

飞书不只是触发层，也是 agent 可调用的**工具面**。两者分开设计：

**触发层（消息进来）**
- 群聊消息过滤：只响应 @mention、疑问句、显式请求动词（"帮我…"、"查一下…"）
- 不符合的消息静默忽略，不触发任何任务
- 直接消息（DM）：默认全部响应
- WebSocket 长连接模式（不需要公网 IP）

**工具面（agent 调用飞书）**
以下能力作为 MCP 工具注入给相关 agent，让 agent 直接写飞书而不是把结果停在 Hearth：
```
feishu_doc_write      写/更新飞书文档
feishu_bitable_append 向多维表格追加记录
feishu_wiki_search    搜索知识库
feishu_drive_upload   上传文件到云盘
feishu_send_message   发消息给指定人/群
```

这些工具只注入有需要的 agent（如 assistant profile），不是所有 agent 都有。

### Inbox → IM 主动推送

Inbox 条目创建时，根据类型决定是否同时推 IM 通知：

```
notifyChannels 配置（每种 Inbox 类型可配）：
  budget_exceeded   → ['feishu', 'telegram']   ← 预算告警必须主动推
  approval          → ['feishu', 'telegram']   ← 等你批准的必须主动推
  task_failed       → ['feishu']               ← 失败通知推飞书
  result_ready      → []                       ← 查 Web UI 就行
```

原因：用户不一定在盯 Web UI，但通常在看 IM。预算 80% 告警如果只进 Inbox，
可能等用户发现时已经超支。



## 模块五：Artifact 存储

**职责**：保存 agent 的产出物，支持跨 session、跨 agent 引用。

这是记忆模块没有覆盖的部分：
- 记忆 = 学到了什么（抽象的、经过提炼的）
- Artifact = 做出了什么（具体的产物）

```
Artifact 类型：
  code_change    代码变更（含 diff）
  document       文档、分析报告、设计方案
  test_result    测试运行结果
  screenshot     截图
  plan           任务拆解方案（planner 的输出）
  review         审查报告（reviewer 的输出）
```

A2A 消息传递上下文的方式：
```
agent A 产出 → 存为 Artifact（带 artifactId）
A2A 消息携带 artifactId 而不是内容本身
agent B 按需拉取 Artifact 内容
```

这样避免了两个极端：
- 只传文字摘要 → 信息丢失
- 传完整对话历史 → B 的上下文被撑爆

---

## 模块六：记忆与知识库

**职责**：把可复用、不可从当前代码重新推导的高价值信息提炼为 L2 记忆卡片；不替代 CLI 当前上下文，也不把全量转录无差别向量化。

详见 [06-memory.md](./06-memory.md)。

关于自进化（Hermes 式）：不做 agent 自动生成 skill。进化通过记忆系统实现——纠错记忆、偏好记忆让 agent 越用越好。Skill 保持小而精，经过人工审查后添加，不膨胀。

---

## 模块七：Worker

**职责**：在目标机器上启动 agent 进程，上报状态。通过 `WorkerClient` 接口对外暴露，隔离本机和远机的实现差异。

```
WorkerClient（接口）
  LocalWorkerClient   ProcessBuilder，M1 实现
  RemoteWorkerClient  WebSocket，M2 实现
```

Worker 守护进程（`hearth-worker` 独立启动）职责：
- 向中心注册（workerId、version、capabilities）
- 保持 WebSocket 长连接，等待 launch/cancel 指令
- 每 30s 发心跳
- 重连后上报本地存活 session 列表（状态对账）

详见 `docs/12-worker.md`。

---

## 七个模块的依赖关系

```
触发层
  └→ 任务编排
       ├→ Agent 管理（查 Profile、版本）
       ├→ Worker（通过 WorkerClient 接口启动 agent）
       ├→ 网关（注入 BASE_URL，观测流量）
       ├→ Artifact 存储（保存产出、传递上下文）
       └→ 记忆（提炼结果、查历史拆法）
```

没有循环依赖。记忆模块是纯写入+查询，不主动触发任何操作。
Worker 模块只通过 WorkerClient 接口对外暴露，核心模块不依赖具体实现。
