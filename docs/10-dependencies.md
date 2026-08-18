# 依赖版本清单

**最后更新**：2026-08-16
**原则**：所有版本精确锁定，升级前必须查 breaking changes。

---

## 关键版本决策

### Spring Boot 3.5 → 4.0（必须升级）

Spring Boot 3.5 已于 **2026-06-30 EOL**，不再有安全补丁。
本项目**锁定 Spring Boot 4.0.7**（官方当前最新稳定版为 4.1.0，但本项目选择 4.0.7 作为稳定基线）。

Spring Boot 4.0 要求：
- Java 17+（我们用 21，✓）
- Maven 3.6.3+（我们用 3.8.7，✓）
- Jakarta EE 11 / Servlet 6.1 基线
- **不再支持 Undertow**（我们用默认的 Tomcat，✓）

### Spring AI 1.x → 2.0（配套升级）

Spring AI 2.0.0 GA 于 2026-06-12 发布，**设计为与 Spring Boot 4.0/4.1 配套使用**。
Spring AI 1.x 只支持 Spring Boot 3.x。

Spring AI 2.0 关键 breaking changes（开发时会踩）：
- **artifact 名称变了**：`spring-ai-pgvector-store-spring-boot-starter`
  → `spring-ai-starter-vector-store-pgvector`
- **Jackson 2 → Jackson 3**：如果项目其他地方用了 Jackson 2 的 API 会编译失败
- **MCP transport 变了**：SSE transport 已弃用，默认改为 Streamable HTTP
- **Tool 注册方式变了**：工具必须显式注册为 ToolCallback bean，
  不能再通过 toolNames 隐式传递
- **MCP annotation 包名变了**：如果参考旧文档会找不到类

---

## 精确版本清单

```xml
<!-- ── 核心框架 ── -->
Spring Boot         4.0.7
Spring AI           2.0.0
Spring Framework    7.x（由 Boot 4 管理，不单独声明）

Ollama image        0.32.6（`ollama/ollama:0.32.6`，manifest 已验证）

<!-- ── 前端工具链（精确版本见 docs/13-ui.md） ── -->
Node.js             22.22.1
pnpm                10.6.5
React               19.2.8
TypeScript          5.7.3
Vite                6.4.3
react-router        7.18.2

<!-- ── 存储 ── -->
PostgreSQL JDBC     42.7.11（Boot 4.0.7 BOM）
Flyway              11.14.1（Boot 4.0.7 BOM）
pgvector-java       由 Spring AI 2.0.0 BOM 管理，不单独覆写

<!-- ── 调度 ── -->
Quartz              2.5.2（Boot 4.0.7 BOM）
ShedLock            7.7.0

<!-- ── 序列化 ── -->
Jackson             3.x（由 Boot 4 管理，已从 Jackson 2 升级）

<!-- ── 测试 ── -->
JUnit Jupiter       6.0.3（Boot 4.0.7 BOM）
Testcontainers      2.0.5（Boot 4.0.7 BOM，集成测试用 PostgreSQL 容器）
```

### 可选外部 Agent Runtime（不进入 Maven/npm 依赖图）

| Runtime | 调研/首个 contract-test 基线 | 运行要求 | 许可证 | 用途 |
|---|---|---|---|---|
| Pi Agent | 0.84.1 | Node.js `>=22.19.0` 或固定 hash 的受信 standalone binary | MIT | M2 基础设施完成后的可选 RPC Adapter |

Pi 是 Worker capability，不是 Hearth Java core、前端包或 Spring AI 依赖。Worker 注册时上报精确 Pi 版本和
executable hash；只有兼容矩阵中的组合才能声明 `pi-rpc`。升级必须重跑固定 argv/resource isolation、LF
framing/schema、provider credential carrier、`agent_settled` completion、tool governance、resume 和 cancel contract
tests；仅“进程能启动”不算兼容。详细边界见 `docs/15-pi-agent-adr.md`。

---

## Maven 模块结构

```
hearth/                    根 pom，dependencyManagement
  hearth-core/             领域模型、ValueObject、接口定义（无 Spring 依赖）
  hearth-gateway/          网关透传、观测、模型路由
  hearth-agent/            Profile/版本/Session overlay 编译
  hearth-worker/           Worker daemon、Adapter 与本机 ProcessBuilder；LocalWorkerClient 只调用其受认证 transport
  hearth-orchestrator/     Task/Spec/Plan、Internal Dispatch、预算账本、生命周期状态机
  hearth-artifact/         Artifact 元数据、内容存储与引用保护
  hearth-platform-mcp/     Hearth 平台工具的 MCP Server（M2；不含记忆实现）
  hearth-memory/           记忆分层、提炼管线（Spring AI 的模型/VectorStore 只在这里）
  hearth-trigger/          触发层：飞书/Telegram/cron/webhook → Task
  hearth-api/              Web UI 后端、管理 REST API
```

