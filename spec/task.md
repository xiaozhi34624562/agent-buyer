# Agent Buyer V2 / V3 Tasks

## 目录

- 多 Agent 开发流程
  - 角色职责
  - 里程碑顺序
  - 并行规则
  - Review Gate
- V2.0 任务：Multi-provider + Context Compact
- V2.1 任务：Skill + SubAgent
- V2.2 任务：Multi-instance + Pub/Sub + ToDo + Interrupt
- V2 真实 E2E 追加验证任务
- V3 任务：Agent Buyer Console
  - V3-M1 Backend Console API
  - V3-M2 Frontend Shell
  - V3-M3 Real Data Integration
  - V3-M4 Chat + SSE
  - V3-M5 Hardening / Demo
- 任务边界
- 总体验收标准

本文档记录 V2 与 V3 全量任务拆分。V2 不一次性并行交付，必须按 `V2.0 -> V2.1 -> V2.2` 顺序推进；V3 在 V2 及后续 hardening 任务完成后推进，必须按 `V3-M1 -> V3-M2 -> V3-M3 -> V3-M4 -> V3-M5` 顺序交付。每个里程碑内部可以按依赖并行开发，但必须完成该里程碑的 review gate 后，才能进入下一个里程碑。

状态取值：

- `PENDING`：尚未开始
- `IN_PROGRESS`：正在开发
- `BLOCKED`：被前置任务或外部条件阻塞
- `DONE`：已完成并验证

## 多 Agent 开发流程

V2 继续采用“主 agent 调度 + sub agent 并行开发 + review agent 独立审核”的工作流。

### 角色职责

| 角色 | 职责 | 不做什么 |
|---|---|---|
| 主 agent | 维护 `task.md` 状态、选择可并行任务、分配任务、约束写入范围、集成 sub agent 结果、运行最终验证、更新 `progress.md` | 不跨里程碑提前开发；不把多个互相冲突的大任务同时分发；不在 review 未通过时标记 DONE |
| sub agent | 负责一个明确任务或一个小任务包的具体开发，包含代码、测试和必要文档；完成后报告改动文件、测试结果、遗留风险 | 不修改未分配文件；不回滚其他 agent 的改动；不自行扩大任务范围 |
| review agent | 对 Java/Spring/MyBatis/Redis/Lua/SQL/YAML 变更使用 `java-alibaba-review` skill，对 React/TypeScript/前端代码使用 `front-alibaba-review` skill，输出 P0-P3 findings 和测试缺口 | 不直接改代码；不把风格问题伪装成阻塞项 |

### 里程碑顺序

| 里程碑 | 内容 | 开始条件 | 完成条件 |
|---|---|---|---|
| V2.0 | Multi-provider（DeepSeek + Qwen）+ Context compact | V1a 全部完成 | `V20-GATE` 为 `DONE` |
| V2.1 | Skill 渐进式加载 + SubAgent / AgentTool | `V20-GATE` 为 `DONE` | `V21-GATE` 为 `DONE` |
| V2.2 | 多实例部署 + Pub/Sub + ToDo + Interrupt | `V21-GATE` 为 `DONE` | `V22-GATE` 为 `DONE` |
| V3-M1 | Backend Console API | `V2-HITL-01` 为 `DONE` | `V3M1-GATE` 为 `DONE` |
| V3-M2 | Frontend Shell | `V3M1-GATE` 为 `DONE` | `V3M2-GATE` 为 `DONE` |
| V3-M3 | Real Data Integration | `V3M2-GATE` 为 `DONE` | `V3M3-GATE` 为 `DONE` |
| V3-M4 | Chat + SSE | `V3M3-GATE` 为 `DONE` | `V3M4-GATE` 为 `DONE` |
| V3-M5 | Hardening / Demo | `V3M4-GATE` 为 `DONE` | `V3-GATE` 为 `DONE` |

强约束：

- `V20-GATE` 完成前，不允许开始任何 `V21-*` 任务。
- `V21-GATE` 完成前，不允许开始任何 `V22-*` 任务。
- `V3M1-GATE` 完成前，不允许开始任何 `V3M2-*` 任务。
- `V3M2-GATE` 完成前，不允许开始任何 `V3M3-*` 任务。
- `V3M3-GATE` 完成前，不允许开始任何 `V3M4-*` 任务。
- `V3M4-GATE` 完成前，不允许开始任何 `V3M5-*` 任务。
- 每个 gate 必须完成相关测试、README / Postman / spec 同步，以及对应 skill 的独立审核：Java 变更使用 `java-alibaba-review`，前端变更使用 `front-alibaba-review`。

### 并行规则

- 只有 `blockedBy` 全部为 `DONE` 的任务才能进入开发。
- 并行任务必须有明确、尽量不重叠的写入范围。
- 如果两个 sub agent 都需要修改同一个核心文件，例如 `AgentTurnOrchestrator`、`LlmAttemptService`、`PromptAssembler`、`LuaRedisToolStore`，主 agent 必须拆分顺序或指定唯一 owner。
- sub agent 完成后先由主 agent 做集成检查，再交给 review agent 审核。
- review agent 发现 P0/P1/P2 时，该任务不能标记为 `DONE`；必须修复后重新 review。
- P3 可以由主 agent 判断是否本轮修复，但需要记录在 `progress.md`。
- 每个任务完成时必须更新 `task.md` 状态和 `progress.md` 过程记录。

