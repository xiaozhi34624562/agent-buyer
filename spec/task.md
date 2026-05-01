# Agent Buyer V2 Tasks

## 目录

- 多 Agent 开发流程
  - 角色职责
  - 里程碑顺序
  - 并行规则
  - Review Gate
- V2.0 任务：Multi-provider + Context Compact
- V2.1 任务：Skill + SubAgent
- V2.2 任务：Multi-instance + Pub/Sub + ToDo + Interrupt
- 任务边界
- 总体验收标准

本文档记录 V2 全量任务拆分。V2 不一次性并行交付，必须按 `V2.0 -> V2.1 -> V2.2` 顺序推进；每个里程碑内部可以按依赖并行开发，但必须完成该里程碑的 hardening review gate 后，才能进入下一个里程碑。

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
| review agent | 使用 `java-alibaba-review` skill 对 Java/Spring/MyBatis/Redis/Lua/SQL/YAML 变更做独立审查，输出 P0-P3 findings 和测试缺口 | 不直接改代码；不把风格问题伪装成阻塞项 |

### 里程碑顺序

| 里程碑 | 内容 | 开始条件 | 完成条件 |
|---|---|---|---|
| V2.0 | Multi-provider（DeepSeek + Qwen）+ Context compact | V1a 全部完成 | `V20-GATE` 为 `DONE` |
| V2.1 | Skill 渐进式加载 + SubAgent / AgentTool | `V20-GATE` 为 `DONE` | `V21-GATE` 为 `DONE` |
| V2.2 | 多实例部署 + Pub/Sub + ToDo + Interrupt | `V21-GATE` 为 `DONE` | `V22-GATE` 为 `DONE` |

强约束：

- `V20-GATE` 完成前，不允许开始任何 `V21-*` 任务。
- `V21-GATE` 完成前，不允许开始任何 `V22-*` 任务。
- 每个 gate 必须完成相关测试、README / Postman / spec 同步，以及 `java-alibaba-review` 独立审核。

### 并行规则

- 只有 `blockedBy` 全部为 `DONE` 的任务才能进入开发。
- 并行任务必须有明确、尽量不重叠的写入范围。
- 如果两个 sub agent 都需要修改同一个核心文件，例如 `AgentTurnOrchestrator`、`LlmAttemptService`、`PromptAssembler`、`LuaRedisToolStore`，主 agent 必须拆分顺序或指定唯一 owner。
- sub agent 完成后先由主 agent 做集成检查，再交给 review agent 审核。
- review agent 发现 P0/P1/P2 时，该任务不能标记为 `DONE`；必须修复后重新 review。
- P3 可以由主 agent 判断是否本轮修复，但需要记录在 `progress.md`。
- 每个任务完成时必须更新 `task.md` 状态和 `progress.md` 过程记录。

### Review Gate

每个涉及 Java、SQL、MyBatis XML、Redis Lua、YAML、测试或接口契约的任务，都必须经过 review agent 审核。review agent 固定使用：

```text
Skill: java-alibaba-review
Path: /Users/xiaozhi/.codex/skills/java-alibaba-review/SKILL.md
```

审核重点：

