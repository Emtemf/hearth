# 记忆：分层与提炼

分层是常识，难点在**提炼管线**和**价值判据**。全量 embedding 入库不是记忆系统，
是搜索引擎——它会让上下文被大量低价值内容污染，反而降低 agent 表现。

## 分层

| 层 | 内容 | 存储 | 生命周期 |
|---|---|---|---|
| L0 工作记忆 | 当前 session 上下文 | CLI 自己管，**平台不插手** | session 内 |
| L1 情节记忆 | 全量事件流 | Postgres + 对象存储 | 冷存，按 `08-operations.md` retention 归档/删除 |
| L2 语义记忆 | **高价值记忆卡片** | Postgres + pgvector | 长期，带衰减与复核 |
| L3 组织记忆 | 跨 agent 决策日志 / evidence store | Postgres | 永久 |

L0 不接管很重要：Claude Code / Codex 的上下文压缩策略是它们的核心能力，
平台去干预只会打架。平台在 L1 观测，在 L2 补充。

---

## 向量化技术栈

### Embedding 模型选型：本机优先

记忆卡片包含 system prompt 内容、工作决策、踩坑记录，发给第三方 API 是真实的隐私问题。
**默认用本机 Ollama，数据永不离开本机。**

| 模型 | 维度 | 工具 | 质量 | 内存 | 推荐场景 |
|---|---|---|---|---|---|
| `nomic-embed-text` | 768 | Ollama | 好，中英文强 | ~270MB | **默认，M3 起用** |
| `mxbai-embed-large` | 1024 | Ollama | 更好 | ~670MB | 追求更高精度时 |
| `bge-m3` | 1024 | Ollama | 中文最强 | ~1.2GB | 全中文工作流 |
| `text-embedding-3-small` | 256 | OpenAI API | 好 | 0（远程） | 无本机 GPU、不介意隐私时 |

**推荐 `nomic-embed-text`**：270MB 不占太多内存，中英文都好，Ollama 生态成熟。

### 安装

```bash
# 安装 Ollama（Linux 一行）
curl -fsSL https://ollama.com/install.sh | sh

# 拉取模型
ollama pull nomic-embed-text
```

docker-compose.yml 里也可以加 Ollama 服务，统一用 `docker compose up` 启动。

### Spring AI 2.0 配置

```properties
# 本机模式（默认）
spring.ai.ollama.base-url=http://localhost:11434
spring.ai.ollama.embedding.model=nomic-embed-text

# pgvector 维度 768（和模型输出维度一致）
spring.ai.vectorstore.pgvector.dimensions=768
spring.ai.vectorstore.pgvector.index-type=HNSW
spring.ai.vectorstore.pgvector.distance-type=COSINE_DISTANCE
spring.ai.vectorstore.pgvector.initialize-schema=true
```

### 切换提供方

Spring AI `EmbeddingModel` 接口隔离提供方——切换只改配置，不改代码：

```
本机 → OpenAI：改 application.properties，重跑全量 embedding 批处理
OpenAI → 本机：同上
```

切换时必须重建所有 memory_card 的 embedding（不同模型的向量不能混用）。
`memory_card.embedding_model` 字段记录每条卡片是哪个模型生成的，方便批量重建时过滤。

维度变化时（如从 768 换到 1024）需要：`ALTER TABLE memory_card ALTER COLUMN embedding TYPE vector(1024)`，然后重建 HNSW 索引。

### pgvector 索引

```sql
-- HNSW，不用 IVFFlat
-- IVFFlat 需要预设 lists，数据量小时性能差
-- HNSW 动态构建，从零开始也好用
CREATE INDEX idx_memory_embedding ON memory_card
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);
```

### 成本

本机 Ollama：**$0**，无调用限制，无隐私风险。
OpenAI 备用：$0.02/M tokens，批量 $0.01/M tokens（Batch API）。



---

## L2 内部的 Hot/Cold 分层（防 context 膨胀）

随着使用，L2 会积累几百条记忆。如果全部注入 agent context 会撑爆 token。
需要在 L2 内部区分 hot（频繁命中，自动注入）和 cold（按需检索）：

