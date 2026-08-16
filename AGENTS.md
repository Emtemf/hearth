# Hearth

## 愿景

多 agent 编排平台。把 Claude Code / Codex / Gemini / opencode 的流量过一个自建网关，
实现 system prompt 观测、会话级模型路由、多 agent 协作、记忆积累、蜂窝式多机协同。
长期目标：Jarvis 式个人 AI 助理，从任意设备派活，agent 自动执行，需要决策时推通知。

---

## Goal（什么叫做完）

### M1 验收（预计 3-5 周，以验收通过为准）
```
BDD: Given 本地启动 Hearth 网关，
     When  用注入了 ANTHROPIC_BASE_URL 的脚本打开 Claude Code 并完成至少两次 invocation，
     Then  curl http://127.0.0.1:4517/actuator/health 返回 {"status":"UP"}
           Web UI 里能看到这次 session 的 system prompt 全文、完整对话、token 用量，
           浏览器或中心进程重连后状态与 Postgres 快照自动收敛
```

### M2 验收（再 4-6 周）
```
BDD: Given 两台机器各运行 Hearth Worker（或单机两个 Worker 模拟），且验收取消时目标 Worker ONLINE、控制通道可达，
     When  通过 Web UI 分配一个真实编码任务，
     Then  architect 和 coder 两个 agent 自动协作完成，
           Web UI 调用图能看到 Internal Dispatch（外部边界可投影 A2A）、每个 agent 的 system prompt、预算消耗，
           套娃场景被正确拦截（环检测 + 预算耗尽），
           手动取消任务在 10s 内生效
```

### M3 验收（再 6-8 周）
```
BDD: Given 飞书 Bot 已配置，
     When  发送"! 提醒我明天上午十点检查服务器"，
     Then  1-2 秒内收到确认回复；
     When  发送一个编码任务，
     Then  agent 自动执行，需要决策时推飞书通知，完成后结果回飞书
```

---

## 技术栈（已定，不重新讨论）

```
语言      Java 21，虚拟线程（spring.threads.virtual.enabled=true）
框架      Spring Boot 4.0.7（3.5.x 已于 2026-06-30 EOL，禁止使用）
          Spring MVC + 虚拟线程（禁止 WebFlux / Reactor / DataBuffer）
AI        Spring AI 2.0.0（配套 Boot 4）
          仅用于：记忆提炼 / pgvector 检索 / MCP Server 暴露工具
          禁止用 ChatClient 做网关（破坏字节级透传）
存储      PostgreSQL 16 + pgvector（主存）
          Redis（队列/锁/临时状态，禁止做主存）
调度      Quartz + ShedLock 7.7.0
事件      Spring Modulith Events
构建      Maven 3.8.7，多模块
前端      React 19.2.8 + Vite 6.4.3 + TypeScript 5.7.3（strict）
          react-router 7.18.2
          @tanstack/react-query 5.101.4（服务端状态）
          zustand 5.0.14（UI 状态）
          shadcn CLI 4.16.2 + Tailwind CSS 4.3.3（禁止 MUI/Ant Design）
          @xyflow/react 12.11.2（调用图，M2 加）
          codemirror 6.0.2（system prompt 展示，M1 就要）
          源码在 frontend/ 目录，构建产物放入 hearth-api/src/main/resources/static/
          详细规范（目录结构/状态分工/命名/ESLint）见 docs/13-ui.md
代码风格  Google Java Style Guide
设计范式  DDD（领域驱动），包结构按 bounded context 分层：
          domain / application / infrastructure / interfaces
测试      JUnit 5 + AssertJ + Testcontainers，BDD 命名（given_X_when_Y_then_Z）
数据库    Flyway 迁移，主键 UUID，snake_case；可变聚合表含 created_at/updated_at(timestamptz)，
          immutable event/log 表只含 created_at
日志      结构化 JSON（Logback），MDC 注入 traceId，禁止把 system prompt 写入日志
异常      unchecked exception，DomainException vs TechnicalException 明确区分
API 响应  统一信封：{"data":...,"error":null} / {"data":null,"error":{"code":"模块.类型","message":"..."}}
```