- 安全和权限：run 归属、水平越权、工具权限放大、skill 路径逃逸、PII 泄漏。
- 状态一致性：MySQL trajectory、Redis active state、provider replay、tool result pairing、SubAgent parent link。
- 并发和资源：executor、lease、Pub/Sub fallback、SubAgent slot reserve、interrupt、timeout、late result。
- 异常和日志：fail closed、错误码、budget exceeded、敏感信息、可排障性。
- MyBatis/MySQL：索引、参数绑定、JSON 字段、migration 兼容性。
- 测试：核心行为必须有正向、边界和负向测试。

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
| V20-06 | 实现 `ContextViewBuilder`，分离原始 trajectory 与 provider view | provider 请求前从 MySQL 原始 messages 构建 view；不修改原始 trajectory；compact 前后均调用 `TranscriptPairValidator` | PENDING | V20-02 |
| V20-07 | 实现 `LargeResultSpiller` | 单个 tool result > 2000 token 时，provider view 保留头 200 token、尾 200 token，中间插入 `<resultPath>`；MySQL 原始结果不删除 | PENDING | V20-06 |
| V20-08 | 实现 `MicroCompactor` | provider view 达到 50000 token 时，旧 tool result content 替换为 `<oldToolResult>`；不删除 tool result message；pair validator 通过 | PENDING | V20-07 |
| V20-09 | 实现 `SummaryCompactor` 与 summary JSON schema | summary 包含 `summaryText`、`businessFacts`、`toolFacts`、`openQuestions`、`compactedMessageIds`；保留 system、前三条、最近消息预算规则 | PENDING | V20-08 |
| V20-10 | 增加 `agent_context_compaction` 存储与查询 | 新增 V8 Flyway migration：创建 `agent_context_compaction` 表；记录 compaction strategy、before/after tokens、compactedMessageIds；trajectory query 可展示 compaction 记录 | PENDING | V20-09 |
| V20-11 | V2.0 集成测试与负向测试 | 覆盖 Qwen profile、RunContext provider 复用、fallback 安全边界、50K context compact、summary fields、pair validation、PAUSED 状态迁移、budget exceeded | PENDING | V20-05,V20-10 |
| V20-12 | V2.0 文档与配置收口 | README 标注 V2.0 状态；配置包含 provider、context、budget；Postman 或脚本补 provider/context smoke | PENDING | V20-11 |
| V20-GATE | V2.0 hardening review gate | `mvn test` 通过；V2.0 相关 review agent 无 P0/P1/P2；`progress.md` 记录 V2.0 完成摘要；允许开始 V2.1 | PENDING | V20-12 |

## V2.1 任务：Skill + SubAgent

目标：完成 Anthropic-style skill 渐进式加载、slash skill 注入、AgentTool / SubAgent 同步委派、child trajectory 与 SubAgent 预算。验收信号：`/skillName` 注入工作；SubAgent 继承 tool/skill 但不继承 history；child run trajectory 可独立查询。