**M1 需要启动**：`hearth-core` + `hearth-gateway` + `hearth-agent` + `hearth-worker`（本机实现）+
`hearth-api`，并构建 `frontend/` 静态资源。当前 runnable-surface slice 仅先创建 `hearth-api` 和既有
`hearth-worker` contract harness，以 loopback Actuator health 与只读 M1 status 作为可运行证据；它不替代
M1 的 Web UI、REST/SSE 契约或完整服务组合。Spring AI 仍不属于此 slice。

M2 再创建 `hearth-orchestrator`、`hearth-artifact` 与 `hearth-platform-mcp`；M3 创建 `hearth-memory`、`hearth-trigger`，
并在 `hearth-trigger` 启用 Quartz/ShedLock 自动化。根 pom 只声明当前里程碑实际存在的模块，避免
未完成模块不拖累构建；数据库按 vertical slice 增量迁移，V001/V002 只包含 M1 实际读写结构，M2 再通过
V003+ 引入 Task/Dispatch/Evidence。迁移进入共享环境后 append-only，禁止回写。

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
           https://maven.apache.org/xsd/maven-4.0.0.xsd"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.0.7</version>
    <relativePath/>
  </parent>

  <groupId>ai.hearth</groupId>
  <artifactId>hearth</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <packaging>pom</packaging>

  <properties>
    <java.version>21</java.version>
    <spring-ai.version>2.0.0</spring-ai.version>
    <shedlock.version>7.7.0</shedlock.version>
  </properties>

  <dependencyManagement>
    <dependencies>
      <!-- Spring AI BOM -->
      <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-bom</artifactId>
        <version>${spring-ai.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
      <!-- ShedLock BOM -->
      <dependency>
        <groupId>net.javacrumbs.shedlock</groupId>
        <artifactId>shedlock-bom</artifactId>
        <version>${shedlock.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
      <!-- 子模块 -->
      <dependency>
        <groupId>ai.hearth</groupId>
        <artifactId>hearth-core</artifactId>
        <version>${project.version}</version>
      </dependency>
      <dependency>
        <groupId>ai.hearth</groupId>
        <artifactId>hearth-gateway</artifactId>
        <version>${project.version}</version>
      </dependency>
    </dependencies>
  </dependencyManagement>
</project>
```

---

## 各模块关键依赖

### hearth-api（Web UI 后端）

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-web</artifactId>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

前端构建集成（Maven 调用 Vite）：

```xml
<plugin>
  <groupId>com.github.eirslett</groupId>
  <artifactId>frontend-maven-plugin</artifactId>
  <version>1.15.1</version>
  <configuration>
    <workingDirectory>../frontend</workingDirectory>
    <nodeVersion>v22.22.1</nodeVersion>
    <pnpmVersion>v10.6.5</pnpmVersion>
  </configuration>
  <executions>
    <execution>
      <id>install-node-and-pnpm</id>
      <goals><goal>install-node-and-pnpm</goal></goals>
    </execution>
    <execution>
      <id>pnpm-install</id>
      <goals><goal>pnpm</goal></goals>
      <configuration><arguments>install --frozen-lockfile</arguments></configuration>
    </execution>
    <execution>
      <id>pnpm-build</id>
      <goals><goal>pnpm</goal></goals>
      <configuration><arguments>run build</arguments></configuration>
    </execution>
  </executions>
</plugin>
<!-- 把 Vite 产物复制到 Spring Boot static 目录 -->
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-resources-plugin</artifactId>
  <executions>
    <execution>
      <id>copy-frontend</id>
      <phase>generate-resources</phase>
      <goals><goal>copy-resources</goal></goals>
      <configuration>
        <outputDirectory>${project.basedir}/src/main/resources/static</outputDirectory>
        <resources>
          <resource><directory>../frontend/dist</directory></resource>
        </resources>
      </configuration>
    </execution>
  </executions>
</plugin>
```

开发时跳过前端构建（避免每次 mvn package 都重新构建前端）：
```bash
mvn package -DskipFrontend   # 需要在 pom.xml 里配对应的 skip 属性
```

```xml
<!-- Spring MVC，不是 WebFlux -->
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-web</artifactId>
</dependency>
<!-- 健康检查，M1 必须有 -->
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<!-- Postgres -->
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>
<dependency>
  <groupId>org.postgresql</groupId>
  <artifactId>postgresql</artifactId>
</dependency>
```

application.properties 开启虚拟线程：
```properties
spring.threads.virtual.enabled=true
```

### hearth-memory（记忆模块，Spring AI 在这里）

```xml
<!-- pgvector -->
<dependency>
  <groupId>org.springframework.ai</groupId>
  <artifactId>spring-ai-starter-vector-store-pgvector</artifactId>
</dependency>
<!-- Ollama Embedding（本机，nomic-embed-text，免费无隐私风险） -->
<dependency>
  <groupId>org.springframework.ai</groupId>
  <artifactId>spring-ai-starter-model-ollama</artifactId>
</dependency>
<!-- Anthropic（记忆提炼用 Haiku 级模型） -->
<dependency>
  <groupId>org.springframework.ai</groupId>
  <artifactId>spring-ai-starter-model-anthropic</artifactId>
</dependency>
```

application.properties 配置：
```properties
# 本机 Ollama embedding（默认，免费，数据不离机）
spring.ai.ollama.base-url=http://localhost:11434
spring.ai.ollama.embedding.model=nomic-embed-text

# pgvector：768维 对应 nomic-embed-text 输出维度
spring.ai.vectorstore.pgvector.dimensions=768
spring.ai.vectorstore.pgvector.index-type=HNSW
spring.ai.vectorstore.pgvector.distance-type=COSINE_DISTANCE
spring.ai.vectorstore.pgvector.initialize-schema=true
```

首次启动前需要拉取模型（只需一次）：
```bash
ollama pull nomic-embed-text
# 或通过 docker compose 执行：
docker compose exec ollama ollama pull nomic-embed-text
```

切换为 OpenAI 远程 embedding 时替换依赖为 `spring-ai-starter-model-openai`，
改 properties，并重跑全量 embedding 批处理（见 `docs/06-memory.md`）。

### hearth-trigger（触发与调度模块，M3 启用）

```xml
<!-- Quartz，由 Boot 4 BOM 管理版本 -->
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-quartz</artifactId>
</dependency>
<!-- ShedLock，防多实例重复执行 -->
<dependency>
  <groupId>net.javacrumbs.shedlock</groupId>
  <artifactId>shedlock-spring</artifactId>
</dependency>
<dependency>
  <groupId>net.javacrumbs.shedlock</groupId>
  <artifactId>shedlock-provider-jdbc-template</artifactId>
</dependency>
```

---

## M1 最小依赖（只需要这些）

**M1 不需要 Spring AI、Quartz、ShedLock。只需要：**

```xml
spring-boot-starter-web       Spring MVC + 内嵌 Tomcat
spring-boot-starter-actuator  /actuator/health
spring-boot-starter-data-jpa  JPA + 事务
postgresql                    JDBC 驱动
flyway-core                   数据库迁移
```

加一行 `spring.threads.virtual.enabled=true`，虚拟线程就开了，不需要额外依赖。

---

## Spring AI 2.0 MCP Server 注意事项

Hearth 把自己的工具（`hearth_dispatch` 等）暴露给 agent 时，用的是 Spring AI 的 MCP Server。
该依赖属于 M2 的 `hearth-platform-mcp`，不是 M3 的 `hearth-memory`。`hearth-memory` 到 M3 再通过
application port 为同一 MCP server 增加 `hearth_retrieve_memory` 实现，MCP transport 不因此迁移。

**正确 artifact（Spring AI 2.0）**：
```xml
<dependency>
  <groupId>org.springframework.ai</groupId>
  <artifactId>spring-ai-starter-mcp-server-webmvc</artifactId>
</dependency>
```

**必须显式设置 Streamable HTTP 协议**（不要依赖默认值，SSE transport 已弃用）：
```properties
spring.ai.mcp.server.protocol=STREAMABLE
spring.ai.mcp.server.name=hearth
spring.ai.mcp.server.version=0.1.0
```

**SSE transport 已在 Spring AI 2.0 弃用**，不要参考 1.x 的配置示例（很多网上的文档还是旧的）。
参考：[Spring AI MCP Streamable HTTP Server 文档](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-streamable-http-server-boot-starter-docs.html)

**M1 不要引入 Spring AI**——记忆模块是 M3 的事，提前引入只会带来不必要的配置复杂度。

---

## 需要主动避开的陷阱

**陷阱1：看到旧文档里的 Spring AI artifact 名**

旧名称（1.x）：`spring-ai-pgvector-store-spring-boot-starter`
新名称（2.0）：`spring-ai-starter-vector-store-pgvector`

Sonatype Central 上两个都能搜到，旧的会报版本不存在或 classpath 冲突。

**陷阱2：Jackson 相关的编译错误**

Spring AI 2.0 + Boot 4 使用 Jackson 3，部分 Jackson 2 的 API（`ObjectMapper` 某些方法签名）变了。遇到 Jackson 相关编译错误，先查是不是 Jackson 2/3 混用。

**陷阱3：MCP transport 配置**

Spring AI 2.0 应显式配置 Streamable HTTP，不要依赖 transport 默认值，也不要照搬 1.x 的 SSE 配置。

**陷阱4：Testcontainers 版本**

Boot 4 BOM 管理的 Testcontainers 版本跑集成测试时需要 Docker 在线。本地没有 Docker 时集成测试会失败，不是代码问题。
