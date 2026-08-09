# 运维：配置管理、健康检查、部署拓扑

这三件事没有一件是"以后再说"的——每一件都在 M1 第一天就会踩到。

---

## 配置管理：API Keys 放哪

**绝对不能做的事**：把 key 写进 `application.properties` 然后 commit 进 git。
这个错误在开源项目里发生概率接近 100%，一旦 push 就等于公开了。

**推荐方案（按优先级）**：

### 本地自用阶段（M1/M2）

用环境变量，通过 `.env` 文件注入，`.env` 加进 `.gitignore`：

```bash
# .env（不提交）
ANTHROPIC_API_KEY=replace-with-your-anthropic-api-key
FEISHU_BOT_TOKEN=xxx
TELEGRAM_BOT_TOKEN=xxx
HEARTH_DB_PASSWORD=xxx
```

Spring Boot 自动读取环境变量，`application.properties` 只放非敏感配置：

```properties
# application.properties（可以提交）
server.port=4517
spring.datasource.url=jdbc:postgresql://localhost:5432/hearth
spring.datasource.username=hearth
spring.datasource.password=${HEARTH_DB_PASSWORD}
anthropic.api-key=${ANTHROPIC_API_KEY}
```

启动脚本：
```bash
#!/bin/bash
set -a && source .env && set +a
java -jar hearth.jar
```

### 开源后阶段（M3+）

换成外部 secret 管理（Vault / 系统 keyring），`application.properties` 里只引用 secret 路径，
不引用 secret 内容本身。现在的环境变量方案预留了这个迁移路径——只需要改注入方式，
不需要改代码。

### 启动时验证

所有必需的 key 在启动时检查，缺了就拒绝启动并明确报错，不等到第一次调用时才发现：

```java
@PostConstruct
void validateSecrets() {
    if (anthropicApiKey == null || anthropicApiKey.isBlank()) {
        throw new IllegalStateException(
            "ANTHROPIC_API_KEY is required. Set it in .env or as an environment variable.");
    }
}
```

---

## 健康检查和告警

网关在 agent 的关键路径上，挂了你的所有 Claude Code 会话都会失败。
没有监控，你会以为是 Anthropic 的问题，排查半小时才发现是自己的网关挂了。

### 最小监控（M1 必须做）

Spring Boot Actuator 开箱即用，两行配置：

```properties
management.endpoints.web.exposure.include=health,info,metrics
management.endpoint.health.show-details=always
```

暴露 `GET /actuator/health`，包含：
- 网关进程存活
- Postgres 连接是否正常
- 最后一次成功转发的时间

### 本地告警（够用的最简方案）

不需要 Grafana，不需要 Prometheus。一个定时任务每分钟 ping 一次自己，失败就推飞书/Telegram：

```java
@Scheduled(fixedDelay = 60_000)
void selfPing() {
    try {
        // 内部检查网关核心功能是否正常
        gatewayHealthChecker.check();
    } catch (Exception e) {
        inboxService.createAlert("网关异常：" + e.getMessage());
        // 同时直接推飞书，不走 Inbox——Inbox 本身可能也不可用
        feishuDirectAlert("Hearth 网关异常，请检查");
    }
}
```

关键点：告警通路必须**独立于 Inbox 之外**，否则 Inbox 依赖的数据库如果也挂了，
你什么通知都收不到。

---

## 部署拓扑：Hearth 和 Claude Code 在哪里

这个问题不定，网关地址写 `127.0.0.1:4517` 就是错的。

**两种有意义的拓扑，选一个**：

### 方案A：同机部署（推荐，M1/M2 阶段）

```
你的笔记本 / 台式机
├── Claude Code 进程
├── Hearth 进程（网关 + 编排）
└── Postgres（Docker 或本地）

Claude Code 的 ANTHROPIC_BASE_URL = http://127.0.0.1:4517/s/{sid}/anthropic
```

优点：延迟最低，调试最方便，没有网络问题。
适合：自用阶段，你就是唯一用户。

### 方案B：服务器部署（M3+ 开源后）

