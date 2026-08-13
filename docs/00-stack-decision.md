# ADR-001：技术栈选型 —— Java / Spring，以及 Spring AI 的正确定位

**状态**：已采纳
**日期**：2026-08-06

## 决定

后端用 **Java 21 + Spring Boot 4.0.7 + Spring MVC + 虚拟线程**。Spring AI 引入，但**限定在特定模块**，
不作为平台骨架。

---

## 关键澄清：Spring AI 不是这个平台的骨架

这是选型时最容易搞错的一点，值得先讲清楚。

先问一个问题：**在 Hearth 里，谁在调模型？**

```
❌ 常见误解
   Hearth ──[Spring AI ChatClient]──> 模型

✅ 实际情况
   Claude Code ──> Hearth(网关，透传) ──> 模型
                       ↑
              我们是中间人，不是调用方
```

调模型的是 Claude Code / Codex / Gemini，**不是我们的应用**。我们的应用是
**代理 + 编排器**。所以在网关这条主链路上，Spring AI 没有位置——甚至用了是**有害的**：

> Spring AI 的 `ChatClient` 会把请求反序列化成它的统一抽象再重新序列化。
> 而网关的核心要求是**字节级透传保真**。一旦经过 ChatClient，上游 API 新增的字段
> 会被静默丢弃，`prompt_caching`、`thinking`、各家私有扩展全部会坏。
> 网关必须处理**它不认识的 JSON**，这与 Spring AI 的设计目标正好相反。

### Spring AI 真正该用的地方（约占系统 15%）

| 模块 | 用 Spring AI 的什么 | 为什么 |
|---|---|---|
| 记忆提炼管线 | `ChatClient` | 这里**是我们自己调模型**，且要频繁换便宜模型，抽象层有价值 |
| L2 记忆检索 | `VectorStore` (pgvector) | 现成的 pgvector 集成，省掉一堆胶水 |
| 语义环检测 | `EmbeddingModel` | 检测 agent 在打转但没进展 |
| 记忆冲突检测 | `ChatClient` + 结构化输出 | 判断两条记忆是否矛盾 |
| 平台工具暴露 | Spring AI MCP Server | 把 Hearth 的能力反向暴露给 agent 用 |

一句话：**Spring AI 用在「平台自己需要智能」的地方，不用在「转发别人的智能」的地方。**

---

## 为什么 Java 对这个项目反而更合适

我原本按 TS 起的架子，逐模块重新评估后，Java 在大多数模块上更强：

| 模块 | Java/Spring | Node/TS | 结论 |
|---|---|---|---|
| 网关（反向代理 + 流式透传） | Spring MVC + 虚拟线程，同步 IO 可调试 | 手写 `tee()` + 背压 | **Java 更强** |
| 编排 / 预算账本 / 任务状态机 | JPA 事务、`@Transactional` 天然保证 | 手写事务边界，易错 | **Java 明显更强** |
| 定时任务 | Quartz + ShedLock，成熟到无聊 | node-cron 生态薄，分布式锁要自己搞 | **Java 明显更强** |
| 记忆存储与检索 | Spring AI VectorStore + pgvector | 各种 SDK 拼装 | **Java 更强** |
| 进程管理（驱动 CLI） | `ProcessBuilder` + 虚拟线程 | `child_process` 更顺手 | **TS 略强** |
| 各家 Agent SDK | **无官方 Java SDK** | Claude Agent SDK / opencode SDK 都是 TS | **TS 明显更强** |
| 前端 | React（与后端语言无关） | 同左 | 平手 |

只有一个模块 Java 明显吃亏：**Agent SDK**。下面单独说。

---

## 唯一的真实风险：适配器层没有 Java SDK

Claude Agent SDK、opencode SDK、Gemini ACP 都只有 TS/Python 版本。

**缓解方案：不用 SDK，走 subprocess + ndjson。**

```java
new ProcessBuilder("claude", "-p",
        "--output-format", "stream-json",
        "--input-format", "stream-json", "--verbose")
```

