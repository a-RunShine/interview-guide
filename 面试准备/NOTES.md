# NOTES — 教学偏好与工作约定

## 用户偏好（bojack）

- **语言**：中文。技术名词保留英文（Redis Stream、pgvector、SSE、RAG）。
- **风格**：结论先行，有判断，不绕弯。讨厌套话和"很好的问题！"这类填充。
- **先沟通计划再执行**：涉及安装、批量改动、重命名的操作必须先说方案。
- **不要反复重试同一组失败参数**：失败两次就换路径。
- 新建文件 / 目录默认**中文命名**。本工作区根目录 `面试准备/` 与课程文件名已用中文；
  但 `MISSION.md` / `NOTES.md` / `RESOURCES.md` / `lessons/` / `reference/` 等保留 teach skill
  规定的固定名，否则后续会话无法识别工作区结构。这是一处有意的例外。

## 教学决策记录

- **深挖权重分配**（用户答"不知道，你判断"，由我定）：
  实现细节 35% · 架构选型 35% · 八股结合项目 25% · 业务价值 5%。
- **课程顺序做了调整**：不是先讲架构，而是**先攒真实弹药**（做出可讲的改动），再练表达。
  理由：用户选的框架是"如实说开源 + 我的二次开发"，但当前零改动。顺序反了，后面所有课都是在
  雕刻一个空壳。这是把"能讲"建立在"做过"之上。
- **教学法**：每课的技能训练一律用「主动回忆」（通过 `assets/recall-quiz.js`），不用选择题。
  读一遍文档产生的只是 fluency strength，面试现场需要的是 storage strength。
- **诚实优先于好听**：若某个话术会让用户在面试里说假话，宁可给一个天花板更低但站得住的版本。

## 待办 / 后续计划

- [ ] **GLOSSARY**：建 `reference/术语表.html`，统一全课程的术语口径（Redis Stream / pgvector / HNSW /
      COSINE / SSE / VAD / ASR / TTS / RAG / Skill / Provider / Stream 模板 …）。下一课交付。
- [ ] 课程骨架（**2026-09-15 重排：理解优先，不动代码**）：
  - 0 地基：**0001 可信框架与项目陈述** ✅
  - 1 理解骨架：**0003 跟一条真实请求走完全程** ✅ ← 当前
    · 0004 顶层架构与分层约束 · 0005 九模块地图与主干路由
  - 2 理解选型：0006 六个关键设计决策的完整话术
  - 3 理解细节：0007 Redis Stream 异步模板深入（幂等 / pending / 多实例） · 0008 RAG 检索链路
    · 0009 统一评估引擎与 Skill 出题 · 0010 语音面试链路
  - 4 表达训练：0011 从项目出发的 Java 八股 · 0012 压力追问模拟
  - 5〔**挂起 · 由 bojack 自行决定是否启动**〕**0002 第一刀：恢复被禁用的测试** ——
    材料已写好可随时用。他明确表示「在理解之前不想做贡献」，这是对的学习顺序，不强推。
    `end_phase` 作为备选第二刀一并挂起。
- [x] ~~待核实 `end_phase` 两种改法~~ → 已核实：仅 `WebSocketControlMessage.java:18` 注释提及，
      handler（`VoiceInterviewWebSocketHandler` 880 行附近）switch 只有 `submit`/`end_interview`/`start_phase`。
      **已随第 2 课一并挂起。**

## 教学顺序的判断（重要）

2026-09-15 我原定的顺序是「先攒真实改动 → 再建骨架 → 再练表达」，理由是面试可信度。
bojack 提出「让我先理解项目而不是做贡献，在理解之前我不想做贡献」——**他是对的，我调整了**：

- 没有上下文就无法做出**自己的**判断；让他在读懂模块之前改它的测试，等于让他照抄我的结论，
  面试官一追问「你为什么这么改」就露底。那不是弹药，是新的空壳。
- 正确的顺序是「理解 →（可选）动手 → 表达」。改动只有在**自己发现问题**之后才有含金量。
- 但**风险没有消失**：他选的框架是「如实说开源 + 我的二次开发」。在真正改动之前，
  第 1 课里的**第二段逐字稿必须留空**，只能讲第一段和第三段。这一点已在第 1 课里标注，
  后续每次涉及话术都要守住。


## 重大发现：语音面试模块 73% 的测试是空转的（2026-09-15）

- 实测：`./gradlew :app:test --no-daemon --tests "*VoiceInterview*"` → 60 个测试，**通过 16 / 跳过 44 / 失败 0**。
- 44 个跳过全部来自 4 个 `@Disabled` 类（多为空占位 `placeholder()`，无断言）：
  `VoiceInterviewServiceTest`（11 个 @Nested，43 个）+ `DashscopeLlmServiceTest` +
  `VoiceInterviewServicePauseTest` + `VoiceInterviewPromptServiceTest`。
- 注释里的原因：重构（commit `e6bebe7` / `f87f435` / `c433a50`）改了构造函数签名与依赖注入，
  测试的 mock 未跟进 → NPE → 直接 `@Disabled` 绕过。
- **根因（本课核心技术点）**：旧测试用 `@InjectMocks` 只声明 4 个 mock，而
  `VoiceInterviewService`（`@RequiredArgsConstructor`）构造函数要 **7 个**依赖。
  `@InjectMocks` 对解析不出的参数**静默传 null**，运行期才 NPE。
  正在跑的 `VoiceInterviewSummaryPersistenceTest` 用手动 `new` + 全 7 个 `@Mock`，是标准模板。
- **注释本身不准确**：`@Disabled` 理由只提缺 `LlmProviderRegistry`，实际缺 3 个
  （还有 `evaluationRepository`、`evaluateStreamProducer`）。这是可讲的「文档与事实不一致」观察。
- 副作用：`BUILD SUCCESSFUL` 具有欺骗性——`@Disabled` 不产生失败。面试可主动讲这个区别。


## 已知素材出处

- `.arch-docs/ARCHITECTURE.md`、`LEARNING_PATH.md`、`modules/*.md` —— 本项目自建架构文档，
  经 3 个独立子代理交叉核实，综合 93/100（见 `_review/SUMMARY.md`）。**但评审本身也有错**：
  SUMMARY 里被"驳回"的那条（称 `autoCreateBucket` 全库零命中）经我复核是**评审自己错**——
  该字段确实存在于 `app/src/main/java/interview/guide/common/config/StorageConfigProperties.java:23`。
  引用 `.arch-docs` 时保持这个警惕。
