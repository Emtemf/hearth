# Hearth 架构硬约束

## 网关实现

- 用 Spring Boot 虚拟线程 + 同步 InputStream/OutputStream
- 禁止引入 WebFlux / Reactor / DataBuffer（内存泄漏难调试）
- 禁止用 Spring Cloud Gateway 的 ModifyResponseBodyGatewayFilter（会缓冲 SSE）
- 禁止用 Spring AI ChatClient 做网关（破坏字节级透传，私有扩展会丢失）
- 响应体绝对不缓冲，录制用独立线程 + 有界队列，满了丢弃而不是阻塞

## 存储

- Postgres 是主存，Redis 只做队列/锁/临时状态
- 禁止把 agent 编排状态放 Redis（崩溃会丢失，无法恢复长任务）
- pgvector 装在同一个 Postgres 实例，不单独部署向量数据库

## 安全

- API key 必须从环境变量读取，禁止写进任何 .properties / .yaml 文件
- 启动时验证所有必需 key，缺了直接抛异常拒绝启动
- 网关监听 127.0.0.1，不是 0.0.0.0

## Spring AI 使用范围

仅限以下场景，其他地方禁止引入：
- 记忆提炼管线（ChatClient 调 Haiku 级模型）
- L2 记忆检索（VectorStore + pgvector）
- 语义环检测（EmbeddingModel）
- 平台 MCP Server 暴露工具给 agent
