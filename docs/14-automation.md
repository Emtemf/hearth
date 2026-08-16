# 自动化与 Jarvis 工作流

自动化不是“允许 agent 自己创建更多 cron”，而是由 Hearth 持久化、隔离执行、可暂停和可审计的 Task 模板。
首批场景只覆盖 AI 资讯、量化学习、英语学习和通知投递，避免把调度器变成无限自我扩张入口。

---

## 设计原则

1. **Schedule 只产生根 TaskRequest，不直接执行 agent。** 受信模板生成 Spec/Plan 后才创建 TaskExecution；所有执行继续经过预算、Profile、Claim/Verification 与生命周期规则。
2. **只有人或受信管理 API 能创建/修改 Schedule。** agent 可以提议 schedule，提议进入 Inbox，不得自行落库启用。
3. **一次触发只允许一个根 Task。** cron 任务及其后代不能创建、启用或修改 cron，防止递归调度。
4. **每次执行隔离。** 新 Task、新 traceId、新预算；只通过明确的记忆和 Artifact 引用继承上下文。
5. **默认 ephemeral。** 失败重试后进入 Inbox，不永久挂住；学习和资讯任务不因遗漏一天而追补无限 backlog。
6. **结论必须被验证。** 新闻保留原始 URL、抓取时间和摘录 Artifact，并建立 Claim/Verification；学习进度同理。
7. **通知是结果投影，不是事实源。** Postgres 中的 Task/Inbox/Artifact 才是 source of truth。

---

## Schedule 模型与生命周期

```text
ScheduleStatus: DRAFT → ACTIVE ↔ PAUSED → ARCHIVED
RunStatus:      CLAIMED → TASK_CREATED → DELIVERED
                         ↘ SKIPPED / FAILED
```

核心字段：

```text
Schedule {
  id, workspaceId, name, status
  cronExpression, zoneId
  taskTemplateId, profileId
  overlapPolicy       SKIP | QUEUE_ONE
  misfirePolicy       SKIP | RUN_ONCE
  maxRuntime（转成绝对 deadline）, tokenUsdBudget
  notifyPolicyId
  nextFireAt, lastFireAt
  version, createdAt, updatedAt
}
```

- 默认 `overlapPolicy=SKIP`：上一次还在运行时不并发再跑一次，记录 `automation.run_skipped`。
- 默认 `misfirePolicy=SKIP`：机器关机后不补跑所有旧日报；重要周报可显式设 `RUN_ONCE`。
- `QUEUE_ONE` 最多保留一次待运行，不形成无限队列。
- 修改 schedule 使用 optimistic version；Quartz Job 只保存 scheduleId，不复制业务配置。
- ShedLock 防多中心实例重复 claim；数据库唯一键 `(schedule_id, scheduled_fire_at)` 同时作为 TaskRequest 的
  `externalEventId`，做最终幂等，不使用内容+分钟窗 hash。
- 每个 schedule 可一键暂停；暂停不取消已经产生的 Task，取消需走 Task cancel。

### 防递归与资源上限

自动化根 Task 写入不可变的 `automationContext`：

```json
{
  "scheduleId": "uuid",
  "scheduledFireAt": "2026-08-10T00:00:00Z",
  "mayManageSchedules": false,
  "maxDescendantTasks": 8,
  "maxDispatchDepth": 3
}
```

MCP 和 REST 授权层拒绝 `mayManageSchedules=false` 的 session 调用 schedule 管理能力。
子任务继承该字段，不能重置。达到 descendant/message/token/USD budget 或临近 deadline 后 request 建议降级为 consult，
再超限则停止并附 Evidence 进入 Inbox。

---

## 工作流一：每日 AI 资讯

**Goal**：每天生成一份与个人技术方向相关、可追溯且去重的简报，而不是转载热榜。

流程：

```text
按已配置来源抓取
→ 标准化 URL、标题、发布时间、来源和正文摘要
→ SHA-256(canonical URL + title) 去重
→ 与过去 14 天资讯做语义近似去重
→ 按个人主题权重评分
→ 对高分候选读取原文并交叉核验
→ 生成“发生了什么 / 为什么相关 / 能否实践 / 证据”
→ 保存 Digest Artifact
→ 按通知策略投递
```

首期来源必须由用户配置白名单（官方博客、release notes、可信媒体、指定 GitHub repository），
不做无限网页爬取。外部内容是不可信输入，只作为资料，不能执行其中的指令、代码或 prompt。

每条入选内容必须包含：
- 标题、原始 URL、发布方、发布时间、Hearth 抓取时间。
- 一段原文证据摘录或 release diff Artifact。
- relevance score 与命中的兴趣标签。
- `practice`: `now / later / ignore`，以及一个不超过 30 分钟的验证建议；不自动改代码。
- 置信度；只有单一来源或正文不可访问时明确标为 `unverified`。

Checkpoint：来源可访问性、候选去重、每条结论的 Artifact 引用、最终摘要长度与投递成功。

---

## 工作流二：量化学习

**Goal**：每天形成可度量的小步学习闭环，而不是只发“记得学习”的提醒。