```
L2-hot   最近 30 天被引用过的记忆，且 citedCount >= 3
         每次 session 启动时自动注入（不超过 10 条，token 预算 2000）
         存放在 memory_card.hot = true

L2-cold  其余所有 active 记忆
         只在 agent 主动调用 hearth_retrieve_memory 时返回
```

**热迁移规则**（定时任务，每天跑一次）：
```
cold → hot：最近 30 天内 citedCount >= 3，且 score >= 0.7
hot → cold：30 天内 citedCount = 0，或 score 衰减到 0.5 以下
```

**注入上限**：hot 记忆总 token 不超过 2000，超出则按 score 降序截断。
这样 agent 始终有最相关的记忆垫底，但不会因为记忆太多而撑爆 context。

`docs/03-schema.md` 的 `memory_card` 表需要加 `hot BOOLEAN NOT NULL DEFAULT false` 字段。



```
session 结束 / checkpoint
        ↓
  [便宜模型] 抽取候选卡片
        ↓
  分类: 决策 | 约束 | 偏好 | 踩坑 | 事实 | 纠错
        ↓
  [价值打分] ← 见下
        ↓
  低分丢弃 ┄┄ 高分 → 冲突检测 → 入 L2
```

用便宜模型（Haiku 级）做抽取是刻意的：提炼是高频批量操作，用贵模型成本不可控，
而抽取本身是低难度任务。打分和冲突消解可以用稍强的模型。

---

## 价值判据（可量化，不拍脑袋）

### 1. 不可推导性 — 权重最高的负向过滤

> 从代码、git history、README、CLAUDE.md 里能直接查到的，**不存**。

这一条能砍掉约 70% 的候选噪音。实现上：入库前跑一次检索，如果在项目
现有资料里能找到高相似度内容，直接丢弃。

「这个项目用 React」不是记忆，是 package.json。
「这个项目当初评估过 Vue，因为团队 SSR 经验放弃了」才是记忆。

### 2. 纠错性 — 权重最高的正向信号

**人类反驳 agent 的瞬间是记忆金矿。**

平台既然有 IM 对接，用户在飞书里说「不对，应该是 X」——这条要自动识别并升到
L2 最高权重。触发点：

- IM 中的否定式回复
- Inbox 里人工驳回了 agent 的方案
- 人工修改了 agent 产出的代码（diff of diff）
- 任务被人工中断

这类记忆的复用价值远高于 agent 自己总结的「我学到了…」。

### 3. 命中反馈 — 让记忆库自我净化

```
被检索且实际被引用 → 权重 +
被检索但未被引用   → 权重 -（检索命中了但没用，说明相关性判断有误）
长期未被检索       → 衰减 → 归档（不删除，降到 L1）
```

没有这个闭环，L2 会单调膨胀，最终退化成 L1。

### 4. 跨域复用性

只在单个 session 成立的不进 L2。判据：把 session 特有的实体（文件名、分支名、
具体报错）抽象掉之后，这条记忆还成立吗？

---

## 两件容易漏的事

### 冲突消解

新记忆和旧记忆矛盾时**不能两条都留**——这是记忆系统失效最常见的原因，
agent 检索到互相矛盾的两条，行为变得不可预测。

```
MemoryCard.supersedes: string[]    // 明确的取代关系链
MemoryCard.status: active | superseded | archived | disputed
```

检测到矛盾但无法自动判断谁对时，标 `disputed` 并进 Inbox 问人。
**不要让系统自己猜**——猜错的成本远高于问一句。

### 可见域

不是所有记忆都该喂给所有 agent。全局共享会导致上下文污染，
让 architect 拿到一堆前端调试细节。

```
MemoryCard.scope: {
  workspace: string | '*'
  agentRoles: string[] | '*'    // ['architect', 'reviewer']
  tags: string[]
}
```

检索时按 `(当前 workspace, 当前 agent role, 任务 tags)` 过滤后再做向量召回。

---

## 有效期

```
MemoryCard.validUntil?: ISO8601
MemoryCard.reviewAfter?: ISO8601
```

技术选型类记忆天然会过期（「用 X 库的 2.x 版本，3.x 有 bug」）。
到期触发复核而非直接失效——复核可以是一次便宜的自动检查，也可以进 Inbox。