### Review Gate

每个涉及代码变更的任务，都必须经过 review agent 审核。审核规则：

- Java/Spring/MyBatis/Redis/Lua/SQL/YAML 变更：使用 `java-alibaba-review`
- React/TypeScript/CSS/前端测试变更：使用 `front-alibaba-review`

审核重点（Java）：

- 安全和权限：run 归属、水平越权、工具权限放大、skill 路径逃逸、PII 泄漏。
- 状态一致性：MySQL trajectory、Redis active state、provider replay、tool result pairing、SubAgent parent link。
- 并发和资源：executor、lease、Pub/Sub fallback、SubAgent slot reserve、interrupt、timeout、late result。
- 异常和日志：fail closed、错误码、budget exceeded、敏感信息、可排障性。
- MyBatis/MySQL：索引、参数绑定、JSON 字段、migration 兼容性。
- 测试：核心行为必须有正向、边界和负向测试。

审核重点（前端）：

- 安全边界：confirmToken/adminToken/provider key 不渲染、敏感字段脱敏。
- SSE 协议：POST SSE parser 正确处理 split chunk、多 event、ping、非法 JSON；`tool_use` 使用 `toolName` 字段并兼容历史 `name`。
- 状态管理：run list/timeline/runtime/chat 状态联动正确；terminal run 禁用 interrupt/abort。
- React 规约：组件职责清晰、hooks 边界合理、测试覆盖关键路径。
- 响应式布局：桌面三栏无重叠、移动端 tabs 正确渲染。

任务验收必须满足：

- sub agent 自测通过。
- review agent 无未解决 P0/P1/P2 finding。
- 主 agent 集成后运行相关测试；gate 任务运行该里程碑要求的完整回归。
- `task.md`、`progress.md` 已同步更新。

## V2.0 任务：Multi-provider + Context Compact

目标：完成 DeepSeek + Qwen provider registry、fallback 安全边界，以及 context compact 三阶段策略。验收信号：DeepSeek 故障可 fallback Qwen；50K token 上下文不 fail closed；summary compact 保留 `businessFacts/toolFacts/openQuestions`。

| ID | 任务 | 验收标准 | 状态 | blockedBy |
|---|---|---|---|---|
| V20-01 | 梳理 V2.0 实施基线与测试基线 | 记录当前 V1a 全量 `mvn test` 结果；确认 DeepSeek smoke、MySQL、Redis、本地配置可用；`progress.md` 记录基线 | DONE | none |
| V20-02a | 扩展 RunContext 持久化 provider 选型 | 新增 V7 Flyway migration：`agent_run_context` 增加 `primary_provider` / `fallback_provider` / `provider_options`；create run 写入；continuation 从 RunContext 读取，不再使用请求默认或当前配置默认；迁移可重复执行并通过 Flyway 校验 | DONE | V20-01 |
| V20-02 | 抽象 `LlmProviderAdapterRegistry` 与 provider profile 边界 | DeepSeek 通过 registry/profile 调用；业务代码不直接依赖具体 provider；provider 选型从 RunContext 读取；现有 DeepSeek 测试通过 | DONE | V20-02a |
| V20-03 | 接入 `QwenProviderAdapter` 与 `QwenCompatibilityProfile` | Qwen base-url/api-key/model 可配置；tools/messages/finish reason/usage/error mapping 有单元测试；Qwen stream tool delta 可组装为 `ToolCall` | DONE | V20-02 |
| V20-04 | 实现 `ProviderFallbackPolicy` 与 fallback attempt 语义 | 建连前网络错误、429、5xx 可 fallback；stream 已产生 tool delta 后不 fallback；fallback provider 选型从 RunContext 读取，不能从请求默认覆盖；fallback attempt 写 `agent_llm_attempt` 与 `agent_event` | DONE | V20-03 |
| V20-04a | RunStateMachine 增加 `PAUSED` 状态迁移 | `RunStateMachine` 接受 `RUNNING -> PAUSED`；`PAUSED` 可 continuation；合法/非法迁移单测覆盖；`CANCELLED` 仍不可 continuation | DONE | V20-01 |
| V20-05 | 引入 `AgentExecutionBudget` 的 MainAgent 与 run-wide 预算 | MainAgent user turn 最多 30 次 LLM call；run-wide 最多 80 次；触发后 run -> `PAUSED`，写 `MAIN_TURN_BUDGET` / `RUN_WIDE_BUDGET` event | DONE | V20-04,V20-04a |
| V20-06 | 实现 `ContextViewBuilder`，分离原始 trajectory 与 provider view | provider 请求前从 MySQL 原始 messages 构建 view；不修改原始 trajectory；compact 前后均调用 `TranscriptPairValidator` | DONE | V20-02 |
| V20-07 | 实现 `LargeResultSpiller` | 单个 tool result > 2000 token 时，provider view 保留头 200 token、尾 200 token，中间插入 `<resultPath>`；MySQL 原始结果不删除 | DONE | V20-06 |
| V20-08 | 实现 `MicroCompactor` | provider view 达到 50000 token 时，旧 tool result content 替换为 `<oldToolResult>`；不删除 tool result message；pair validator 通过 | DONE | V20-07 |
| V20-09 | 实现 `SummaryCompactor` 与 summary JSON schema | summary 包含 `summaryText`、`businessFacts`、`toolFacts`、`openQuestions`、`compactedMessageIds`；保留 system、前三条、最近消息预算规则 | DONE | V20-08 |
| V20-10 | 增加 `agent_context_compaction` 存储与查询 | 新增 V8 Flyway migration：创建 `agent_context_compaction` 表；记录 compaction strategy、before/after tokens、compactedMessageIds；trajectory query 可展示 compaction 记录 | DONE | V20-09 |
| V20-11 | V2.0 集成测试与负向测试 | 覆盖 Qwen profile、RunContext provider 复用、fallback 安全边界、50K context compact、summary fields、pair validation、PAUSED 状态迁移、budget exceeded | DONE | V20-05,V20-10 |
| V20-12 | V2.0 文档与配置收口 | README 标注 V2.0 状态；配置包含 provider、context、budget；Postman 或脚本补 provider/context smoke | DONE | V20-11 |
| V20-GATE | V2.0 hardening review gate | `mvn test` 通过；V2.0 相关 review agent 无 P0/P1/P2；`progress.md` 记录 V2.0 完成摘要；允许开始 V2.1 | DONE | V20-12 |