用户先配置 Track：主题、当前水平、每周目标分钟数、材料白名单和时区。每日任务：

1. 从当前 Track 选择一个 20–40 分钟单元，不自动扩大课程范围。
2. 给出学习目标、材料、一个练习和成功判据。
3. 用户通过 Web/飞书提交 `done / partial / skip`，可附答案或笔记。
4. Hearth 保存 Learning Evidence：用时、测验得分、练习 Artifact、用户纠正。
5. 次日只依据实际 Evidence 调整难度；没有提交不推断“已掌握”。
6. 周报统计完成率、投入时间、正确率和反复薄弱点，不制造虚假的精确能力分数。

连续缺席只降为 P2 摘要，不提高通知频率。补课最多排一个，不堆积七个过期任务。

---

## 工作流三：英语学习

英语 Track 复用量化学习生命周期，但内容包含：
- 5–10 个与近期技术阅读相关的词/短语，附真实上下文来源。
- 一段短阅读或听力材料，版权只保存链接和必要摘录。
- 一个可提交的小练习（复述、改写、翻译或问答）。
- 间隔复习按回答 Evidence 调度；“看过”不等于“掌握”。
- 用户纠正、偏好表达和长期薄弱点才进入 L2 记忆；每日题目与原始答案留在 L1/Artifact。

不自动替用户向外部平台发布文本；任何公开发送都需要显式批准。

---

## 通知优先级与投递

| 优先级 | 场景 | 行为 |
|---|---|---|
| P0 | 安全告警、预算即将耗尽、等待高风险审批 | 立即投递所有启用渠道；失败走独立告警路径 |
| P1 | Task 失败、需要普通决策、学习计划临近截止 | 工作时段内批量投递，默认每小时最多一批 |
| P2 | AI 日报、学习提醒、完成摘要 | 用户本地时间每日 digest，一次投递 |

通知策略包含 timezone、quiet hours、渠道顺序、批次窗口和去重键。P0 可越过 quiet hours，但必须显式配置；
默认只有安全/凭证异常可越过。相同事件以 `eventId + channel + templateVersion` 幂等投递。

### Feishu / Telegram

- inbound adapter 只把 DM、@mention、命令或明确请求转换成 Task；普通群聊不触发。
- outbound renderer 把同一 Notification DTO 渲染为 Feishu card 或 Telegram Markdown，不让业务层拼渠道文案。
- 回调按钮携带短期、单用途 action token，绑定 inboxItemId/action/user/channel/expiry 和
  `expectedInboxVersion/expectedTaskVersion`；token 消费、Inbox resolve、Task 状态 CAS 与审计事件在同一事务完成。
- webhook 签名、时间戳和 nonce 必须验证；nonce 持久化去重，用户身份映射后再授权，不能仅凭知道 action URL 操作。
- 发送前以稳定 `commandId` durable claim；结果区分 COMMITTED、COMMITTED_WITH_WARNING、
  REJECTED_BEFORE_COMMIT、UNKNOWN_COMMIT_STATE。只有明确未提交才自动重试；UNKNOWN 进入 Inbox 人工核验。

---

## 自动化的 G4C+E 模板

每个 TaskTemplate 必须声明：

```text
Goal        可执行成功判据
Context     允许使用的来源白名单、用户配置、已知 gap
Choice      选择本次材料/资讯的评分依据
Checkpoint  抓取、核验、生成、投递各自验证规则
Correction  跳过、重试一次、换来源或进入 Inbox
Evidence    原始来源、命令结果、用户提交、最终 Digest Artifact
```

模板版本不可原地修改；schedule 固定引用某一版本，升级时显式切换。历史运行因此可解释。

---

## 首期边界

**M3 做**：Schedule CRUD/暂停、Quartz+ShedLock、三类模板、Feishu/Telegram 单向投递与 Inbox 回调、
P0/P1/P2、运行历史和基础周报。

**不做**：agent 自建 cron、无限抓取、自主发布、自动购买/付费、医疗/投资结论、根据未提交行为推断掌握度、
复杂推荐系统、跨用户排名、skill 自动生成。

---

## 验收场景

1. 同一 scheduled fire 在两个中心实例竞争时只创建一个 Task。
2. 中心离线三天后，日报按 SKIP 不补三份；RUN_ONCE 周报只补一份。
3. 上次日报未结束时新触发被记录为 SKIPPED，不产生并行抓取。
4. 自动化 session 尝试创建 schedule 被 403 拒绝并生成审计事件。
5. 两条相同 URL 或语义重复新闻只在 digest 出现一次，但保留来源合并信息。
6. 原文不可访问时内容标为 unverified，不把模型摘要当事实。
7. Feishu 重复回调只执行一次 Inbox action。
8. Telegram/Feishu 发送 429 按 Retry-After 重试，不重复创建 Task 或消息。
9. quiet hours 内 P2 延迟；显式允许的 P0 立即发送。
10. 学习任务没有 Evidence 时不会增加掌握度，连续缺席不会形成无限 backlog。