**版本精确清单 + Spring AI 2.0 breaking changes + pom.xml 骨架见 `docs/10-dependencies.md`。**

---

## 模块边界（不能跨越）

```
触发层      → 只做：外部输入 → TaskRequest；Inbox → 推送回通讯软件
任务编排    → 只做：拆解、分配、生命周期、G4C+E 验证
Agent 管理  → 只做：Profile / 版本 / 约束 / session overlay 编译
Worker      → 只做：在本机启动 agent 进程，上报状态（通过 WorkerClient 接口）
网关        → 只做：透传 + 观测 + 模型路由，不做业务决策
Artifact    → 只做：存储和寻址，不做内容解析
记忆        → 只做：写入和检索，不主动触发任何操作
```

**WorkerClient 是接口**，`LocalWorkerClient` 通过本机受认证 transport 调用独立 `hearth-worker`，远机实现用
WebSocket/TLS。只有 Worker daemon 可以使用 `ProcessBuilder`；所有调用方只能依赖接口，不能直接启动进程。
Agent 以低权限 `hearth-agent` 身份运行，不能继承 `hearth-api` 的 provider/数据库/IM/admin secret 或读取 Worker
control credential。M1 必须用 UID、目录权限、环境变量名/hash 和 transport contract test 验证这条边界。

---

## 不做什么（防范围蔓延）

- **不用 WebFlux / Reactor / DataBuffer**：内存泄漏难调试，遇到提议直接拒绝
- **不用 Spring AI ChatClient 做网关**：破坏字节级透传，私有扩展丢失
- **不把 Redis 做主存**：崩溃丢编排状态，长任务无法恢复
- **不做 agent 自动生成 skill**：进化靠记忆系统，skill 必须人工审查后加
- **不把敏感内容放进进程 argv**：API key、token 只走环境变量，argv 在 `ps aux` 里可见
- **不做 @mention 语法路由**：协作消息走 Hearth MCP tool 显式 dispatch，@mention 解析有大量 edge case（Unicode 零宽字符、markdown 包裹、mid-line 误判），clowder-ai 踩了很多坑
- **不在 properties/yaml 里写 API key**：必须从环境变量读取
- **不在 M1/M2 做多 workspace / 多用户**：自用，单 workspace
- **不做自动回滚**：提供操作日志 + undo 清单，人来执行
- **不做 Hermes 式 skill 自动生成**：skill 膨胀失控，靠记忆系统进化
- **不 fork Pi 或把 Pi SDK 嵌入 Java 核心**：Pi 只作为 Worker 上的可选 RPC Adapter；路由、预算、Evidence 和生命周期仍由 Hearth 控制

---

## 代码规范

### 包结构（每个 bounded context）
```
ai.hearth.{context}/
  domain/          Entity, ValueObject, DomainEvent, Repository 接口
  application/     ApplicationService（用例编排，事务边界）
  infrastructure/  Repository 实现, 外部 API 调用, MQ
  interfaces/      Controller, 消息监听, Scheduler
```

### 命名约定
- Entity：名词，有 ID，有生命周期（`Task`, `Session`, `MemoryCard`）
- ValueObject：不可变 record，无 ID（`BudgetGrant`, `TraceContext`, `Observability`）
- ApplicationService：动词+名词（`LaunchSessionService`, `DispatchAgentService`）
- 禁止 Lombok：Java 21 record 已够用，避免和 Google Checkstyle 冲突

### 错误处理
- 领域规则违反 → `DomainException extends RuntimeException`
- 基础设施失败 → `TechnicalException extends RuntimeException`
- 上层统一捕获，转成 API 响应信封的 error 字段
- 禁止 swallow exception（catch 后只打日志不处理）

---

## 任务设计标准：G4C+E

G4C+E 是阶段性 invariant，不要求原始需求一出生就填满。领域对象固定为：

