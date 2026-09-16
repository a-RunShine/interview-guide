# 评审报告 03 — knowledgebase / voiceinterview / interview / frontend

> 评审方式：对照真实源码逐一 `grep`/`ls`/`wc -l` 核实，非凭印象。
> 核查基线：代码库根 `/Users/sunshine/Desktop/interview-guide`。

## 综合得分：93 / 100
（加权：事实准确性 30%、覆盖完整性 20%、结构规范性 10%、信息密度 15%、交叉引用一致性 10%、可操作性 15%）

## 维度评分
| 维度 | 得分 | 扣分理由 |
| --- | --- | --- |
| 事实准确性 | 96 | 绝大多数类名/方法/路由/行数与源码逐一吻合；唯一硬伤是 frontend.md §3.1 列了不存在的错大小写文件名 `knowledgebaseInterviewCompletion.ts`，且把纯逻辑 .ts 文件数算成 6（实为 5）。 |
| 覆盖完整性 | 88 | knowledgebase §3.3 漏 4 个真实 HTTP 路由；interview 未枚举 10 个 Skill 方向；其余覆盖充分。 |
| 结构规范性 | 100 | 四份文档均恰好 8 个二级标题，表头命名统一，无结构漂移。 |
| 信息密度 | 90 | 个别小节偏长（voiceinterview §1、frontend §3.2），但无套话与编造，水分低。 |
| 交叉引用一致性 | 94 | 模块名/规模数字与 ARCHITECTURE.md 自洽（仅 frontend 行数 17.99K vs ARCHITECTURE 17.6K 轻微取整差）；文末「回到 ARCHITECTURE.md」链接有效。 |
| 可操作性 | 93 | 新人能据类名/路由定位代码；frontend 错大小写文件名会让人误以为有两个文件。 |

## 逐文档评分
| 文档 | 得分 | 一句话总评 |
| --- | --- | --- |
| knowledgebase.md | 93 | 规模/路由/方法几乎全部命中，仅 HTTP 接口表缺 4 条真实路由。 |
| voiceinterview.md | 95 | WebSocket 协议、行数、端点与源码逐字段一致，质量最高。 |
| interview.md | 93 | 路由/常量/方法名精准，但未列出内置 Skill 方向清单。 |
| frontend.md | 91 | 页面→路由、API 客户端全覆盖，但多出 1 个错大小写的「幽灵」逻辑文件。 |

## 必须修复（P0）
- frontend.md → §3.1 → 声称 `pages/` 含「6 个纯逻辑 .ts 文件」并列出 `knowledgebaseInterviewCompletion.ts`。（证据：真实 `frontend/src/pages/` 仅 5 个非测试逻辑 `.ts`——`interviewEntry.ts`、`interviewHistoryStats.ts`、`knowledgeBaseInterviewCompletion.ts`、`questionGenerationStatus.ts`、`voiceEvaluationStatus.ts`；`knowledgebaseInterviewCompletion.ts`（小写 b）并非独立文件，macOS 大小写不敏感使其与真实文件 `knowledgeBaseInterviewCompletion.ts` 同 inode；源码路径 `frontend/src/pages/knowledgeBaseInterviewCompletion.ts`。）→ 改法：将第 6 项 `knowledgebaseInterviewCompletion.ts` 更正为 `knowledgeBaseInterviewCompletion.ts`，并把计数改为「5 个纯逻辑 .ts 文件」，与 §5.1 行 117 写法统一。

## 建议改进（P1）
- knowledgebase.md → §3.3 → HTTP 接口表漏 4 条真实路由。（证据：源码 `KnowledgeBaseController` 含 `GET /api/knowledgebase/category/{category}`(L125)、`GET /api/knowledgebase/uncategorized`(L133)、`PUT /api/knowledgebase/{id}/category`(L141)；`KnowledgeBaseInterviewController` 含 `GET /api/knowledgebase/{id}/questions/categories`(L52)，均不在文档表内。）→ 改法：补入 §3.3，保持「类级前缀+方法级路径」完整。
- interview.md → §1 / §3.1 → 未枚举内置 Skill 方向列表。（证据：`app/src/main/resources/skills/` 实际有 10 个方向目录 `ai-agent-dev`、`algorithm`、`ali-backend`、`bytedance-backend`、`frontend`、`java-backend`、`java-backend-tencent`、`python-backend`、`system-design`、`test-development` 加 `_shared`；文档仅示例 `java-backend`。）→ 改法：在 §1 或新增小节列出 10 个方向，与 ARCHITECTURE.md L193「10+ 面试方向」自洽。
- voiceinterview.md → §3.3 → 上行/通用 control `action` 未列 `end_phase`。（证据：`dto/WebSocketControlMessage.java` 注释中 `action` 取值含 `end_phase`（与 `start_phase` 对应），handler 也使用。）→ 改法：在消息协议表中补充 `end_phase` 的语义（阶段结束），避免读者误以为只有三个 action。
- frontend.md → §1 / §2 → 「路由常量集中在 `constants/routes.ts`」表述易误导。（证据：`frontend/src/constants/routes.ts` 实际仅 5 条常量 `interview`/`interviewCreate`/`interviewSession`/`resumeUpload`/`knowledgebaseUpload`；全部页面路由定义在 `App.tsx` L199-254。ARCHITECTURE.md L42 同口径也略偏。）→ 改法：说明真实路由表在 `App.tsx`，`routes.ts` 只是部分复用常量。

## 写得好的地方
- knowledgebase §7「临时 job → 提升」模式解释 pgvector 写入，给出替代方案与失败回退，落地性强、无空话。
- voiceinterview §3.3 把 WebSocket 上下行消息类型逐条列出 `type`/`字段`/`含义`，与 `WebSocketControlMessage`、`WebSocketSubtitleMessage` 及 handler 实际字符串（welcome/asr_ready/audio_chunk/pause_timeout 等）完全对应。
- 四份文档规模数字与 `wc -l` 实测逐一吻合：KB 49 文件/5514 行、VI 28 文件/5448 行、IV 27 文件/4048 行、FE 85 文件/17990 行。
- interview §6 sequenceDiagram 与真实 Redis Stream 异步评估链路一致，图中方法名（`getHistoricalQuestions`、`sendEvaluateTask`）可对应源码。
- frontend §3.1 页面→路由表与 `App.tsx` 实测路径一一对应，并明确标注 5 个纯逻辑 `.ts` 被 `node --test` 直接运行，便于新人定位测试入口。