## V2.1 任务：Skill + SubAgent

目标：完成 Anthropic-style skill 渐进式加载、slash skill 注入、AgentTool / SubAgent 同步委派、child trajectory 与 SubAgent 预算。验收信号：`/skillName` 注入工作；SubAgent 继承 tool/skill 但不继承 history；child run trajectory 可独立查询。

| ID | 任务 | 验收标准 | 状态 | blockedBy |
|---|---|---|---|---|
| V21-01 | 实现 `SkillRegistry` 与 Anthropic skill 扫描 | 可扫描 `skills/*/SKILL.md` frontmatter；preview 只包含 `name + description`; 3 个业务 skill 可被索引 | DONE | V20-GATE |
| V21-02 | 实现 `SkillPathResolver` 路径安全 | `skill_view(skillName, skillPath)` 禁止 `..`、绝对路径、符号链接逃逸；路径逃逸返回稳定错误 | DONE | V21-01 |
| V21-03 | 实现 `SkillListTool` 与 `SkillViewTool` | `skill_list()` 无 userId 入参，userId 从 `ToolUseContext` 注入；`skill_view` 可返回完整 `SKILL.md` 或 skill 内文件 | DONE | V21-02 |
| V21-03a | PromptAssembler 注入 skill preview 列表 | system prompt 在 user info 与 tool-use guidance 之间加入用户可访问 skill preview（`name + description`）；preview 不包含 `SKILL.md` 内容；`systemPromptHash` 重新计算并有测试覆盖 | DONE | V21-03 |
| V21-04 | 实现 `SkillCommandResolver` slash skill 注入 | 用户消息含 `/skillName` 时，本轮 provider view 注入完整 `SKILL.md`；不改写 materialized system prompt；访问写 `agent_event` | DONE | V21-03a |
| V21-05 | 实现 slash skill budget fail closed | 超过 `agent.skills.max-per-message=3` 或 `max-token-per-message=8000` 时返回 `SKILL_BUDGET_EXCEEDED`；错误体包含命中 skill、预算、实际、超出量 | DONE | V21-04 |
| V21-06 | 设计并实现 `AgentTool` schema 与反滥用提示 | `AgentTool` 作为普通 Tool 注册；schema description 明确“高成本工具，请谨慎使用”；MainAgent system prompt 加 spawn hint | DONE | V21-03 |
| V21-07 | 增加 child run MySQL 字段与 DTO | 新增 V9 Flyway migration：`agent_run` 增加 `parent_run_id`、`parent_tool_call_id`、`agent_type`、`parent_link_status`; trajectory query DTO 暴露 `parentLinkStatus` | DONE | V21-06 |
| V21-08 | 实现 `SubAgentRegistry` 与 `SubAgentProfile` | 至少提供 `ExploreAgent` profile；子 Agent 默认继承 parent tool/skill 能力集合，但不继承 message history、ToDo、compact view | DONE | V21-06 |
| V21-09 | 实现 `SubAgentBudgetPolicy`、`ChildRunRegistry` 与 Redis Lua slot reserve | `reserve_child.lua` 原子检查 `max-spawn-per-run=2`、`max-concurrent-per-run=1`、`spawn-budget-per-user-turn=2`; 失败返回 `SUBAGENT_BUDGET_EXCEEDED`；同时实现 `ChildRunRegistry`，维护 parent ↔ child 运行期映射，作为 V22-12 cascade interrupt 的查询入口 | DONE | V21-07 |
| V21-10 | 实现 `release_child.lua` 与 child lifecycle | child terminal / timeout / interrupt / detach 释放 in-flight 计数；lifetime spawn 计数不释放；并发 reserve 只有一个成功 | DONE | V21-09 |
| V21-11 | 实现 `SubAgentRunner` 同步等待 | `AgentTool` 同步等待 child result summary，默认 3 分钟；成功只把 summary/状态/关键引用/childRunId 返回 parent | DONE | V21-08,V21-10 |
| V21-12 | 实现 child timeout 与 parent link status | 等待超过 3 分钟写 `DETACHED_BY_TIMEOUT` 并 interrupt child；parent interrupt / failed 分别写 `DETACHED_BY_INTERRUPT` / `DETACHED_BY_PARENT_FAILED` | DONE | V21-11 |
| V21-13 | 扩展 SubAgent LLM call budget | 每个 SubAgent user turn 最多 30 次 LLM call；超限返回 partial summary 并进入 `PAUSED`；写 `SUB_TURN_BUDGET` event | DONE | V21-11,V20-05 |
| V21-14 | V2.1 集成测试与负向测试 | 覆盖 slash skill、skill path escape、skill budget、SubAgent 权限继承、history 隔离、reserve race、parentLinkStatus、timeout partial | DONE | V21-05,V21-13 |
| V21-15 | V2.1 文档与演示收口 | README/Postman 增加 `/purchase-guide` 示例、SubAgent 同步等待限制、child trajectory 查询示例 | DONE | V21-14 |
| V21-GATE | V2.1 hardening review gate | `mvn test` 通过；V20 集成测试全部通过；V2.1 review agent 无 P0/P1/P2；`progress.md` 记录 V2.1 完成摘要；允许开始 V2.2 | DONE | V21-15 |