```
TaskRequest → TaskSpecVersion(immutable) → PlanVersion(immutable) → TaskExecution
```

`INTAKE/CLARIFYING` 允许只有 brief、已知 Context 与 gaps；进入执行前才要求 Goal/验收/约束、Choice、
Checkpoint、Correction 完整。Fact/人类要求要 SourceRef；Inference 引用 premise；Hypothesis 标记不确定性和
验证办法；Execution Claim 必须用 `EvidenceClaim + VerificationRecord` 证明。Artifact 只是材料，不等于结论。
语义检查由独立 reviewer Session 或人完成，编排层只直接运行 deterministic/composite verifier。

`escalate` 必须附带 EvidenceClaim 或失败 VerificationRecord；只有裸 Artifact ID 不处理。

详见 `docs/09-g4c.md`。

---

## 韧性约束

### 运行实体与状态真相源

```text
TaskRequest 原始需求与澄清生命周期
TaskSpec/Plan 不可变的需求与计划版本
Task        TaskExecution 运行生命周期（表名仍为 task）
Session     可跨多轮使用的逻辑 agent 会话
Invocation  一次 user turn / agent activation；每次重试创建新 attempt 或复用同一 commandId
Exchange    Invocation 内一次上游模型调用
```

- `semantic_completed`、`stream_eof`、`process_exited`、`transport_disconnected` 是不同信号，禁止互相代替
- UI 显示“正在执行”以 Invocation 状态为准，不以长期 Session 是否存活为准
- Session 恢复使用 adapter 专属、带版本的 ResumeDescriptor，禁止把裸字符串直接传给任意 CLI
- 聚合状态变更、`domain_event` 与必要 outbox 必须在同一短事务中提交；事务内禁止 Worker/provider/IM 网络 I/O
- 每个有副作用的命令必须携带稳定 `commandId`，重试复用；结果区分 committed / rejected_before_commit / unknown_commit_state
- `AWAITING_HUMAN` 是 Task 全局执行门：launch/continue/retry/resume/Dispatch request 派生均须拒绝，批准用版本 CAS

### 超时
| 场景 | 值 | 超时后 |
|---|---|---|
| 网关上游 API | 120s | 504，agent 自行重试 |
| Dispatch 消息投递 | 30s | 重试，超限死信 |
| Session 运行 | 60min（可配） | TIMED_OUT → Inbox |
| Checkpoint 等待 | 10min | 按 correction 策略 |
| Inbox ephemeral | 24h 提醒，72h 自动取消 | CANCELLED |
| Inbox durable | 24h 提醒，永不自动取消 | 等人处理 |

### 重试
指数退避 + jitter，base=1s，max=30s，最多3次。
可重试：5xx / timeout / 429。不可重试：4xx / 鉴权失败 / 业务规则违反。
429 读 `Retry-After` 头，按指定时间等。超限 → 死信 → Inbox `task_failed`。

### 幂等
Dispatch 创建：可信 MCP/Adapter 层签发/派生稳定 `commandId`，重试复用；投递端按 messageId upsert。
触发层优先使用 `(source, externalEventId)`；只有来源不提供稳定 ID 时才用带短 TTL 的 fallback fingerprint。
token/USD 预算扣除用 `SELECT FOR UPDATE`；wall time 用父子单调收紧的绝对 deadline，不作为可划拨余额。

### 任务 persistence
- `ephemeral`（默认）：超时自动取消
- `durable`：永不自动取消，`!important` 前缀或 Web UI 显式设置

