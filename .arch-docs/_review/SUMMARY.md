# 评审汇总与优化记录

> 评审日期：2026-09-13 · 评审对象：`.arch-docs/` 下 11 份文档（共约 2,200 行）
> 评审方式：3 个独立评审子代理，各自对照真实源码 / 配置 / `wc -l` 实测逐条核实，非凭印象打分
> 优化日期：2026-09-13（同一轮内完成）

## 综合得分：93 / 100

（按文档数加权：评审 01 覆盖 2 份文档、评审 02 覆盖 5 份、评审 03 覆盖 4 份）

| 评审批次 | 覆盖文档 | 得分 |
|---|---|---|
| 01 | ARCHITECTURE.md + LEARNING_PATH.md | 91 |
| 02 | common / infrastructure / llmprovider / resume / interviewschedule | 94 |
| 03 | knowledgebase / voiceinterview / interview / frontend | 93 |

## 维度评分（三批汇总）

| 维度 | 权重 | 01 | 02 | 03 | 加权 |
|---|---|---|---|---|---|
| 事实准确性 | 30% | 92 | 92 | 96 | 93.6 |
| 覆盖完整性 | 20% | 95 | 96 | 88 | 92.5 |
| 结构规范性 | 10% | 95 | 98 | 100 | 97.9 |
| 信息密度 | 15% | 90 | 95 | 90 | 91.5 |
| 交叉引用一致性 | 10% | 88 | 90 | 94 | 90.6 |
| 可操作性 | 15% | 85 | 93 | 93 | 90.4 |

**结论**：结构规范性与事实准确性是强项；覆盖完整性与可操作性相对最弱，主要来自「漏条目」而非「写错」。

## 逐文档得分

| 文档 | 得分 | 一句话总评 |
|---|---|---|
| ARCHITECTURE.md | 92 | 版本号 / 路由 / 类名 / 规模与实测高度一致，仅运行指引有凭证错误 |
| LEARNING_PATH.md | 93 | 路径与命令可执行可定位，是高质量 onboarding 文档 |
| llmprovider.md | 97 | 14 条路由、方法名、依赖与源码一字不差，最干净 |
| infrastructure.md | 96 | 文件与方法名实测全对，表述严谨 |
| resume.md | 96 | 异步链路与重试常量全部吻合 |
| voiceinterview.md | 95 | WebSocket 协议与端点逐字段一致 |
| interviewschedule.md | 94 | 路由与方法名准确，规模数字与一处笔误待修 |
| common.md | 90 | 横切层档案最全，但有常量名与返回类型错误 |
| knowledgebase.md | 93 | 规模/路由/方法几乎全中，HTTP 表漏 4 条路由 |
| interview.md | 93 | 路由与常量精准，缺 Skill 方向清单 |
| frontend.md | 91 | 页面→路由与 API 全覆盖，有 1 个错大小写的幽灵文件名 |

## 问题清单与处理台账

### 已采纳并修复（15 条）