## V2.2 任务：Multi-instance + Pub/Sub + ToDo + Interrupt

目标：完成多实例部署运行态、tool result Pub/Sub、ToDo 短期计划、用户 interrupt 与级联取消。验收信号：双实例并发 schedule 不重复执行；interrupt 写入 `PAUSED`；ToDo reminder 每 3 turn 注入但不污染对外 conversation。

注：`V22-09` 原为 `PAUSED` state machine 任务，已提前合并到 `V20-04a`，这里保留序号空位，避免重排既有 review 记录。

| ID | 任务 | 验收标准 | 状态 | blockedBy |
|---|---|---|---|---|
| V22-01 | 多实例配置与本地验证拓扑 | 本地可启动两个 Spring Boot 实例连接同一 MySQL/Redis；请求不依赖 sticky session；README 说明多实例验证方式 | DONE | V21-GATE |
| V22-02 | 实现 `ActiveRunSweeper` 职责边界 | Sweeper 只依赖 `RedisToolStore.schedule(runId)`；不依赖 AgentLoop/provider；编译期依赖边界防误调用 | DONE | V22-01 |
| V22-03 | 实现 active runs stale cleanup | sweeper 每轮清理 MySQL 已 terminal 超过 60000ms 的 run，从 `agent:active-runs` 主动 `SREM` | DONE | V22-02 |
| V22-04 | 实现 `ToolResultPubSub` | complete CAS 成功后 publish result notification；ToolResultWaiter 优先 Pub/Sub；消息 payload 不作为一致性真相源 | DONE | V22-01 |
| V22-05 | 实现 Pub/Sub polling fallback | Pub/Sub 丢消息时以 500ms polling Redis terminal state 兜底；测试证明丢通知仍可拿到结果 | DONE | V22-04 |
| V22-06 | 实现 `TodoStore` Redis 模型 | `agent:{run:<runId>}:todos`、`todo-reminder` 可保存 step；step 状态支持 `PENDING/IN_PROGRESS/DONE/BLOCKED/CANCELLED` | DONE | V22-01 |
| V22-07 | 实现 `ToDoCreateTool` 与 `ToDoWriteTool` | 模型可创建计划并更新 step；ToDo 不作为业务事实源；关键变更写 `agent_event` | DONE | V22-06 |
| V22-08 | 实现 `TodoReminderInjector` | 每 3 个 turn 检查未完成 ToDo；注入 transient reminder message；不持久化为对外 user message | DONE | V22-07 |
| V22-10 | 实现 interrupt HTTP endpoint | `POST /api/agent/runs/{runId}/interrupt` 校验 run 归属；写独立 `interrupt_requested` control field，与 `abort_requested` 共存；active 当前 turn 主动结束并返回 `nextActionRequired=user_input`，terminal run 不返回 continuation action | DONE | V22-01,V20-04a |
| V22-11 | 实现 `RunInterruptService` 与工具取消 | interrupt 后不再发起新的 LLM 请求；WAITING tool synthetic cancel；RUNNING tool 收 cancellation token；写操作副作用前检查 token；scheduler、runtime、cancellation token 三处同时检查 `interrupt_requested` 与 `abort_requested` | DONE | V22-10 |
| V22-12 | 实现 SubAgent interrupt 级联 | MainAgent interrupt 找到 active child runs 并写 interrupt control；child 返回 `INTERRUPTED_PARTIAL` 或 late result 留在 child trajectory | DONE | V22-11,V21-12 |
| V22-13 | 多实例 schedule 并发测试 | 两实例同时 schedule 同一 run 不重复执行工具；safe/exclusive 调度语义保持正确；late complete 不污染 transcript | DONE | V22-02,V22-05 |
| V22-14 | V2.2 集成测试与负向测试 | 覆盖 Pub/Sub fallback、active cleanup、ToDo reminder、interrupt PAUSED、SubAgent cascade、multi-instance schedule race | DONE | V22-08,V22-12,V22-13 |
| V22-15 | V2.2 文档与演示收口 | README/Postman 增加 interrupt、多实例、ToDo、Pub/Sub fallback 演示；公开说明 SubAgent 同步等待限制仍存在 | DONE | V22-14 |
| V22-GATE | V2.2 hardening review gate | `mvn test` 通过；V20、V21 集成测试全部通过；V2.2 review agent 无 P0/P1/P2；多实例 smoke 通过；`progress.md` 记录 V2 完成摘要 | DONE | V22-15 |

