# InterviewGuide 面试准备 · 资源清单

> 原则：本项目是自建的、可读源码的实体，**最高信任度的资源就是源码本身**。
> 外部资源只用来补齐"为什么这么设计"的背景知识，不用来替代读代码。

## Knowledge

### 一、一手来源（本仓库内，最高信任度）

- **源码**：`app/src/main/java/interview/guide/`（后端约 2.5 万行）、`frontend/src/`（约 1.8 万行）
  ⚠️ **面试里唯一能救命的资源。** 所有话术最终都要能落到具体类名与方法上。
- [`README.md`](../../README.md)
  作者自己的定位、技术栈表、**技术选型 FAQ**（为什么 pgvector、为什么 Redis Stream、为什么 Gradle）——
  这三条问答是理解架构取舍的最短路径，也是面试官最可能问的三个"为什么"。
- [`.arch-docs/ARCHITECTURE.md`](../../.arch-docs/ARCHITECTURE.md)
  顶层架构、数据流时序图、9 模块索引、6 条关键设计决策、部署与测试命令。**课程的主要 ground truth。**
- [`.arch-docs/LEARNING_PATH.md`](../../.arch-docs/LEARNING_PATH.md)
  5 分钟 / 30 分钟 / 深入贡献 三条阅读路径 + 改代码前检查清单 + debug 流程。
- [`.arch-docs/modules/<name>.md`](../../.arch-docs/modules/)
  9 份模块档案，每份含「定位 / 关键文件清单 / 核心类 / 内部架构 / 数据流 / 设计决策 / HTTP 路由」。
- [`AGENTS.md`](../../AGENTS.md)
  项目自己的分层约束与 Never Do 清单。**面试被问"你们团队的代码规范"时，这里是现成答案。**

### 二、技术背景（用来回答"为什么"）

- [Spring AI 官方文档](https://docs.spring.io/spring-ai/reference/)
  用 for：`ChatClient`、`Advisor`、`VectorStore` 抽象的理解。本项目所有文本 LLM 调用都走这套。
- [pgvector README](https://github.com/pgvector/pgvector)
  用 for：HNSW vs IVFFlat 索引、`vector_cosine_ops`、距离算子 `<->` / `<=>` 的准确语义。
  本项目向量维度 1024、COSINE、HNSW —— 被追问索引选型时的依据。
- [Redis Streams 官方文档](https://redis.io/docs/latest/develop/data-types/streams/)
  用 for：`XADD` / `XREADGROUP` / `XACK` / Pending Entries List / 消费者组语义。
  本项目 `AbstractStreamProducer` + `AbstractStreamConsumer` 就是这层的封装。
- [JEP 444: Virtual Threads](https://openjdk.org/jeps/444)
  用 for：本项目开启 `spring.threads.virtual.enabled`。被问"为什么 Java 25 用虚拟线程适合这个场景"
  时，答案是「I/O 密集 + 大量 SSE / WebSocket 长连接」，虚拟线程让阻塞式写法也能高并发。
- [Apache Tika 支持的格式](https://tika.apache.org/2.9.2/formats.html)
  用 for：简历与知识库文档解析的格式覆盖面。
- [iText 8 字体与 CJK 支持](https://itextpdf.com/)
  用 for：项目内置 `ZhuqueFangsong-Regular.ttf` —— "PDF 导出中文乱码"是经典追问点。

### 三、作者侧材料（谨慎使用）

- [《SpringAI 智能面试平台 + RAG 知识库》配套教程](https://javaguide.cn/zhuanlan/interview-guide.html)
  ⚠️ **付费内容，且 README 明示其目标之一就是"教你如何在面试中讲清楚这个项目"。**
  用 for：了解作者本人认为的亮点在哪。**不要用 for 背话术** —— 这是公开材料，面试官也可能看过，
  背出来反而暴露"你只是在复述作者的话"。用它来对照"我自己的理解跟作者差在哪"。

## Wisdom (Communities)

- **项目 GitHub Issues / PR**（`https://github.com/Snailclimb/interview-guide`）
  ⭐ **最推荐的场。** 提一个真实的 issue 或 PR（哪怕就是 `end_phase` 那类不一致），
  你会拿到维护者的真实反馈 —— 这是能写进面试回答的第一手经历，
  比任何面经都硬："我给上游提过一个 PR，维护者认为……"
- [牛客网 · 面经区](https://www.nowcoder.com/)
  用 for：看真实的 Java 后端项目轮都问了什么，校准本课程的重点。
- [JavaGuide 网站](https://javaguide.cn/)
  用 for：作者的技术文章，尤其是 Gradle、Kafka 选型那几篇 —— 与本项目的技术选型 FAQ 同源。
- **模拟面试伙伴**：找一个真人对练。
  用 for：本课程的自测只能验证"你想得起"，验证不了"你说得清"。**这是唯一的验证方式。**

## Gaps（当前缺失，后续补）

- 没有找到高质量的、针对"项目经历被深挖"的中文方法论材料 —— 课程里的框架是我基于风险模型推导的，
  缺一个外部权威背书。
- 缺少本项目的**线上故障/性能数据**（QPS、延迟分布、token 成本）。若面试官追问量级，
  目前只能给 README 里的「首包延迟 200ms」这一个数字。**待从源码与配置中反推可讲的量级指标。**
- 语音链路（WebSocket + 千问3 ASR/TTS）的第三方资料偏厂商营销，缺独立评测。慎引。