| ID | 任务 | 验收标准 | 状态 | blockedBy |
|---|---|---|---|---|
| V21-01 | 实现 `SkillRegistry` 与 Anthropic skill 扫描 | 可扫描 `skills/*/SKILL.md` frontmatter；preview 只包含 `name + description`; 3 个业务 skill 可被索引 | PENDING | V20-GATE |
| V21-02 | 实现 `SkillPathResolver` 路径安全 | `skill_view(skillName, skillPath)` 禁止 `..`、绝对路径、符号链接逃逸；路径逃逸返回稳定错误 | PENDING | V21-01 |
| V21-03 | 实现 `SkillListTool` 与 `SkillViewTool` | `skill_list()` 无 userId 入参，userId 从 `ToolUseContext` 注入；`skill_view` 可返回完整 `SKILL.md` 或 skill 内文件 | PENDING | V21-02 |
| V21-03a | PromptAssembler 注入 skill preview 列表 | system prompt 在 user info 与 tool-use guidance 之间加入用户可访问 skill preview（`name + description`）；preview 不包含 `SKILL.md` 内容；`systemPromptHash` 重新计算并有测试覆盖 | PENDING | V21-03 |
| V21-04 | 实现 `SkillCommandResolver` slash skill 注入 | 用户消息含 `/skillName` 时，本轮 provider view 注入完整 `SKILL.md`；不改写 materialized system prompt；访问写 `agent_event` | PENDING | V21-03a |
| V21-05 | 实现 slash skill budget fail closed | 超过 `agent.skills.max-per-message=3` 或 `max-token-per-message=8000` 时返回 `SKILL_BUDGET_EXCEEDED`；错误体包含命中 skill、预算、实际、超出量 | PENDING | V21-04 |
| V21-06 | 设计并实现 `AgentTool` schema 与反滥用提示 | `AgentTool` 作为普通 Tool 注册；schema description 明确“高成本工具，请谨慎使用”；MainAgent system prompt 加 spawn hint | PENDING | V21-03 |
| V21-07 | 增加 child run MySQL 字段与 DTO | 新增 V9 Flyway migration：`agent_run` 增加 `parent_run_id`、`parent_tool_call_id`、`agent_type`、`parent_link_status`; trajectory query DTO 暴露 `parentLinkStatus` | PENDING | V21-06 |
| V21-08 | 实现 `SubAgentRegistry` 与 `SubAgentProfile` | 至少提供 `ExploreAgent` profile；子 Agent 默认继承 parent tool/skill 能力集合，但不继承 message history、ToDo、compact view | PENDING | V21-06 |
| V21-09 | 实现 `SubAgentBudgetPolicy`、`ChildRunRegistry` 与 Redis Lua slot reserve | `reserve_child.lua` 原子检查 `max-spawn-per-run=2`、`max-concurrent-per-run=1`、`spawn-budget-per-user-turn=2`; 失败返回 `SUBAGENT_BUDGET_EXCEEDED`；同时实现 `ChildRunRegistry`，维护 parent ↔ child 运行期映射，作为 V22-12 cascade interrupt 的查询入口 | PENDING | V21-07 |
| V21-10 | 实现 `release_child.lua` 与 child lifecycle | child terminal / timeout / interrupt / detach 释放 in-flight 计数；lifetime spawn 计数不释放；并发 reserve 只有一个成功 | PENDING | V21-09 |
| V21-11 | 实现 `SubAgentRunner` 同步等待 | `AgentTool` 同步等待 child result summary，默认 3 分钟；成功只把 summary/状态/关键引用/childRunId 返回 parent | PENDING | V21-08,V21-10 |
| V21-12 | 实现 child timeout 与 parent link status | 等待超过 3 分钟写 `DETACHED_BY_TIMEOUT` 并 interrupt child；parent interrupt / failed 分别写 `DETACHED_BY_INTERRUPT` / `DETACHED_BY_PARENT_FAILED` | PENDING | V21-11 |
| V21-13 | 扩展 SubAgent LLM call budget | 每个 SubAgent user turn 最多 30 次 LLM call；超限返回 partial summary 并进入 `PAUSED`；写 `SUB_TURN_BUDGET` event | PENDING | V21-11,V20-05 |
| V21-14 | V2.1 集成测试与负向测试 | 覆盖 slash skill、skill path escape、skill budget、SubAgent 权限继承、history 隔离、reserve race、parentLinkStatus、timeout partial | PENDING | V21-05,V21-13 |
| V21-15 | V2.1 文档与演示收口 | README/Postman 增加 `/purchase-guide` 示例、SubAgent 同步等待限制、child trajectory 查询示例 | PENDING | V21-14 |
| V21-GATE | V2.1 hardening review gate | `mvn test` 通过；V20 集成测试全部通过；V2.1 review agent 无 P0/P1/P2；`progress.md` 记录 V2.1 完成摘要；允许开始 V2.2 | PENDING | V21-15 |

## V2.2 任务：Multi-instance + Pub/Sub + ToDo + Interrupt

目标：完成多实例部署运行态、tool result Pub/Sub、ToDo 短期计划、用户 interrupt 与级联取消。验收信号：双实例并发 schedule 不重复执行；interrupt 写入 `PAUSED`；ToDo reminder 每 3 turn 注入但不污染对外 conversation。

注：`V22-09` 原为 `PAUSED` state machine 任务，已提前合并到 `V20-04a`，这里保留序号空位，避免重排既有 review 记录。

