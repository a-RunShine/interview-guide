# AI Interview Platform Agent Rules

Spring Boot 4.1.0 + Java 25 + Spring AI 2.0.0 + React 面试平台。

本文件是跨工具 Agent 入口，只放长期有效、代码里不容易直接推断、猜错会影响结果的规则。更细的目录规则放在 `.claude/rules/`，需要时再读取。

## Tech Stack

- Backend: Spring Boot 4.1.0 / Java 25 / Gradle / Spring AI 2.0.0
- Database: PostgreSQL + pgvector，向量维度 1024，距离类型 COSINE
- Cache & async: Redis / Redisson / Redis Stream
- Storage & parsing: RustFS/S3 / Apache Tika
- Mapping & export: MapStruct / iText 8 / SpringDoc OpenAPI
- Frontend: React 18 / TypeScript / Vite / TailwindCSS 4，代码在 `frontend/`

## Commands

```bash
./gradlew :app:compileJava
./gradlew :app:test --no-daemon
./gradlew :app:bootRun
```

```bash
cd frontend && pnpm run dev
cd frontend && pnpm run build
```

```bash
docker compose -f docker-compose.dev.yml up -d
```

## Project Structure

- `app/src/main/java/interview/guide/common/`: 通用能力，包括限流、AI 调用、异步模板、配置、异常、统一响应。
- `app/src/main/java/interview/guide/infrastructure/`: 技术基础设施，包括文件、导出、Redis、MapStruct 映射。
- `app/src/main/java/interview/guide/modules/`: 业务模块，每个模块自包含 MVC 分层。
- `app/src/main/resources/prompts/`: StringTemplate Prompt 模板。
- `frontend/src/`: React 前端页面、组件、API 客户端和类型定义。

## Architecture

- 后端遵循 `Controller -> Service -> Repository`，Controller 只做路由、校验和委托。
- Service 承担业务编排；`@Transactional` 只放 Service 层，并保持事务范围最小。
- Repository 继承 Spring Data JPA `JpaRepository`，自定义查询用方法名或 `@Query`。
- 基础设施能力优先放在 `common/` 或 `infrastructure/`，不要散落到业务 Service。
- 对外响应统一使用 `Result<T>`，禁止直接返回 Entity。

## Backend Rules

- 业务异常必须使用 `BusinessException(ErrorCode.XXX, "描述信息")`。
- 全局异常处理器返回 HTTP 200 + `Result.error(code, message)`。
- 请求体优先用不可变 `record`，命名后缀使用 `XxxRequest` / `XxxResponse` / `XxxDTO` / `XxxEntity`。
- Entity 到 DTO/Response 的映射优先使用 MapStruct。
- 使用构造器注入，优先配合 Lombok `@RequiredArgsConstructor`。
- 代码使用 2 空格缩进、无通配符导入、避免内联全限定类名。
- 日志使用 SLF4J 占位符，异常必须作为最后一个参数传入。

## AI And Async Rules

- 获取聊天模型统一走 `LlmProviderRegistry.getChatClientOrDefault(provider)`。
- 结构化输出统一走 `StructuredOutputInvoker`，不要在业务代码里复制重试逻辑。
- Prompt 模板放在 `resources/prompts/`，使用 StringTemplate `.st`。
- LLM、S3、外部 HTTP 调用不得放在数据库事务内。
- Redis Stream 生产/消费使用 `AbstractStreamProducer` / `AbstractStreamConsumer` 模板。
- 异步处理前先校验实体是否存在；实体已删除时 ACK 丢弃。
- 限流使用可重复 `@RateLimit`，不要手写散落的 Redis 限流逻辑。

## Config And Data

- 配置集中在 `application.yml`、`.env` 和 `@ConfigurationProperties` 类中。
- API Key、数据库密码等敏感信息只放 `.env`，不得提交到 Git。
- 不要在 Service 中散落 `@Value`。
- 本地默认后端端口是 `8080`：`server.port: ${SERVER_PORT:8080}`。
- 开发环境 `ddl-auto` 可为 `update`，生产环境不能依赖自动建表。

## Frontend Rules

- API 调用集中在 `frontend/src/api/`，复用 `request.ts` 的 Axios 实例。
- 类型定义放在 `frontend/src/types/`，不要在页面里重复定义共享接口。
- 页面放在 `frontend/src/pages/`，可复用 UI 放在 `frontend/src/components/`。
- 路由常量放在 `frontend/src/constants/routes.ts`。
- 组件交互优先使用现有设计语言和 `lucide-react` 图标。

## Testing

- 后端测试使用 JUnit 5 + Mockito + AssertJ。
- 测试意图用中文 `@DisplayName` 描述，复杂场景用 `@Nested` 分组。
- 集成测试使用 H2 配置；限流相关测试需要真实 Redis。
- 改后端公共能力时至少运行 `./gradlew :app:test --no-daemon`。
- 改前端时至少运行 `cd frontend && pnpm run build`。

## Never Do

- 不要 `throw new RuntimeException(...)`，业务失败必须用 `BusinessException`。
- 不要直接返回 Entity 给前端。
- 不要把 `@Value` 散落在 Service 中。
- 不要在事务内调用 LLM、S3 或外部 HTTP。
- 不要同类内部调用 `@Transactional` 方法。
- 不要 `catch (Exception e) {}` 静默忽略。
- 不要循环调用 DB，优先批量查询或批量写入。
- 不要硬编码密钥、Token、数据库密码。
- 不要使用 `Executors.newXxxThreadPool()`，需要线程池时显式配置 `ThreadPoolExecutor`。

## More Rules

- 后端 Java 细则：`.claude/rules/backend.md`
- AI、限流、异步细则：`.claude/rules/ai-and-async.md`
- 前端细则：`.claude/rules/frontend.md`

## Cursor Cloud specific instructions

- 这台 VM 没有 systemd。Docker 要用 `fuse-overlayfs` 存储驱动和 `iptables-legacy` / `ip6tables-legacy`，再以前台方式启动 `dockerd`。`sudo service docker start` 不会拉起守护进程。
- 先把 `.env.example` 复制成仓库根目录 `.env`，再执行 `docker compose -f docker-compose.dev.yml up -d`。Compose 会读取这份 `.env`，Postgres 密码因此是 `password`。只有在变量未设置时，compose 文件自身的默认值才是 `123456`。
- RustFS 容器用户是 uid/gid `10001`。首次启动前执行 `sudo chown -R 10001:10001 "$HOME/documents/interview_guide/rustFS"`，否则数据目录权限会导致容器退出。
- `bootRun` 要求 `AI_BAILIAN_API_KEY` 和 `APP_AI_CONFIG_ENCRYPTION_KEY` 非空。`.env.example` 里的占位值足够启动，并覆盖面试日程这类不调用模型的功能。真正的 DashScope 调用需要替换 `AI_BAILIAN_API_KEY`。
- 前端使用 Corepack 锁定的 pnpm 10.26.2。pnpm 10 默认跳过依赖构建脚本，安装后需要执行 `corepack pnpm rebuild esbuild @swc/core protobufjs --config.dangerouslyAllowAllBuilds=true`，否则 Vite 找不到 esbuild 二进制。
- 登录 shell 里的 Node 可能先命中 `/exec-daemon/node`（22.14），它不能直接 `node --test` 运行前端 `.ts` 测试。前端校验用 `cd frontend && corepack pnpm run build`。