## V2 真实 E2E 追加验证任务

| id | task | 验收标准 | status | blockedBy |
|---|---|---|---|---|
| V2-E2E-01 | 重置真实 LLM E2E 全量套件 | 独立脚本覆盖订单取消、ToDo、SubAgent、interrupt、skill slash / skill_list / skill_view、三类 context compact、DeepSeek -> Qwen fallback；运行产物写入 `/tmp/agent-buyer-real-llm-e2e/<timestamp>`；`progress.md` 记录踩坑与验证结果 | DONE | V22-GATE |
| V2-ARCH-01 | 重排 package 架构 | `api/tool/llm/trajectory/subagent/skill/todo/business` 按职责拆分 package；不改变业务行为；新增 package architecture 文档；`mvn test` 与真实 LLM E2E 通过 | DONE | V2-E2E-01 |
| V2-HITL-01 | 增强 human-in-the-loop 判断与缺槽追问 | 确认阶段先规则判断，规则不命中时调用 LLM 结构化分类；低置信/失败 fail closed 为追问；`cancel_order` 缺 `orderId` 进入 `PAUSED + user_input` 而不是普通工具失败；补单测与文档 | DONE | V2-ARCH-01 |

## V3 任务：Agent Buyer Console

目标：实现 Agent Buyer Console，让 reviewer 可以在浏览器中观察 run list、trajectory timeline、chat/SSE、HITL、interrupt、abort、runtime state。V3 不是通用 MySQL/Redis 管理后台，只提供 agent lifecycle 所需的安全投影。

### V3-M1 Backend Console API

目标：新增最小后端 Admin Console API：分页 run list 与当前 run runtime-state。验收信号：`GET /api/admin/console/runs` 与 `GET /api/admin/console/runs/{runId}/runtime-state` 可用，后端测试通过，Java 变更通过 `java-alibaba-review`。

| ID | 任务 | 验收标准 | 状态 | blockedBy |
|---|---|---|---|---|
| V3M1-01 | 梳理 V3 实施基线与契约 | `progress.md` 新增 V3 启动记录，至少列出：现有 `/api/agent/*` endpoint、trajectory DTO 名称、`RedisKeys` 中 V3 会读取的 key、当前 `AgentProperties` admin 配置缺口；不修改功能代码 | DONE | V2-HITL-01 |
| V3M1-02 | 增加 Admin 配置与 `AdminAccessGuard` | 新增 `agent.admin.enabled/token` 配置；`AdminAccessGuardTest` 至少覆盖 5 个断言：local/demo + blank token allowed、non-local + blank token denied、enabled=false denied、token match allowed、token mismatch denied；运行 `mvn -q -Dtest=com.ai.agent.web.admin.service.AdminAccessGuardTest test` 通过 | DONE | V3M1-01 |
| V3M1-03 | 实现 Run List DTO 与 `AdminRunListService` | 返回字段包含 runId/userId/status/turnNo/agentType/parentRunId/parentLinkStatus/primaryProvider/fallbackProvider/model/maxTurns/startedAt/updatedAt/completedAt/lastError；SQL 固定 join `agent_run_context` 且固定 `ORDER BY r.updated_at DESC`；测试断言 page/pageSize 边界、status/userId 过滤、无动态 sort/table 参数；运行 `mvn -q -Dtest=com.ai.agent.web.admin.service.AdminRunListServiceTest test` 通过 | DONE | V3M1-02 |
| V3M1-04 | 实现 Runtime State DTO 与 `AdminRuntimeStateService` | 返回 `AdminRuntimeStateDto(runId, activeRun, entries)`；`entries` 只包含当前 run 固定 key，不包含 `agent:active-runs` 完整 set、不包含 `confirm-tokens`；fixture 中放入其他 runId 和 `confirmToken` 时响应不泄露；测试覆盖 HASH/ZSET/SET/STRING/none 和 TTL；运行 `mvn -q -Dtest=com.ai.agent.web.admin.service.AdminRuntimeStateServiceTest test` 通过 | DONE | V3M1-02 |
| V3M1-05 | 实现 `AdminConsoleController` | `GET /api/admin/console/runs` 支持 page/pageSize/status/userId；`GET /api/admin/console/runs/{runId}/runtime-state` 返回 runtime DTO；所有入口先调用 `AdminAccessGuard`；测试断言 200/400/403/503、header 透传、service 参数透传；运行 `mvn -q -Dtest=com.ai.agent.web.admin.controller.AdminConsoleControllerTest test` 通过 | DONE | V3M1-03,V3M1-04 |
| V3M1-06 | V3-M1 后端 review gate | 运行 `MYSQL_PASSWORD='Qaz1234!' mvn test` 通过；review agent 使用 `java-alibaba-review` 审核 V3-M1 Java/YAML/SQL 变更且无 P0/P1/P2；`progress.md` 记录测试命令、结果、review 结论和遗留 P3 | PENDING | V3M1-05 |
| V3M1-GATE | V3-M1 gate | 手工或自动 smoke 验证两个 endpoint：run list 有分页响应，runtime-state 对一个真实 run 返回 `activeRun` 布尔值且不含 `confirmToken` / 完整 `agent:active-runs`；满足后才能开始 V3-M2 | PENDING | V3M1-06 |

