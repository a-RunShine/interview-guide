# 评审报告 01 — ARCHITECTURE.md + LEARNING_PATH.md

> 评审人：严格文档评审员 · 评审日期：2026-09-13
> 核查基准：代码库根 `/Users/sunshine/Desktop/interview-guide`（master `b2d1ca0`）
> 方法：逐条对照 `settings.gradle` / `gradle/libs.versions.toml` / `app/build.gradle` / `frontend/package.json` / `docker-compose.dev.yml` / `.github/workflows/ci.yml` / 各 `*Controller.java` / 关键类源码 / `wc -l` 实测。

## 综合得分：91 / 100
（加权说明：事实准确性 30%、覆盖完整性 20%、结构规范性 10%、信息密度 15%、交叉引用一致性 10%、可操作性 15%）

## 维度评分
| 维度 | 得分 | 扣分理由 |
|---|---|---|
| 事实准确性 | 92 | 版本号/路由/类名/行数/配置几乎全部与源码吻合；唯一硬错：§7.1 登录 RustFS 控制台凭证写反（详见 P0）。另"必须手动建桶"与 `auto-create-bucket:true` 默认矛盾（P1）。 |
| 覆盖完整性 | 95 | ARCH 含模板要求的 7 节，LP 含要求的 5 节，关键内容无遗漏；设计决策均有代码/配置依据，无过度演绎。 |
| 结构规范性 | 95 | ARCH 恰为 7 个二级标题、LP 恰为 5 个二级标题；表格规范、命名统一、mermaid 清晰。 |
| 信息密度 | 90 | 整体干货多、套话少；仅 LP 与 ARCH 在"9 模块/规模"上略有重复表述，但不影响理解。 |
| 交叉引用一致性 | 88 | `modules/*.md` 链接全部可用、规模数字前后自洽；但 §5 路由表把语音 WS 归在 `/api/voice-interview/**` 下且未给出真实路径（P1）。 |
| 可操作性 | 85 | 运行/测试命令、`ci.yml`、LP 文件路径均可落地执行；受 P0 凭证错误拖累（新人按文档无法登录控制台），否则可达 92。 |

## 逐文档评分
| 文档 | 得分 | 一句话总评 |
|---|---|---|
| ARCHITECTURE.md | 92 | 技术栈版本号、路由、类名、行数规模与实测高度一致，仅 §7.1 登录凭证与建桶前提需修正。 |
| LEARNING_PATH.md | 93 | 给出的源码路径、运行/测试命令、模块精读顺序均可执行可定位，结构清晰，是高质量 onboarding 文档。 |

## 必须修复（P0）
1. `ARCHITECTURE.md` → §7.1 第 3 步「启动依赖后登录控制台」 → 登录 RustFS 控制台（http://localhost:9001）的凭证写错（文档值：`APP_STORAGE_ACCESS_KEY / SECRET_KEY`；真实值：`RUSTFS_ACCESS_KEY / RUSTFS_SECRET_KEY`，默认 `rustfsadmin/rustfsadmin`，见 `docker-compose.dev.yml` 的 `rustfs` 服务环境变量）。新人在 9001 页用 `APP_STORAGE_*` 登录会失败。→ 改为「用 `RUSTFS_ACCESS_KEY / RUSTFS_SECRET_KEY`（默认 `rustfsadmin/rustfsadmin`）登录」。

## 建议改进（P1）
1. `ARCHITECTURE.md` → §5 主干 HTTP 路由表（voiceinterview 行） → 只写「WebSocket 语音端点」未给真实路径；真实路径是 `/ws/voice-interview/{sessionId}`（`modules/voiceinterview/config/WebSocketConfig.java:24` 的 `registry.addHandler(..., "/ws/voice-interview/{sessionId}")`），且**不在** `/api/voice-interview/**` 下。→ 在表中补真实 WS 路径，并注明「WS 端点独立于 REST，路径前缀为 `/ws/`」。

2. `ARCHITECTURE.md` → §7.1 第 3 步「手动创建名为 interview-guide 的 bucket」 → 与代码默认矛盾：`application.yml` 中 `app.storage.auto-create-bucket` 默认 `true`，后端首次访问会自动建桶。→ 改为「默认已开启自动建桶；如需手动创建，登录控制台后建 `interview-guide` 桶」，或删除该强制步骤。

3. `ARCHITECTURE.md` → §1 与 §2.1「约 50,000 行代码」 → 口径不清：实测主源码约 43K 行（后端 main `app/src/main/java` 25,235 行 + 前端 `frontend/src` 17,990 行），含后端测试 7,307 行才到约 50.5K。9 个模块自身行数合计约 42.8K。→ 注明计数口径，如「约 4.3 万行主源码（含测试约 5 万行）」。

4. `LEARNING_PATH.md` → §2.1 步骤 1「跑通 GET /api/resumes/health」 → 该端点真实存在（`ResumeController:128`），但步骤未说明需先完成 step ③ 的 bucket/依赖，且未提示后端需先 `bootRun` 成功。→ 补充前置（先起 `docker compose` 与 `bootRun` 无报错）以免新人误以为可零依赖直连。

## 写得好的地方
1. **§2 技术栈版本号全部可核**：Spring Boot 4.1.0、Spring AI 2.0.0、Spring AI Agent Utils 0.10.0、DashScope 2.22.7、Redisson 4.0.0、AWS S3 SDK 2.29.51、Tika 2.9.2、iText 8.0.5、MapStruct 1.6.3、SpringDoc 3.0.2、React 18.3、Vite 5.4、react-router-dom 7.11、Recharts 3.6、onnxruntime-web 1.24、Gradle 9.6.1、Java 25、pnpm 10.26 均与 `libs.versions.toml`/`package.json`/`gradle-wrapper.properties` 一一吻合。
2. **§5 模块规模与 `wc -l` 实测完全吻合**：common 3087/34、infrastructure 2573/14、resume 1781/16、interview 4048/27、voiceinterview 5448/28、knowledgebase 5514/49、interviewschedule 870/11、llmprovider 1883/17、frontend 17990/85，无一处虚报。
3. **§5 主干路由与 `grep *Controller.java` 结果一致**：所列 `POST /api/resumes/upload`、`GET /api/resumes/{id}/export`、`POST /api/interview/sessions`、`POST /api/knowledgebase/{id}/questions/generate`、`POST /api/knowledgebase-interviews/sessions`、`POST /api/voice-interview/sessions`、`POST /api/interview-schedule/parse`、`PUT /api/llm-provider/default-provider` 等端点均真实存在。
4. **关键类名全部存在且精确**：`LlmProviderRegistry`、`StructuredOutputInvoker`、`UnifiedEvaluationService`、`AbstractStreamProducer`/`AbstractStreamConsumer`、`RateLimitAspect`、`AnalyzeStreamProducer`、`AnalyzeStreamConsumer`、`ResumeGradingService`、`QuestionGenerationRecoveryScheduler`、`VoiceInterviewWebSocketHandler`（1484 行，与 LP「约 1484 行」精确一致）、`ApiKeyEncryptionService` 全部核实存在。
5. **LEARNING_PATH 路径与命令可落地**：`App.java` 的 `exclude` OpenAI 自动配置、`common/config/LlmProviderProperties`、`application.yml`（`port:8080`/`threads.virtual.enabled`/pgvector `1024·COSINE·HNSW`/`app.ai.providers` 含 dashscope/kimi/deepseek/glm/lmstudio）、各模块 `service/handler` 路径、前端入口均真实；测试命令与 `.github/workflows/ci.yml`（Java 25 + Node 24 + 4 个 `node --test` + `pnpm run build`）完全一致。
