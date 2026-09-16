# InterviewGuide 智能 AI 面试官平台 — Learning Walkthrough

> 生成时间：2026-09-13
> 适用对象：拿到这份源码、想快速读懂它的人

> **TL;DR**：
> - 5 分钟懂全貌 → 看 [第 1 节](#1-5-分钟懂全貌)
> - 30 分钟上手 → 看 [第 2 节](#2-30-分钟上手)
> - 深入贡献 → 看 [第 3 节](#3-深入贡献)
> - 项目跳过什么 → 看 [第 4 节](#4-可以暂时跳过)

---

## 1. 5 分钟懂全貌

> 目标：知道这个项目是干嘛的、整体长什么样。不需要写代码。

**步骤**：

1. **读 `README.md`** — 3 分钟
   - 第 1 节「项目介绍」+「技术栈」+「功能特性」：简历分析 / 模拟面试（文字 + 语音）/ 面试安排 / 知识库 + 题库面试 / 多模型配置，五条业务线
   - 技术选型 FAQ 里有作者对"为什么用 pgvector 而不是专用向量库""为什么用 Redis Stream 而不是 Kafka"的直接回答，是理解架构取舍的最短路径
   - 跳过「效果展示」的截图段和「配套教程」的推广段

2. **看 [ARCHITECTURE.md 第 1 节「项目一句话定位」](./ARCHITECTURE.md#1-项目一句话定位)** — 30 秒
   - 确认 README 没看错

3. **看 [ARCHITECTURE.md 第 3 节「顶层架构图」](./ARCHITECTURE.md#3-顶层架构图)** — 1 分钟
   - 关键在于看出「common 横切能力层 + infrastructure 技术基座 + modules 自包含业务」这个三层切法，以及 Postgres / Redis / S3 / 外部 LLM 四个外部依赖

4. **看 [ARCHITECTURE.md 第 5 节「模块索引表」](./ARCHITECTURE.md#5-模块索引)** — 1 分钟
   - 9 个模块各自负责什么，一句话对上号

**时间合计**：~5 分钟

---

## 2. 30 分钟上手

> 目标：能跑起来、能在脑子里画出请求的完整路径。需要读代码，但只读关键文件。

**步骤**：

1. **跑起来**（8 分钟）— 按 [ARCHITECTURE.md 第 7 节](./ARCHITECTURE.md#7-部署--运行--测试) 走
   - 三个前置动作最容易卡住：① `.env` 里必须填 `AI_BAILIAN_API_KEY` 和 `APP_AI_CONFIG_ENCRYPTION_KEY`；② `docker compose -f docker-compose.dev.yml up -d` 起依赖；③ 首次要登录 `http://localhost:9001` 手动建 `interview-guide` bucket
   - 跑通一个最简单的请求：`GET http://localhost:8080/api/resumes/health`，或直接开 `swagger-ui.html`
   - **前置条件**：这一步要求上面 compose 的三个依赖容器都已 healthy、且 `./gradlew :app:bootRun` 已无报错启动完成，否则 8080 上没有服务可连

2. **读入口文件**（5 分钟）
   - 后端主入口：`app/src/main/java/interview/guide/App.java` — 注意它 `exclude` 了 Spring AI 全部 OpenAI 自动配置。这是全文最重要的信号：**模型不是自动配置出来的，而是由 `app.ai.providers` 驱动、运行期可切换**（见 `common/config/LlmProviderProperties`、`common/ai/LlmProviderRegistry`）
   - 后端配置总入口：`app/src/main/resources/application.yml` — 端口 8080、虚拟线程开启、pgvector 维度 1024 / COSINE / HNSW、`app.ai.providers` 各 Provider 定义
   - 前端入口：`frontend/src/main.tsx` → `frontend/src/App.tsx`（**完整路由表就在这里**）；`frontend/src/constants/routes.ts` 只放 5 条被多处复用的路径常量，别指望它列出全部页面
   - 前端 API 基座：`frontend/src/api/request.ts`（统一 Axios 实例 + `/api` 代理）

3. **读核心模块的「定位 + 关键文件清单」**（12 分钟）
   - 按 [模块索引表](./ARCHITECTURE.md#5-模块索引) 顺序，**先 common 再 infrastructure，然后按兴趣挑业务模块**
   - 每个模块打开 `modules/<name>.md` 只看三节：「1. 定位」「2. 关键文件清单」「3. 核心类 / 函数 / 接口」
   - 跳过「内部架构图」「数据流」「设计决策」细节

4. **跟着一个真实请求走一遍**（5 分钟）
   - 推荐 [ARCHITECTURE.md 4.1「简历上传 → 异步分析」](./ARCHITECTURE.md#41-简历上传--redis-stream-异步分析--pdf-报告)：它一次性串起了最典型的四层——Controller → Service → infrastructure（文件/解析）→ common（异步模板 + LLM）→ Redis Stream 消费者
   - 路径：`ResumeController` → `ResumeUploadService` → `AnalyzeStreamProducer` → Redis → `AnalyzeStreamConsumer` → `ResumeGradingService`
   - 看完能讲清楚"这个请求经过哪几个文件、哪一步是异步的、状态存在哪"

**时间合计**：~30 分钟

---

## 3. 深入贡献

> 目标：要改代码、能写出符合项目风格的 PR。需要精读模块。

### 3.1 模块精读顺序

> 按依赖关系**倒序**读：先读被依赖最多的底层，再读上层。这样读上层时底层已经懂了。

**推荐顺序**（从底到顶）：

1. **`common`** — 最底层，几乎被所有模块依赖
   - 读 `modules/common.md` 全文
   - **优先读这 4 处**：
     - `common/ai/LlmProviderRegistry.java` + `common/ai/StructuredOutputInvoker.java` — 所有 AI 调用的必经之路，"结构化输出 + 重试 + 降级"的统一实现
     - `common/async/AbstractStreamProducer.java` + `AbstractStreamConsumer.java` — 所有异步任务的模板，看懂它就懂了一半架构
     - `common/evaluation/UnifiedEvaluationService.java` — 文字与语音面试共用的评估引擎
     - `common/aspect/RateLimitAspect.java` + `common/annotation/RateLimit.java` + `resources/scripts/*.lua` — 可重复注解限流（Global/IP/User 三档）

2. **`infrastructure`** — 技术基座
   - 读 `modules/infrastructure.md` 全文
   - 优先读 `file/DocumentParseService.java`（Tika 解析统一入口）、`file/FileStorageService.java`（S3 读写）、`export/PdfExportService.java`（iText 中文字体处理是坑点）

3. **`llmprovider`** — 配置持久化
   - 读 `modules/llmprovider.md`；重点 `service/ApiKeyEncryptionService.java`（密钥加密落盘）与 `controller/LlmProviderController.java`（设置页对应的全部端点）

4. **`resume`** — 最简单的业务模块，适合作为"读懂一个完整业务链路"的练手对象
   - 读 `modules/resume.md` 全文；它把"上传 → 异步分析 → 轮询 → 导出"闭环走完了，其他模块都是它的加强版

5. **`interviewschedule`** — 体量最小、依赖最少的业务模块
   - 读 `modules/interviewschedule.md`；重点是 `service/InterviewParseService.java` 的"正则规则 + AI 兜底"双引擎设计

6. **`interview`** — 文字面试
   - 读 `modules/interview.md` + `resources/skills/<方向>/SKILL.md` 任意一个（理解出题策略怎么外置）
   - 重点：`skill/InterviewSkillService.java`、`service/InterviewQuestionService.java`（历史题去重）、`listener/EvaluateStreamConsumer.java`

7. **`knowledgebase`** — 体量最大的后端模块（49 个文件 / 19 个 service）
   - 读 `modules/knowledgebase.md`；建议按三条子链路分开读：**向量化**（Parse → Vector → VectorizeStream）、**RAG 问答**（Query → QueryRewrite → SSE）、**题库 + 专项面试**（QuestionGeneration → Question → Interview + 容量校验）
   - 重点：`repository/VectorRepository.java`（pgvector 原生查询）、`service/KnowledgeBaseQueryService.java`、`service/KnowledgeBaseInterviewService.java`

8. **`voiceinterview`** — 技术密度最高的模块
   - 读 `modules/voiceinterview.md`；`handler/VoiceInterviewWebSocketHandler.java`（约 1484 行）是编排中枢，建议先读类注释与方法列表再进细节
   - 重点：`service/QwenAsrService.java` / `QwenTtsService.java` / `DashscopeLlmService.java`（三段音频链路）、`context/VoiceContextCompressor.java`（多轮上下文压缩）

9. **`frontend`** — 最上层，用户最近
   - 读 `modules/frontend.md`；建议按 `App.tsx`（路由表）→ `constants/routes.ts` → 目标页面 → 该页引用的 `components/` 与 `api/` 的顺序读

### 3.2 改代码前的检查清单

- [ ] 读了根目录 `AGENTS.md`（跨工具 Agent 入口，含 Tech Stack / Architecture / Never Do 清单）与 `CLAUDE.md`
- [ ] 后端改公共能力：跑了 `./gradlew :app:test --no-daemon`
- [ ] 前端改动：跑了 `cd frontend && pnpm run build`（含 tsc 类型检查）
- [ ] 遵守 Never Do：不 `throw new RuntimeException`（用 `BusinessException(ErrorCode.XXX, "描述")`）；不直接返回 Entity；不把 `@Value` 散进 Service；不在事务内调 LLM / S3 / 外部 HTTP；不循环调 DB；不用 `Executors.newXxxThreadPool()`
- [ ] 新异步任务继承 `AbstractStreamProducer` / `AbstractStreamConsumer`，并遵守"消费前校验实体存在，实体已删除则 ACK 丢弃"
- [ ] 新 AI 调用走 `LlmProviderRegistry.getChatClientOrDefault(provider)`，结构化输出走 `StructuredOutputInvoker`
- [ ] 新 Prompt 放 `resources/prompts/*.st`（StringTemplate），不硬编码在 Java 里
- [ ] 新限流点用 `@RateLimit` 注解，不手写 Redis 限流逻辑
- [ ] 命名后缀遵循 `XxxRequest` / `XxxResponse` / `XxxDTO` / `XxxEntity`；Entity → DTO 优先 MapStruct
- [ ] 公共能力变更同步更新 `.arch-docs/ARCHITECTURE.md` / `.arch-docs/modules/<name>.md`

### 3.3 找 bug / debug 流程

1. **先判断是不是异步链路的问题** —— 这个项目最容易出问题的地方是 Redis Stream：简历分析、文档向量化、题库生成、面试评估都是异步的。如果是"状态一直卡在分析中/生成中"，先查 Redis Stream 消费端日志与 `AsyncTaskStatus`，再查 `QuestionGenerationRecoveryScheduler` 这类补偿任务
2. **报错信息搜代码** —— 业务异常统一带 `ErrorCode`，直接用错误码或错误描述全文搜索定位抛出点
3. **看 [ARCHITECTURE.md 第 4 节「数据流」](./ARCHITECTURE.md#4-数据流典型请求路径)** —— 走一遍涉及的模块
4. **看对应 `modules/<name>.md` 的第 6 节「数据流」** —— 看本模块内的处理顺序
5. **AI 相关问题的固定排查顺序** —— `LlmProviderRegistry` 取到的 Provider 是否正确 → `.env` / `~/.interview-guide/llm-providers.yml` 里该 Provider 的 base-url + api-key 是否有效 → `LlmProviderController` 的连通性测试端点 → `StructuredOutputInvoker` 的结构化解析是否失败降级
6. **语音面试额外一层** —— WebSocket 音频链路问题优先看 `VoiceInterviewWebSocketHandler` 的日志与 Micrometer 指标（TTS/ASR 延迟），README 已列出已知问题（端到端延迟偏高、无耳机时回声泄漏、TTS 音色单一、弱网断续）

---

## 4. 可以暂时跳过

### 4.1 目录跳过

- `build/`、`app/build/`、`app/bin/`、`frontend/node_modules/`、`frontend/dist/` — 构建产物与第三方依赖
- `.gradle/`、`.git/` — 缓存与版本库
- `.idea/`、`.vscode/`、`.claude/`、`.githooks/` — IDE 与工具配置
- `gradle/wrapper/gradle-wrapper.jar` — 二进制

### 4.2 文件跳过

- `LICENSE`（35KB 法律文本）、`gradlew` / `gradlew.bat`（脚本模板）
- `frontend/pnpm-lock.yaml`、`gradle/libs.versions.toml` 之外的锁文件 — 看 manifest 就够
- `app/src/test/resources/test-files/` — 测试用的简历/文档样本
- `app/src/main/resources/fonts/`、`app/src/main/resources/db/migration/`（除非在改 schema）
- `docker/`、`scripts/` — 一次性运维脚本
- `docs/` — 架构设计与改造记录，属于补充材料，卡住时再查

### 4.3 模块/内容跳过（第一遍不必读）

- `app/src/main/resources/skills/*/references/**` — 各面试方向的参考题库，量大但与代码逻辑无关
- `frontend/src/pages/*.test.ts` 与被它们测试的纯逻辑 `.ts` — 属于细节校验，不是理解主链路必需
- `SpringDoc` / OpenAPI 注解、Javadoc 长注释 — 需要时用 `swagger-ui.html` 直接看接口

> 完整跳过规则见 skill 的 `references/depth-boundaries.md`。

---

## 5. 时间投入参考

| 目标 | 预估时间 | 完成标志 |
|---|---|---|
| 5 分钟懂全貌 | 5 分钟 | 能用一句话讲清楚项目干嘛、有哪 9 个模块 |
| 30 分钟上手 | 30 分钟 | 能跑起来 + 跟完"简历上传 → 异步分析"整条链路 |
| 写第一个 PR | 2-4 小时 | 提一个小的 bug fix（如某个 Prompt 调优、一处前端交互修补） |
| 成为模块 owner | 1-2 周 | 精读完一个核心模块（建议从 `common` 或 `resume` 入手）的全部代码 |
| 成为项目 owner | 1-3 月 | 精读全部 9 个模块 + 跑通全部测试 + 修过几个非平凡的 bug（尤其 Redis Stream 与语音链路） |

> 如果你熟悉 Spring Boot + Spring AI 与 React，前两档会快很多；`voiceinterview` 是最陡的一段（WebSocket + 实时音频 + 三方语音 SDK）。

---

> **回到** [ARCHITECTURE.md](./ARCHITECTURE.md)