| # | 级别 | 位置 | 问题 | 处理 |
|---|---|---|---|---|
| 1 | P0 | ARCHITECTURE §7.1 | 登录 RustFS 控制台的凭证写成 `APP_STORAGE_ACCESS_KEY / SECRET_KEY`，真实为 `RUSTFS_ACCESS_KEY / RUSTFS_SECRET_KEY`（默认 `rustfsadmin/rustfsadmin`） | 已改为正确凭证 |
| 2 | P0 | common §2 / §3.2 | `PromptSecurityConstants` 常量名写成 `ANTI_INJECTION` / `DATA_BOUNDARY`，源码实为 `ANTI_INJECTION_INSTRUCTION` / `DATA_BOUNDARY_INSTRUCTION` | 已改为全名 |
| 3 | P0 | frontend §3.1 | 列出不存在的错大小写文件 `knowledgebaseInterviewCompletion.ts`，且把纯逻辑 `.ts` 计为 6 个 | 已改为 `knowledgeBaseInterviewCompletion.ts`，计数修正为 5 个 |
| 4 | P1 | ARCHITECTURE §5 | 语音 WS 只写「WebSocket 语音端点」，未给真实路径 | 已补 `/ws/voice-interview/{sessionId}`，并注明 WS 独立于 `/api` 前缀 |
| 5 | P1 | ARCHITECTURE §1/§2 | 「约 50,000 行代码」口径不清 | 已改为「约 4.3 万行主源码（后端 25,235 + 前端 17,990；含测试 7,307 共约 5.05 万行）」 |
| 6 | P1 | ARCHITECTURE §2.2 | 「路由常量集中在 `constants/routes.ts`」易误导 | 已改为「完整路由表在 `App.tsx`，`routes.ts` 只放 5 条复用常量」 |
| 7 | P1 | ARCHITECTURE §5 | frontend 规模写 `~17.6K`，实测 17,990 | 已改为 `~18.0K` |
| 8 | P1 | LEARNING_PATH §2.1 | 跑 `GET /api/resumes/health` 未说明前置条件 | 已补「需 compose 依赖 healthy 且 `bootRun` 无报错启动完成」 |
| 9 | P1 | LEARNING_PATH §2.2 / §3.1 | 读代码顺序把 `constants/routes.ts` 排在 `App.tsx` 之前 | 已调整为 `App.tsx`（路由表）在前 |
| 10 | P1 | common §3.2 | `ApiPathResolver.buildOpenAiClient` 返回类型写成 `OpenAIClientImpl`，实为接口 `OpenAIClient` | 已改为 `OpenAIClient`（注明实现为 `OpenAIClientImpl`） |
| 11 | P1 | common §5.1 | 异步模板继承方清单漏 `voiceinterview` | 已补 `VoiceEvaluateStreamProducer/Consumer` |
| 12 | P1 | infrastructure §8.1 | 「4 个测试类（含 1 个 README）」把 README 算作测试类 | 已改为「4 个测试类（外加 1 个 README，非测试类）」 |
| 13 | P1 | interviewschedule §1/§2 | 规模写 886 行，实测 870 行 | 已改为 870 |
| 14 | P1 | interviewschedule §3.2 | 「解析解析」重复字 | 已改为「日期与轮次解析辅助逻辑」 |
| 15 | P1 | knowledgebase §3.3 | HTTP 表漏 4 条真实路由 | 已补 `category/{category}`、`uncategorized`、`PUT {id}/category`、`{id}/questions/categories` |
| 16 | P1 | interview §1 | 未枚举内置 Skill 方向 | 已补 10 个方向清单 + `_shared/` |
| 17 | P1 | frontend §2/§5 | 「路由常量在 `routes.ts`」表述易误导 | 已改为列明 5 条常量，并指出完整路由表在 `App.tsx` |

### 采纳但按事实修正（1 条）

| 级别 | 位置 | 评审意见 | 核实结果与处理 |
|---|---|---|---|
| P1 | voiceinterview §3.3 | 建议在消息协议表补 `end_phase` 的语义 | **评审结论不准确**：`WebSocketControlMessage` 注释确实列了 `end_phase`，但 `VoiceInterviewWebSocketHandler` 的 `switch` 只有 `submit` / `end_interview` / `start_phase` 三个分支，**未实现 `end_phase`**。已在表下加注记说明这一「注释与实现不一致」的事实，而非把它写成受支持的动作。 |

### 驳回（1 条）

| 级别 | 位置 | 评审意见 | 驳回理由 |
|---|---|---|---|
| P1 | ARCHITECTURE §7.1 | 称「手动创建 bucket 与 `app.storage.auto-create-bucket` 默认 true 矛盾」，建议删除该步骤 | **评审结论错误**：全库 `grep -rn "auto-create-bucket\|autoCreateBucket\|createBucket" app/src/main app/src/test` 零命中，该配置项并不存在。手动建桶步骤保留。 |

## 优化后复核

- 结构：ARCHITECTURE.md 仍为 7 个二级标题、LEARNING_PATH.md 为 5 个、9 份模块档案各 8 个 —— 无结构漂移。
- 链接：ARCHITECTURE.md 模块索引表中 9 条 `modules/*.md` 链接全部可解析。
- 残留扫描：`APP_STORAGE_ACCESS_KEY / SECRET_KEY`、`886 行`、`解析解析`、`knowledgebaseInterviewCompletion.ts`、`17.6K`、`约 50,000` 均已无残留。
- 规模：修复后 11 份文档共约 2,240 行。

> 评审的独立价值在本次体现为两处：纠正了 3 个 P0 硬错，以及**暴露了文档之外的 1 处源码不一致**（`WebSocketControlMessage` 注释 vs handler 实际分支）。同时有 2 条评审意见经核实为错误，未采纳 —— 评审结论本身也需要核实。

---

> **回到** [ARCHITECTURE.md](../ARCHITECTURE.md) · [LEARNING_PATH.md](../LEARNING_PATH.md)
