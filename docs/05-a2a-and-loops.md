# A2A 与防套娃

## 为什么 max_depth 不够

深度上限是最容易想到的方案，也是最容易被绕过的：

- `A → B → C → A` 是环，但深度只有 3，不触发上限
- 深度 3 的 fan-out 如果每层扇出 5，就是 125 个 agent，深度检查全部通过
- 真正的危害不是「层数深」，而是**烧钱**和**不收敛**

所以 Hearth 用五层防线，深度只是其中最弱的一层。

---

## 防线 1：消息出生证

每条 A2A 消息强制携带 `TraceContext`，无法伪造（由 orchestrator 签发，不由 agent 填写）：

```java
record TraceContext(
    String traceId,      // 整个任务树共享
    String spanId,       // 本条消息
    String parentSpanId, // 谁派生的我（root 为 null）
    int    depth,        // 距离根任务的跳数
    String budgetNodeId, // 见防线 3
    List<String> ancestorSessionIds  // 存 sessionId，不存 role name
)
```

**关键**：agent 不能自己构造 A2A 消息直接投递，必须经过 orchestrator 的
`dispatch()`，由后者注入 trace 上下文。这是所有防线的前提——如果 agent 能伪造
trace，后面四层全部失效。

祖先链存 **sessionId**，不存 role name。原因：循环的定义是"同一个 session 被重复激活"，
而不是"同一种角色被重复使用"。存 role name 会把 `architect-A 派给 architect-B 审查` 这种
合理的同角色协作也误判为环。

---

## 防线 2：调用图环检测

### 两种目标语义必须分开

```text
spawn_child(targetProfileId/spawnRole)   创建新 Session；新 ID 不可能已在祖先链
send_existing(targetSessionId)           激活已有 Session；可以做祖先 sessionId 环检测
```

角色只用于 Profile 路由，不能冒充稳定身份。`send_existing` 时若 `targetSessionId` 已在祖先链则拒绝
`request`，可按规则降级 `consult`；`spawn_child` 不做无意义的 sessionId 环检查，而由预算几何衰减、
深度、profile pair 往返次数、message cap、重复 action fingerprint 和 checkpoint progress 共同限制。

拒绝时不是静默失败，而是返回一个结构化错误给发起方，让它知道该自己解决：

```json
{ "error": "a2a.cycle_detected", "ancestorChain": ["session-A", "session-B", "session-A"] }
```

**降级而非硬拒**：可以允许 `consult` 类型穿过环（见防线 4），因为 consult 不会
再派生，不构成无限递归。这让「A 问 B，B 需要回头确认 A 的一个细节」这种合理场景不被误杀。

---

## 防线 3：预算继承而非重置 ← 最本质的一条

套娃真正的危害是资源耗尽。预算约束比深度约束更贴近本质，且对未知拓扑同样有效。

```
父任务 budget: { tokens: 500k, wallMs: 30min, usd: 5.00 }
  ├─ 派生子任务 → 从父预算中【划拨】，不是重新发一份
  │    子任务 budget: { tokens: 150k, ... }   父剩余: { tokens: 350k, ... }
  └─ 子任务耗尽自己的份额 → 挂起，向父申请追加
       父同意则再划拨（父自己也会减少），父拒绝则子任务必须收敛输出
```

实现要点：

- 预算是**树形账本**，不是每个 agent 一个计数器
- 划拨策略可配：`fixed`（固定额度）/ `ratio`（父剩余的 N%）/ `elastic`（先给小额，按需追加）
- 推荐默认 `ratio: 0.3` —— 天然形成几何衰减，无需显式深度限制就会收敛
- 网关是唯一可信的计量点（它看得到真实 token），控制面的估算只做参考

> 用 `ratio` 划拨时，第 N 层能拿到的预算是 `0.3^N`。到第 5 层只剩 0.24%，
> 系统会自然停下来——这比硬编码 `max_depth=5` 更平滑，也更难被特殊拓扑绕过。

---

## 防线 4：消息类型分级与优雅降级

```
request   可再派生。完整的任务委托。
consult   【不可再派生】必须用自己已有的上下文直接回答。
notify    单向通知，不期待回复，不可派生。
```

深度或预算逼近阈值时，orchestrator **自动把 request 降级为 consult**，而不是直接
截断任务。效果是：agent 仍能拿到它需要的信息，但协作树停止生长。