### 数据与录制
- Flyway 按 slice 演进：V001/V002 只建 M1 实际结构；V003+ 再引入 Task/Dispatch/Evidence，禁止预建未验证世界
- Task 用 `parent_task_id` 持久化执行树；Goal/Context 在 immutable TaskSpecVersion，Choice/Checkpoint/Correction 在 immutable PlanVersion
- `session.task_id` / `artifact.task_id` 永久可空，支持 standalone session；M2 编排流在应用层要求非空
- 所有运行事件携带 `(sessionId, processGeneration, eventSeq)`；Worker 断线重连按最后确认序号重放
- `domain_event.event_id` 只是内部 ID，不等于提交顺序；单 publisher 在提交后分配 `event_publication.publication_seq`，它才是 UI/SSE 唯一可见顺序；cursor 过期时必须拉 REST snapshot + watermark 后再续传
- `observability_level` 表示接入机制，`recording_status` 表示实际完整度；full 也可能 partial
- Anthropic 重复发送完整 history；Transcript 按最长公共前缀和规范化 turn identity 去重，禁止按文本全局去重
- Artifact 被引用时不得普通删除；零引用删除保留 sha256 tombstone；泄密时管理员可 SECURITY_PURGE，并使关联 Verification 失效

### 安全执行边界
- API 只接受 `providerRouteId` 和 `workspaceRootId + relativeCwd`，禁止调用方提交任意 upstream URL 或绝对 cwd
- provider route 在服务端固定 wire protocol、目标 host 白名单、模型白名单和 credential reference；M1 不做跨协议翻译
- Worker 对路径 canonicalize 并拒绝 `..`、符号链接逃逸和 allowed root 之外路径
- M1 绑定 loopback 仍需随机本地管理员会话与 CSRF 防护；loopback 不是身份认证
- Artifact 默认只接收 content；未来 path 上传只能读取 session cwd 内文件
- Dispatch ExistingSession 必须通过 workspace/Task Tree/state authorization；Dispatch/Claim Artifact 必须通过 task/Evidence scope 检查
- Invocation/Worker command 持久化有效 deadline；过期后禁止 retry、reconcile 或发送新的副作用命令

### 自动化
- Schedule 只能由人或受信管理 API 创建/修改；agent 只能提交提议到 Inbox
- Schedule 只创建根 Task，自动化 Task 及其后代继承 `mayManageSchedules=false`
- 默认 overlap/misfire 为 SKIP，不补无限 backlog，不允许递归 cron

### 强制取消（命令必须持久化并最终生效）
取消信号走独立持久化 outbox 和独立线程，不受任务繁忙影响。
Task `cancellation_version` 与 Session `fencing_generation` 分离；Worker 只执行 generation 精确匹配的命令。
Worker ONLINE 且控制通道可达时，SIGTERM → 等待 8s → SIGKILL，最迟 10s 确认进程死亡。网络分区时
中心立即持久化取消并撤销 session capability，Task 保持 CANCELLING；Worker 重连后优先执行，不能伪报
10s 内已死亡。级联向下取消所有子任务防孤儿进程。

### 部分失败
默认策略：保留已成功、只重试失败、超限进 Inbox 附带全部证据，人来决定。

### 分布式追踪
所有链路传播 `traceId`：MDC 注入日志、HTTP 请求头 `X-Trace-Id`、Internal Dispatch TraceContext；外部
A2A Adapter 仅通过已协商的 versioned extension 传播 Hearth 语义。
事件顺序用提交后由单 publisher 分配的 `event_publication.publication_seq`，不用时间戳排序（多机时钟漂移）。

详见 `docs/11-resilience.md`。

---

## 蜂窝架构（多机 Worker）

每台有 agent CLI 的机器跑一个 Hearth Worker 守护进程，向中心注册，等待指令。
agent 进程的 `ANTHROPIC_BASE_URL` 指向中心网关（局域网 IP 或 Tailscale 地址）。

Worker 职责：注册 → 心跳 → 接收 launch/cancel 指令 → 上报状态对账（防裂脑）。
心跳与事件携带 `processGeneration`；控制面事件有每进程单调 `eventSeq`，Worker 持久化未确认事件并在重连后重放。
Worker 认证：每个 session 分别签发 gateway/MCP opaque 256-bit capability token，数据库只存 SHA-256；
token 绑定 `(workerId, sessionId, audience, expiresAt)`，终止时撤销。
Artifact 传输：M2 首期由 agent 通过中心 Platform MCP 按 content 上传并返回 artifactId；未来 path/大文件上传才由
Worker 在 session cwd 边界内读取并中继，中心不接受任意 path。