### V3-M2 Frontend Shell

目标：建立 `admin-web` React 工程和 console shell，先用 mock/fixture 数据完成布局、基础组件、API type 和 SSE parser。验收信号：三栏 console 可渲染，前端代码通过 `front-alibaba-review`，`npm test && npm run build` 通过。

| ID | 任务 | 验收标准 | 状态 | blockedBy |
|---|---|---|---|---|
| V3M2-01 | 初始化 `admin-web` Vite React 工程 | `admin-web/package.json` 含 `dev/build/test/test:watch` 脚本；Vite proxy `/api -> http://127.0.0.1:8080`；`App.test.tsx` 断言页面标题 `Agent Buyer Console`；运行 `cd admin-web && npm test && npm run build` 通过 | PENDING | V3M1-GATE |
| V3M2-02 | 实现共享类型与 API client | `agentApi.ts` 暴露 createRun/continueRun/getTrajectory/abortRun/interruptRun；`adminApi.ts` 暴露 listRuns/getRuntimeState；测试或 mock 断言 `/api/agent/*` 必带 `X-User-Id`，`/api/admin/*` 仅 token 非空时带 `X-Admin-Token`，任何 error/debug output 不包含 admin token | PENDING | V3M2-01 |
| V3M2-03 | 实现 POST SSE parser | `sseParser.test.ts` 至少 5 个用例：单 chunk 单 event、split chunk、单 chunk 多 event、ping 保留到 debug event、非法 JSON 生成 parser error；`tool_use` 以 `toolName` 为规范字段并兼容历史 `name`；代码中不使用 `EventSource` | PENDING | V3M2-02 |
| V3M2-04 | 构建 UI primitives 与 Console Shell | 新增 UI primitives 与 `ConsoleShell/Toolbar`；测试断言桌面显示 Runs/Timeline/Chat，移动端显示 tabs；CSS 不使用 hero、gradient orb、卡片套卡片；按钮使用 lucide icon；`npm test && npm run build` 通过 | PENDING | V3M2-01 |
| V3M2-05 | Toolbar 状态与本地存储 | Toolbar 可编辑 userId/adminToken，刷新后从 localStorage 恢复；测试断言页面文本、SSE log、debug state 不渲染 adminToken 原值；Refresh 和 Debug toggle 有明确 disabled/loading 状态 | PENDING | V3M2-04 |
| V3M2-GATE | V3-M2 gate | `cd admin-web && npm test && npm run build` 通过；前端代码经 `front-alibaba-review` 无 P0/P1/P2；主 agent 用浏览器或截图检查 desktop 1440x900、mobile 390x844 无明显重叠；`progress.md` 记录截图/检查结论；满足后才能开始 V3-M3 | PENDING | V3M2-03,V3M2-05 |

### V3-M3 Real Data Integration

目标：把 run list、timeline、runtime-state 接入真实后端数据，但暂不启用完整 chat 创建流程。验收信号：选择一个真实 run 后能展示 trajectory 与 runtime debug。

