# 评审报告 02 — common / infrastructure / llmprovider / resume / interviewschedule

> 评审基准：代码库根 `/Users/sunshine/Desktop/interview-guide`，对照真实 `.java` 源码、`app/src/test` 测试树、前端 `frontend/src` 与 `ARCHITECTURE.md` 逐项核实。
> 核实方式：对 5 份文档列出的类名 / 方法名 / HTTP 路由 / 行数文件数 / 测试文件 / 依赖关系全部用 `grep`/`ls`/`wc -l` 与直接读源码验证。

## 综合得分：94 / 100
（加权：事实准确性 30%、覆盖完整性 20%、结构规范性 10%、信息密度 15%、交叉引用一致性 10%、可操作性 15%）

## 维度评分
| 维度 | 得分 | 扣分理由 |
| --- | --- | --- |
| 事实准确性 | 92 | 类名/方法名/路由/行数/测试文件经实测全部吻合；扣分项：common 中 `PromptSecurityConstants` 常量名与 `ApiPathResolver` 返回类型写错，interviewschedule 行数 886 实为 870。 |
| 覆盖完整性 | 96 | 5 份均含模板 8 个小节，关键文件与外部接口无遗漏；唯一小缺：common §5.1 异步模板"被继承方"清单漏掉 voiceinterview。 |
| 结构规范性 | 98 | 每份恰好 8 个二级标题，表格命名规范，mermaid 图齐全。 |
| 信息密度 | 95 | 内容密集、套话少，示例可直接复用；个别措辞略冗（如 common §5.1 段落式清单）。 |
| 交叉引用一致性 | 90 | 与 ARCHITECTURE.md 模块规模/端点基本自洽，`../ARCHITECTURE.md` 链接有效；扣分项：common 与 interviewschedule 对 `PromptSecurityConstants` 常量命名不一致（DATA_BOUNDARY vs DATA_BOUNDARY_INSTRUCTION）。 |
| 可操作性 | 93 | 文件路径、方法签名、curl/Java 示例充分，新人可定位；扣分项：常量标识符不精确会令 grep 失败。 |

## 逐文档评分
| 文档 | 得分 | 一句话总评 |
| --- | --- | --- |
| common.md | 90 | 横切层档案最全，但 `PromptSecurityConstants` 常量名与 `ApiPathResolver` 返回类型写错、异步继承清单漏 voiceinterview。 |
| infrastructure.md | 96 | 文件/方法名经实测全部正确，表述严谨，仅 §8.1 测试数措辞有小歧义。 |
| llmprovider.md | 97 | 14 条路由、方法名、依赖（含 voiceinterview 三类）、测试行数均与源码一字不差，最干净。 |
| resume.md | 96 | 7 条路由、异步链路、方法名全部吻合，无专属测试的描述属实。 |
| interviewschedule.md | 94 | 路由与方法名准确，但规模数字 886（实 870）偏高、§3.2 有"解析解析"笔误。 |

## 必须修复（P0）
- common.md → §2 与 §3.2 → `PromptSecurityConstants` 列出的常量名 `ANTI_INJECTION` / `DATA_BOUNDARY` 在源码中不存在（证据：`ai/PromptSecurityConstants.java` 实际为 `ANTI_INJECTION_INSTRUCTION` 与 `DATA_BOUNDARY_INSTRUCTION`；且 interviewschedule.md §5.2 用的就是正确全名）。→ 改法：将两个常量名改为 `ANTI_INJECTION_INSTRUCTION` / `DATA_BOUNDARY_INSTRUCTION`，保持与 interviewschedule.md 及源码一致。

## 建议改进（P1）
- common.md → §3.2 → `ApiPathResolver.buildOpenAiClient(...)` 返回类型写为 `OpenAIClientImpl`（证据：`ai/ApiPathResolver.java` 方法签名返回的是接口 `OpenAIClient`）。→ 改法：改为 `OpenAIClient`。
- common.md → §5.1 → "被谁依赖"清单只列 `resume / knowledgebase / interview` 继承 `AbstractStreamProducer/Consumer`（证据：`grep -rEn "extends AbstractStream(Producer|Consumer)"` 还命中 `modules/voiceinterview/listener/VoiceEvaluateStreamProducer|Consumer`）。→ 改法：补上 `voiceinterview`，改为"resume / knowledgebase / interview / voiceinterview"。
- infrastructure.md → §8.1 → 措辞"下 4 个测试类（含 1 个 README）"把 README 算进测试类（证据：`infrastructure/file/` 实际为 4 个 `*Test.java` + 1 个 `README.md`，README 非测试类）。→ 改法：改为"4 个测试类 + 1 个 README"。
- interviewschedule.md → §1/§2 → 规模写"约 886 行"（证据：`wc -l` 实测 `modules/interviewschedule` 共 870 行）。→ 改法：改为"约 870 行"（ARCHITECTURE.md 的 `~0.9K` 仍成立）。
- interviewschedule.md → §3.2 → "解析解析辅助逻辑（`parseDateTime`…）"有重复字"解析解析"（证据：原文 §3.2 首句）。→ 改法：删去一个"解析"。

## 写得好的地方
- llmprovider.md §3.3 的 14 条 HTTP 路由（类级 `@RequestMapping("/api/llm-provider")` + 方法级路径拼接）与 `LlmProviderController.java` 逐条吻合，含 voice 子路由与限流注解。
- resume.md §6 异步链路与源码一致：`AnalyzeStreamProducer.sendAnalyzeTask` → `AbstractStreamProducer.sendTask` → Redis Stream → `AnalyzeStreamConsumer.processBusiness` → `gradingService.analyzeResume`，且 `MAX_RETRY_COUNT=3` 在 `AsyncTaskStreamConstants` 与抽象消费者中均核实为 3。
- 5 份文档的"规模"数字（文件数/行数）与 `find ... | wc -l` 实测完全一致（common 34/3087、infra 14/2573、llmprovider 17/1883、resume 16/1781、interviewschedule 11/870）。
- 测试覆盖章节诚实地标注 resume / interviewschedule 无专属测试（已用 `find app/src/test` 证实），并列出共享夹具 `test-files/sample-resume.md|txt`，未编造测试文件。
- 依赖关系（被谁依赖）引用的前端文件 `frontend/src/api/*.ts`、`pages/*.tsx`、`hooks/useInterviewSchedule.ts` 全部真实存在，未发现虚构调用方。