```
你的手机 / 任意设备
    │ 飞书 / Telegram
    ▼
远程服务器
├── Hearth 进程（网关 + 编排）
└── Postgres

你的笔记本（工作机）
└── Claude Code 进程
    ANTHROPIC_BASE_URL = https://hearth.your-domain.com/s/{sid}/anthropic
```

优点：可以从任何设备发任务，不依赖笔记本开机。
额外要求：HTTPS（TLS），不能用 http 发 API key；防火墙只开必要端口。

**M1 先做方案A**，方案B 所需的 HTTPS 和域名配置等开源时再加，接口设计上不影响迁移。

---

## 本地管理面认证

绑定 `127.0.0.1` 只能阻止其他机器直连，不能阻止同用户恶意进程或浏览器跨站请求。M1 首次启动生成
高熵本地管理员 bootstrap token，用户兑换后使用 `HttpOnly`、`Secure`（HTTPS 时）、`SameSite=Strict`
会话 cookie；所有修改状态的 API 要求 Origin 检查和 CSRF token。gateway/MCP/session capability token
只允许对应 audience，不能访问管理 API、raw recording 或 provider route 配置。

Public/open-source 部署不得使用“本地只有我”作为授权判断；启用非 loopback 监听前必须配置 TLS、用户认证、
workspace 授权、rate limit 和审计。未配置时启动直接失败。

---

## 对象存储（Artifact 存储后端）

Artifact 的大文件（代码 diff、测试报告、截图）存对象存储，不进 Postgres。

**M1/M2 阶段（本机）**：用本地文件系统模拟，路径 `~/.hearth/artifacts/{artifactId}`。
不引入 MinIO/S3，保持简单。

**M3+ 阶段（多机蜂窝）**：Worker 上传 Artifact 需要一个网络可达的存储。
对象存储实现统一使用 S3 兼容接口，具体部署可选自建 MinIO 或受信云服务；选择前必须核实当时维护状态、
安全更新与许可证，文档不锁定未经持续验证的供应商。

本地文件系统实现（M1/M2）配置：
```properties
hearth.artifact.storage=local
hearth.artifact.local.base-path=${user.home}/.hearth/artifacts
```

---

## 数据保留策略

不做这个，M1 的磁盘会被 system prompt + 全量事件流撑满。

| 数据 | 保留策略 | 原因 |
|---|---|---|
| L1 事件流（全量） | 30 天后归档（压缩），90 天后删除 | 量最大，调试价值随时间快速下降 |
| Artifact 文件 | Task 结束后 7 天，**但有引用的不得删除** | 大文件，占磁盘 |
| L2 记忆卡片 | 永久（有衰减机制，不硬删除） | 高价值，量小 |
| Session 元数据 | 永久 | 量小，成本审计需要 |
| Inbox 已解决条目 | 90 天 | 审计用 |

### Artifact 引用保护（防悬空引用）

G4C+E 要求所有结论长期可通过 Artifact 独立核验。7 天后删除会破坏这个保证。

清理规则：**被引用的 Artifact 不得删除**。引用来源包括：
- 未关闭的 Inbox 条目（`inbox_item.actions` 里含 artifactId）
- checkpoint_execution 记录（`evidence_artifact_id`）
- operation_log 快照（`snapshot_ref`）
- memory_card 来源（`source_session_ids` 对应的 exchange/artifact）

实现：所有引用统一写入 `artifact_reference`，与引用方业务写入同一事务。清理任务对 Artifact
加行锁并再次确认引用数为 0，随后将 `status` 改为 `DELETED`、清空内容/存储路径，保留
`artifact_id + deleted_at + sha256 + size_bytes` tombstone。这样历史引用返回 410 和可核验摘要，
而不是无法解释的 404。具体 Schema 见 `docs/03-schema.md`。

---

网关监听在 `127.0.0.1`，不是 `0.0.0.0`，否则局域网内任何人都能把自己的 Claude Code 指向你的网关，消费你的 API quota。

方案A（同机）时这条免费保护你。方案B（服务器）时需要在网络层加认证，不在本文档范围内。