| ID | 任务 | 验收标准 | 状态 | blockedBy |
|---|---|---|---|---|
| V3M3-01 | 实现 `useRunList` 与 Run List Panel | Mock `listRuns` 返回两条不同 status/provider 的 run；测试断言 loading/empty/error/data、status/userId filter、pageSize clamp、选中 run 高亮；Run item 展示 status、短 runId、userId、provider/model、turnNo、updatedAt、parentRunId、parentLinkStatus | PENDING | V3M2-GATE |
| V3M3-02 | 实现 `useRunDetail` 与 Timeline Panel | Fixture 包含 MESSAGE/LLM_ATTEMPT/TOOL_CALL/TOOL_PROGRESS/TOOL_RESULT/EVENT/COMPACTION；测试断言全部节点渲染、timestamp 缺失时稳定排序、tool result synthetic/cancelReason 可见 | PENDING | V3M3-01 |
| V3M3-03 | Timeline / Debug 的敏感字段 redaction | 提供包含 `confirmToken`、adminToken、`sk-...` provider key 的 fixture；测试断言 timeline、runtime state、SSE log、error banner 均不显示原值；redaction 函数有独立单测 | PENDING | V3M3-02 |
| V3M3-04 | 实现 `useRuntimeState` 与 Debug Drawer | Mock runtime DTO 包含 meta/queue/tools/leases/control/children/todos/todo-reminder 和 `activeRun`；测试断言分组正确、缺失 key 不报错、不显示完整 `agent:active-runs`、没有任意 Redis key 输入框 | PENDING | V3M3-01,V3M3-03 |
| V3M3-05 | 串联 App 选中 run 状态 | 测试流程：选择 run -> 调用 getTrajectory/getRuntimeState -> timeline/runtime 更新；点击 debug toggle -> drawer 展示当前 run；刷新 run list 不丢失仍存在的 selectedRunId | PENDING | V3M3-02,V3M3-04 |
| V3M3-GATE | V3-M3 gate | `npm test && npm run build` 通过；前端代码经 `front-alibaba-review` 无 P0/P1/P2；连接本地后端时可看到至少一个真实 run 的 run list、timeline、runtime state；`progress.md` 记录使用的 runId 和观察结果；满足后才能开始 V3-M4 | PENDING | V3M3-05 |

### V3-M4 Chat + SSE

目标：Console 内复用现有 `/api/agent/*` 实现真实 chat、continuation、HITL、interrupt、abort。验收信号：用户可以在页面内创建 run 并实时看到 SSE text/tool events。

| ID | 任务 | 验收标准 | 状态 | blockedBy |
|---|---|---|---|---|
| V3M4-01 | 实现 `useChatStream` | 用 mock SSE 事件流测试：text_delta 合并 assistantDraft、tool_use 生成 tool card、tool_progress 更新 percent、tool_result 展示 preview、final 设置 runStatus/nextActionRequired、error 停止 streaming、ping 只进 debug log；state 不保存原始 `confirmToken` | PENDING | V3M3-GATE |
| V3M4-02 | 实现 Chat Panel 创建 run | 测试断言点击 Send 调用 `POST /api/agent/runs`，header 含 `X-User-Id`，body 含默认 allowedToolNames 与 `deepseek-reasoner` 参数；收到首个含 runId 的 SSE event 后设置 selectedRunId 并刷新 run list | PENDING | V3M4-01 |
| V3M4-03 | 实现 continuation / HITL / PAUSED 输入 | 测试断言 `WAITING_USER_CONFIRMATION` 显示 Confirm/Reject；Confirm 发送“确认继续执行”，Reject 发送“放弃本次操作”；`PAUSED + user_input` 时 composer placeholder 为“补充订单号、说明或下一步指令...”；请求走 `/api/agent/runs/{runId}/messages` | PENDING | V3M4-02 |
| V3M4-04 | 实现 Run Controls | 测试断言 New Chat 清空当前 draft 但不清空 run list；Refresh Run 刷新 trajectory/runtime；Interrupt/Abort 对 terminal run disabled，对 active run 调用对应 endpoint；isStreaming 时 Send/Interrupt/Abort disabled 状态符合 UI 约定 | PENDING | V3M4-03 |
| V3M4-05 | Chat 与 Timeline / Runtime 联动 | 测试断言 chat 收到 `tool_use/tool_result/final` 后调用 runtime refresh；`final` 后调用 run list 与 trajectory refresh；SSE `ping` 不进入 chat transcript；error event 显示 ErrorBanner 并停止 loading | PENDING | V3M4-04,V3M3-05 |
| V3M4-GATE | V3-M4 gate | `npm test && npm run build` 通过；前端代码经 `front-alibaba-review` 无 P0/P1/P2；本地后端 smoke 至少覆盖 create run、WAITING_USER_CONFIRMATION continuation、PAUSED user input 或 interrupt 三类中的两类；`progress.md` 记录请求、runId、结果；满足后才能开始 V3-M5 | PENDING | V3M4-05 |

### V3-M5 Hardening / Demo

目标：完善演示脚本、README、集成测试和浏览器 smoke，保证 V3 可以作为作品集 demo 稳定展示。