每个 CLI 都提供了机器可读的 stdout 协议，这是它们的**公开契约**，
比 SDK 的内部 API 更稳定。配合 Java 21 虚拟线程，每个 agent 进程用一根虚拟线程
做阻塞式读取，代码比响应式回调更直白。

**这甚至可以算优点**：不绑定任何厂商 SDK，接 `openclaw` / `hermes` 时
不需要等对方出 Java 库——只要它能吐 ndjson 就能接。

代价是要自己维护解析逻辑。对策：每个 CLI 录一套黄金样本进 CI，升级前先跑回归。

---

## 网关实现的两个硬约束

### 1. 请求体可以缓冲，响应体不能整段聚合

这个区分让实现简单很多：

```
请求  一次性 JSON POST，需要完整读取才能抽 system prompt / 改写 model
      → 小请求内存缓冲，大请求写权限受限临时文件；始终有硬上限
响应  SSE 流，agent 逐 token 等着显示
      → 必须逐事件透传，禁止攒完整响应；M1 验收附加 p95 TTFB ≤ 50ms
```

### 2. 不要用 WebFlux，也不要用 Spring Cloud Gateway 的 body filter

Spring Cloud Gateway 的 `ModifyResponseBodyGatewayFilter` 会把响应体聚合成
完整对象再处理，SSE 会被卡住（这是社区里反复出现的问题）。

WebFlux 的 `DataBuffer` 生命周期管理也是陷阱：retain/release 一旦失配，内存泄漏
且不报错，只是 JVM 慢慢涨直到 OOM；响应式链上的异常栈是假的，调试成本极高。

**采用**：Spring Boot 虚拟线程 + 裸 `HttpServletRequest` / `HttpServletResponse`。
代码是同步的：读请求 `InputStream`，写响应 `OutputStream`，每步清晰可调试。
同时写到录制缓冲区，录制用独立虚拟线程 + 有界队列，队列满时丢弃而不是阻塞转发侧。

```java
// 伪代码，说明思路：逐块转发 + 旁路录制
byte[] buf = new byte[8192];
int n;
while ((n = upstream.read(buf)) != -1) {
    clientOut.write(buf, 0, n);        // 立即转发，不等录制
    recorder.offerLossy(buf, 0, n);    // 异步录制，满则丢
}
```

“字节级透传”主要约束响应流和未知字段保留，并不表示改写 `model` 后请求字节完全相同。请求解析必须使用
保留未知 JSON 字段的 tree/stream 方式，只修改明确允许的字段；同时保存脱敏后的原始请求录制。M1 默认
请求硬上限 32 MiB，超过 1 MiB 不留在单个 heap byte array，而是 spool 到 0600 临时文件，转发完成后删除。

---

## 最终技术栈

```
语言       Java 21（虚拟线程；升 25 LTS 时无痛）
框架       Spring Boot 4.0.7（3.5.x 已于 2026-06-30 EOL，不能用）
           └─ Spring MVC + 虚拟线程   网关（同步 IO，可调试）
           └─ Spring MVC             管理 API
           └─ Modulith               模块化单体，强制模块边界
AI 能力    Spring AI 2.0.0 —— 配套 Boot 4，仅用于记忆管线 / pgvector / MCP Server
存储       PostgreSQL 16 + pgvector（关系 + JSONB 事件 + 向量一把梭）
调度       Quartz（Boot 4 BOM 管理）+ ShedLock 7.7.0
事件       Spring Modulith Events
构建       Maven 3.8.7（满足 Boot 4 要求的 3.6.3+，不需要升级）
前端       React + xyflow（调用图可视化）
```

**⚠️ Spring AI 2.0 有 breaking changes**：artifact 名称变了、Jackson 2→3、MCP transport 变了、工具注册方式变了。
开始写代码前必须读 `docs/10-dependencies.md`。

---

## 被否决的方案

- **纯 TS 单体**：编排/事务/调度这三块要重造 Spring 已有的轮子，且你不熟
- **Java 编排 + TS 适配器（双语言）**：适配器确实 TS 更好，但为一个模块引入
  跨语言 IPC、双套构建、双套部署，不划算。subprocess+ndjson 已经够用
- **Spring AI 做网关**：见上文，会破坏透传保真度