M1 用本机独立 Worker daemon，M2 扩展到多机，M3 用 Tailscale 跨地点。`hearth-api`、`hearth-worker`、`hearth-agent`
使用不同服务身份；Agent 只能访问允许的 workspace/overlay，不能以 Session/Artifact UUID 存在性越过 workspace、Task
Tree 或 Evidence scope 授权。Worker event 必须同时校验 `workerId + connectionId + processGeneration + launchId + eventSeq`。

**M1 必须做的一件事：所有 agent 启动通过 `WorkerClient` 接口，不直接用 `ProcessBuilder`。**

详见 `docs/12-worker.md`。

---

## 观测等级（session 级）

| 等级 | 能拿到 | 计费 |
|---|---|---|
| `full` | system prompt 全文、tools schema、全量 messages、精确 token；有版本化 pricing 时计算成本 | API |
| `sidecar` | 对话内容、工具调用、准确 token/成本（转录文件实测） | 订阅 |
| `none` | 仅生命周期事件（开始/结束/退出码） | 订阅 |

降级必须记录原因：`subscription_auth` / `oauth_only_provider` / `user_opt_out` / `gateway_unreachable`。

---

## 防套娃

1. TraceContext 由 orchestrator 签发，agent 不能自造
2. 祖先链存 **sessionId**（不是 role name）
3. 预算 ratio=0.3 继承，第5层只剩 0.24%，自然收敛
4. 临近阈值 request 自动降级为 consult（优雅收敛）
5. 超预算 / 往返超 4 次 / 消息超 200 条 → Inbox

详见 `docs/05-a2a-and-loops.md`。

---

## 安全

- API key 只从环境变量读，禁止写进任何配置文件
- 启动时验证所有必需 key，缺了拒绝启动并报错
- 网关：单机绑 `127.0.0.1`；多机验证 session capability token，并在转发前剥离内部凭证
- 上游凭证按协议重写：Anthropic 静态 key 用 `x-api-key`，禁止统一套用 Bearer
- 禁止把 system prompt 写入日志（进数据库，不进日志）
- 告警通路独立于 Inbox（Inbox 挂了也能收到告警）

---

## 参考文档

| 文档 | 内容 |
|---|---|
| `docs/00-stack-decision.md` | 技术栈 ADR，选型理由 |
| `docs/01-architecture.md` | 控制面/数据面分离，网关设计 |
| `docs/02-modules.md` | 七个模块职责与边界 |
| `docs/03-schema.md` | 数据库 Schema（按 M1/M2/M3 slice 增量 migration） |
| `docs/04-platform-mcp.md` | Hearth MCP Server 工具完整定义 |
| `docs/05-rest-api.md` | REST API 契约（Web UI ↔ 后端接口） |
| `docs/05-a2a-and-loops.md` | Hearth Internal Dispatch、A2A Adapter 与防套娃五层防线 |
| `docs/06-memory.md` | 记忆分层，提炼管线，价值判据 |
| `docs/07-roadmap.md` | M1/M2/M3 路线图与验收标准 |
| `docs/08-operations.md` | 配置管理，健康检查，部署拓扑 |
| `docs/09-g4c.md` | G4C+E 阶段 invariant、Task 版本模型与 Evidence 验证 |
| `docs/10-dependencies.md` | 精确版本清单，pom.xml 骨架，陷阱 |
| `docs/11-resilience.md` | 超时/重试/幂等/状态机/优雅停机 |
| `docs/12-worker.md` | Worker 守护进程，蜂窝架构，WorkerClient 接口 |
| `docs/13-ui.md` | 桌面端信息架构、前端规范与交互状态 |
| `docs/14-automation.md` | AI 资讯、学习工作流、Schedule 隔离与通知策略 |
| `docs/15-pi-agent-adr.md` | Pi Agent 集成决策：可选 Adapter，不作基础运行时 |
| `docs/16-spec-governance.md` | Canonical source owner、规格变更流程与 drift check |