这是「优雅收敛」而非「硬性失败」——后者会让任务半途而废，产出垃圾结果。

---

## 防线 5：人类断路器

触发以下任一条件，任务挂起并进 Inbox 等人工裁决：

- 预算超出根任务额度的阈值（默认 80% 预警，100% 挂起）
- 同一 `traceId` 内 A2A 消息数超过上限（默认 200）
- 同一对 agent 之间往返超过 K 次（默认 K=4）——M2 可用
- 「语义环」检测：连续 N 轮消息 embedding 相似度过高——**M3 才可用**（依赖 pgvector + EmbeddingModel）

最后两条都是防「非结构性死循环」——图上无环、预算未尽，但两个 agent 在礼貌地
互相推诿。**M2 阶段语义环检测不可用，往返次数上限是唯一的非结构性防线**，
默认值设为 K=4。

---

## Agent Council：并行共识模式（特定场景）

适用场景：代码审查、方案评估——需要多个独立视角，而不是流水线协作。

```
普通流水线（现有）：
  architect → coder → reviewer（线性，后者看前者的输出）

Council 模式（新增）：
  同一个 diff / 方案
  ├→ claude-reviewer（Claude，安全视角）  ─┐
  ├→ gemini-reviewer（Gemini，性能视角）   ├→ Orchestrator 合并意见 → 汇总报告
  └→ codex-reviewer（Codex，逻辑视角）  ──┘
  三者互相看不到对方的结论，最后 Orchestrator 合并
```

**触发方式**：在 Task 里声明 `reviewMode: "council"`，planner 自动派发
同一 artifactId 给多个 reviewer profile，每个用不同模型。

**合并逻辑**：Orchestrator 等所有 reviewer 的 `hearth_save_artifact` 都到达后，派发一个受预算、
Profile、Session、Gateway 观测和 Evidence 约束的 `synthesizer` Invocation。synthesizer 读取三份报告并
保存汇总 Artifact，标注哪些问题被多人发现、哪些是单一视角。Orchestrator 只验证状态和 Artifact，
禁止自己通过 Spring AI ChatClient 或任意模型调用做汇总。

**不是所有任务都需要 Council**：Council 模式成本是单 reviewer 的 3 倍，
只在 planner 判断"高风险变更"或任务显式声明时启用。

---



结构性防线检测的是"图上有没有环"，行为异常检测的是"有没有在无效循环"——
两者都需要，解决不同的问题。

```
异常类型 1：Token 膨胀
  检测：某 session 在单次任务中 context 增长速率 > 基线 3 倍
  判断：用 exchange 表的 input_tokens 序列做趋势检测，简单线性回归即可
  处置：soft alert → 继续；膨胀持续 3 次 exchange → 进 Inbox

异常类型 2：重复 Prompt
  检测：当前请求与同 traceId 内历史 exchange 的 prompt 相似度 > 85%
  判断：用字符串相似度（Levenshtein 或 MinHash），不需要 embedding
  处置：连续 2 次重复 → 进 Inbox（"agent 可能在兜圈"）

异常类型 3：工具失败率突升
  检测：同一工具在同一 session 内连续失败 >= 3 次
  判断：检查 operation_log 的工具调用结果
  处置：auto_terminate 并在 Inbox 标注具体工具名和错误
```

这三种检测在 Orchestrator 的事件流上直接算，不需要 pgvector，M2 就能做。
它们和往返次数上限（K=4）是互补的：K 限制是静态阈值，行为检测是动态的。

---



一个真实任务会产生几百条 A2A 消息。平铺的时间线等于没有可观测性。

```
L1  甘特图     谁在什么时间窗口干活，并行度一目了然，用于「现在卡在哪」
L2  调用图     xyflow 画 DAG，节点=span，边=A2A 消息，配色=状态/成本
L3  消息详情   单条消息的完整 payload + 对应的 gateway exchange（system prompt 全文）
```

外加一个**默认开启**的过滤器：「只看需要人类决策的点」。日常应该看这个视图，
另外三层是排查时才下钻。

---

## 对外协议

A2A 消息格式对齐 [Google A2A spec](https://github.com/google/A2A)，不自己发明。
理由：未来接 openclaw / hermes 这类外部 agent 时，对方大概率已经支持 A2A；
自定义协议等于给每个新接入方增加一层翻译成本。

Hearth 的 `TraceContext` 作为 A2A 消息的 metadata 扩展字段携带，不破坏协议兼容性。