| ID | 任务 | 验收标准 | 状态 | blockedBy |
|---|---|---|---|---|
| V22-01 | 多实例配置与本地验证拓扑 | 本地可启动两个 Spring Boot 实例连接同一 MySQL/Redis；请求不依赖 sticky session；README 说明多实例验证方式 | PENDING | V21-GATE |
| V22-02 | 实现 `ActiveRunSweeper` 职责边界 | Sweeper 只依赖 `RedisToolStore.schedule(runId)`；不依赖 AgentLoop/provider；编译期依赖边界防误调用 | PENDING | V22-01 |
| V22-03 | 实现 active runs stale cleanup | sweeper 每轮清理 MySQL 已 terminal 超过 60000ms 的 run，从 `agent:active-runs` 主动 `SREM` | PENDING | V22-02 |
| V22-04 | 实现 `ToolResultPubSub` | complete CAS 成功后 publish result notification；ToolResultWaiter 优先 Pub/Sub；消息 payload 不作为一致性真相源 | PENDING | V22-01 |
| V22-05 | 实现 Pub/Sub polling fallback | Pub/Sub 丢消息时以 500ms polling Redis terminal state 兜底；测试证明丢通知仍可拿到结果 | PENDING | V22-04 |
| V22-06 | 实现 `TodoStore` Redis 模型 | `agent:{run:<runId>}:todos`、`todo-reminder` 可保存 step；step 状态支持 `PENDING/IN_PROGRESS/DONE/BLOCKED/CANCELLED` | PENDING | V22-01 |
| V22-07 | 实现 `ToDoCreateTool` 与 `ToDoWriteTool` | 模型可创建计划并更新 step；ToDo 不作为业务事实源；关键变更写 `agent_event` | PENDING | V22-06 |
| V22-08 | 实现 `TodoReminderInjector` | 每 3 个 turn 检查未完成 ToDo；注入 transient reminder message；不持久化为对外 user message | PENDING | V22-07 |
| V22-10 | 实现 interrupt HTTP endpoint | `POST /api/agent/runs/{runId}/interrupt` 校验 run 归属；写独立 `interrupt_requested` control field，与 `abort_requested` 共存；当前 turn 主动结束并返回 `nextActionRequired=user_input` | PENDING | V22-01,V20-04a |
| V22-11 | 实现 `RunInterruptService` 与工具取消 | interrupt 后不再发起新的 LLM 请求；WAITING tool synthetic cancel；RUNNING tool 收 cancellation token；写操作副作用前检查 token；scheduler、runtime、cancellation token 三处同时检查 `interrupt_requested` 与 `abort_requested` | PENDING | V22-10 |
| V22-12 | 实现 SubAgent interrupt 级联 | MainAgent interrupt 找到 active child runs 并写 interrupt control；child 返回 `INTERRUPTED_PARTIAL` 或 late result 留在 child trajectory | PENDING | V22-11,V21-12 |
| V22-13 | 多实例 schedule 并发测试 | 两实例同时 schedule 同一 run 不重复执行工具；safe/exclusive 调度语义保持正确；late complete 不污染 transcript | PENDING | V22-02,V22-05 |
| V22-14 | V2.2 集成测试与负向测试 | 覆盖 Pub/Sub fallback、active cleanup、ToDo reminder、interrupt PAUSED、SubAgent cascade、multi-instance schedule race | PENDING | V22-08,V22-12,V22-13 |
| V22-15 | V2.2 文档与演示收口 | README/Postman 增加 interrupt、多实例、ToDo、Pub/Sub fallback 演示；公开说明 SubAgent 同步等待限制仍存在 | PENDING | V22-14 |
| V22-GATE | V2.2 hardening review gate | `mvn test` 通过；V20、V21 集成测试全部通过；V2.2 review agent 无 P0/P1/P2；多实例 smoke 通过；`progress.md` 记录 V2 完成摘要 | PENDING | V22-15 |

## 任务边界

V2 明确不做：

- 不接入 GLM
- 不做 RAG / embedding / vector store
- 不使用 LangChain4j AI Services 接管 AgentLoop
- 不引入 Kafka、RabbitMQ、workflow engine
- 不实现完整 outbox 幂等恢复
- 不引入大结果对象存储，V2 只使用 trajectory logical path 指向 MySQL 原始结果
- 不做 Redis key 级 `generation` 隔离，除非 provider fallback 实现中发现 replay 污染风险必须处理

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
