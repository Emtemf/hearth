# Hearth 模块边界

每个模块只做自己职责范围内的事，禁止跨模块直接调用内部实现。

## 六个模块的边界

**触发层**
- 只做：外部输入（飞书/Telegram/cron/webhook）→ Task 对象
- 只做：Inbox 条目 → 推送回通讯软件
- 禁止：持有任何业务逻辑，禁止直接操作 agent 或记忆

**任务编排**
- 只做：Task 拆解、分配、生命周期管理、G4C+E 验证
- 禁止：直接调模型（通过 agent 做，不自己调）
- 禁止：直接读写记忆（通过记忆模块的接口）

**Agent 管理**
- 只做：Profile 定义、版本管理、session overlay 编译、约束检查
- 禁止：直接调模型
- 禁止：持有任务状态

**网关**
- 只做：HTTP 透传、system prompt 抽取、模型路由、成本记录
- 禁止：任何业务决策（路由规则由 Agent 管理模块提供，网关只执行）
- 禁止：缓冲响应体

**Artifact 存储**
- 只做：存储产出物、提供按 ID 寻址的接口
- 禁止：解析 Artifact 内容（内容由调用方解析）
- 禁止：主动触发任何下游操作

**记忆**
- 只做：写入候选记忆、检索相关记忆
- 禁止：主动触发任何操作（由编排层在 session 结束后触发提炼）
- 禁止：全量 embedding 所有内容（必须经过价值打分过滤）

## G4C+E 强制要求

- 每个 Task 必须有可验证的 Goal，不接受"功能正常"这类主观判断
- Checkpoint 验证由编排层独立执行，不接受 agent 自报结论
- escalate 消息必须附带 evidenceArtifactIds，空数组不处理
- 记忆的 Context 来源必须标注（file / memory / artifact / human_stated）

## 防套娃

- TraceContext 由 orchestrator 签发，任何 agent 不得自行构造
- 祖先链存 sessionId，不存 role name
- 默认 ratio=0.3 预算继承，不得在子任务里重置预算