| ID | 任务 | 验收标准 | 状态 | blockedBy |
|---|---|---|---|---|
| V3M5-01 | 增加本地 Console 启动脚本 | `scripts/start-console-dev.sh` 通过 `bash -n`；脚本检查 MySQL/Redis 连通性，发现 8080 已监听时不重复启动后端，启动 `admin-web` Vite；脚本不包含真实 API key，不打印 provider/admin secret | PENDING | V3M4-GATE |
| V3M5-02 | README 中文补充 Agent Buyer Console | README 包含启动后端、启动前端、访问 URL、示例用户、推荐 prompt、admin token/local profile 说明、V3 不做通用 DB/Redis browser 的边界；README 中不出现真实 provider key | PENDING | V3M5-01 |
| V3M5-03 | 增加前端集成测试 fixture | Fixture 至少包含：run summary、USER/ASSISTANT message、LLM attempt、tool call、tool progress、tool result、confirmation final SSE、compaction、runtime state、`activeRun`、含 `confirmToken` 的恶意字段；fixture 可被 `App.integration.test.tsx` 直接复用 | PENDING | V3M4-05 |
| V3M5-04 | 增加 `App.integration.test.tsx` | mocked fetch 覆盖完整 UI 流：加载 run list -> 选择 run -> 展示 timeline/runtime -> 处理 text_delta -> 展示 WAITING_USER_CONFIRMATION 控件 -> 验证 confirmToken redaction 与 activeRun bool；`cd admin-web && npm test` 通过 | PENDING | V3M5-03 |
| V3M5-05 | 浏览器 smoke 与视觉检查 | 使用 Playwright 或 in-app browser 检查 desktop 1440x900、mobile 390x844；记录截图路径或检查日志；断言无面板重叠、按钮文字不溢出、debug drawer 可打开、chat 可发送 prompt 并收到 SSE final | PENDING | V3M5-04 |
| V3M5-06 | V3 最终验证与 review gate | 顺序运行 `MYSQL_PASSWORD='Qaz1234!' mvn test`、`cd admin-web && npm test && npm run build`；Java 变更经 `java-alibaba-review` 无 P0/P1/P2；前端变更经 `front-alibaba-review` 无 P0/P1/P2；真实 LLM E2E 作为独立 smoke 记录 pass/fail 与原因；`progress.md` 写 V3 完成摘要 | PENDING | V3M5-02,V3M5-05 |
| V3-GATE | V3 hardening review gate | `V3M1-GATE/V3M2-GATE/V3M3-GATE/V3M4-GATE` 和 `V3M5-06` 全部 DONE；Console 演示清单逐项通过：run list、timeline、runtime state、chat/SSE、HITL、PAUSED、interrupt、abort；spec/task/progress/README 同步完成；不得有未解决 P0/P1/P2 | PENDING | V3M5-06 |

## 任务边界

V2 明确不做：

- 不接入 GLM
- 不做 RAG / embedding / vector store
- 不使用 LangChain4j AI Services 接管 AgentLoop
- 不引入 Kafka、RabbitMQ、workflow engine
- 不实现完整 outbox 幂等恢复
- 不引入大结果对象存储，V2 只使用 trajectory logical path 指向 MySQL 原始结果
- 不做 Redis key 级 `generation` 隔离，除非 provider fallback 实现中发现 replay 污染风险必须处理

V3 明确不做：

- 不做通用 MySQL table browser
- 不做任意 Redis key browser
- 不新增 `/api/admin/chat`
- 不实现生产级多租户 admin RBAC
- 不暴露原始 `confirmToken`、provider raw payload、secret 或完整未脱敏大 tool result
- 不返回完整 `agent:active-runs` set，只返回当前 run 的 `activeRun` 布尔值
- 不把 Console 做成生产运维后台；后续 admin-inspector 必须独立规划

## 总体验收标准

- V2.0 / V2.1 / V2.2 三个 gate 全部 `DONE`。
- 全量 `mvn test` 通过。
- DeepSeek 故障可 fallback Qwen，且 fallback 不污染 transcript 或 Redis tool queue。
- 50K token 上下文能通过 spill / micro / summary compact 继续运行，summary 保留 `businessFacts/toolFacts/openQuestions`。
- `/skillName` slash 注入工作；skill budget 超限 fail closed，不静默截断。
- SubAgent 继承 MainAgent tool/skill 能力集合，但不继承 history、ToDo 或 compact view。
- 单 run 第 3 个 SubAgent、同一时刻第 2 个 in-flight SubAgent 都返回 `SUBAGENT_BUDGET_EXCEEDED`。
- child run trajectory 可独立查询，并暴露 `parentLinkStatus`。
- 双实例 schedule 不重复执行同一 tool。
- Pub/Sub 丢通知时 polling fallback 能拿到 terminal result。
- ToDo reminder 每 3 turn 注入 provider view，但不污染对外 conversation。
- 用户 interrupt 后当前 turn 进入 `PAUSED`，WAITING tool 不再执行，RUNNING tool 收到 cancellation token，active child run 被级联 interrupt。
- V3-M1 / V3-M2 / V3-M3 / V3-M4 / V3-M5 五个 gate 全部 `DONE`。
- Console 复用真实 `/api/agent/*`，不新增 admin chat proxy。
- Console 可展示 run list、trajectory timeline、chat/SSE、runtime state debug。
- Runtime state 不支持任意 Redis key，不返回完整 `agent:active-runs` set。
- 页面、日志、debug panel、SSE log 不展示原始 `confirmToken`、admin token 或 provider key。
- 前端 `npm test`、`npm run build` 通过；后端 `mvn test` 通过。
