# Agent Buyer V1a Hardening Progress

本文档记录重构过程、设计决策和开发中踩过的坑。每次完成一个任务或发现一个影响设计的边界，都在这里追加记录。

## 2026-05-01

### 已完成

- 确认 `spec/requirement.md` 与 `spec/design.md` 已位于 `agent-buyer/spec/`。
- 基于代码 review 结果，整理出 V1a hardening 任务清单。
- 创建 `spec/task.md`，按安全、状态一致性、架构拆分、测试文档的顺序安排任务。
- 创建本进度文档，用于持续记录开发过程。
- 更新 `spec/requirement.md`，补充审查后重构目标、hardening 优先级和新增不变量。
- 更新 `spec/design.md`，补充审查后目标架构、组件分层和核心规则。
- 更新 `spec/task.md`，补充主 agent、sub agent、review agent 的协作职责、并行规则和 review gate。

### 当前结论

- 当前最优先的问题不是功能缺失，而是 agent harness 的边界没有完全固化：
  - run 归属校验没有统一入口
  - continuation 丢失首次请求的 tool allowlist
  - abort / timeout 没有贯穿 scheduler、runtime、tool 和 transcript close
  - `DefaultAgentLoop` 承担过多职责，后续功能会继续压大它

### 已识别的坑

- 不能让 `allowedToolNames == null` 隐式表示“全部工具”。在 create run 时可以使用默认授权集合，但 continuation 必须复用 run context。
- synthetic tool result 不是错误处理装饰品，而是 provider transcript 配对的硬约束。任何 assistant tool call 写入后，都必须最终闭合。
- abort 不是只改 MySQL run 状态。它必须成为 Redis schedule、tool runtime、complete CAS 和业务 tool 副作用前检查都能看到的控制信号。
- 用户确认语义不能靠简单字符串包含判断。`no problem, cancel it` 这类输入会把裸 `contains("no")` 打穿。
- 对外 trajectory 查询不能直接返回 MyBatis entity；entity 字段变化、raw diagnostic、confirmToken、tool args 都可能成为兼容性或安全问题。
- 并行开发必须先控制写入范围。`DefaultAgentLoop`、`AgentController`、`LuaRedisToolStore` 是高冲突文件，不能让多个 sub agent 同时自由修改。
- review agent 必须独立使用 `java-alibaba-review` 审核代码，P0/P1/P2 未清零时主 agent 不能验收。

### 下一步

- 从 T01 开始实现 `RunAccessManager`，先修复 trajectory 查询水平越权。
- T01 完成后再做 T02，避免 continuation 权限修复没有统一访问边界。

### T01 启动记录

- 状态：`IN_PROGRESS`
- 主 agent 职责：分发实现任务、验收 diff、运行测试、触发 review agent。
- sub agent 任务：实现 `RunAccessManager`，统一 `GET trajectory`、continue、abort 的 run 归属和状态校验；按 TDD 补测试。
- review agent 任务：sub agent 完成后，使用 `java-alibaba-review` 独立审查 Java/Spring/MyBatis/测试变更。

### T01 Review 记录

- targeted 测试曾通过：`mvn -Dtest=com.ai.agent.RunAccessManagerTest,com.ai.agent.api.AgentControllerAccessTest test`，13 tests。
- 全量测试曾通过：`MYSQL_PASSWORD=*** mvn test`，30 tests。
- review agent 使用 `java-alibaba-review` 审查后发现 P1/P2：
  - continuation lock 获取发生在 SSE executor 内，锁冲突不能稳定映射为 HTTP 拒绝。
  - continuation lock 冲突使用裸 `IllegalStateException`，异常契约不稳定。
  - `ContinuationLockService.release(lock)` 未校验 lock token，存在误删后继锁风险。
- 已分发 T01 review-fix worker，要求修复上述 finding 并补测试。
- 第一轮 review fix 后，targeted 测试和全量测试通过，但 re-review 继续发现 P1：
  - continuation 预检通过到 executor 实际执行之间存在状态窗口，abort/timeout 后排队任务仍可能复活 run。
  - continuation lock TTL 无法覆盖“提交前拿锁 + executor 排队 + 执行”的完整生命周期。
- 已分发第二轮 review-fix worker，要求通过 MySQL 状态 CAS 缩小窗口：提交 executor 前 `WAITING_USER_CONFIRMATION -> RUNNING`，executor 执行前确认仍是 `RUNNING`。

### T01 完成记录

- 完成时间：2026-05-01 18:28 CST
- 状态：`DONE`
- 主要改动：
  - 新增 `RunAccessManager`，统一 run owner 校验、continuation 准入、abort 状态迁移。
  - `GET /api/agent/runs/{runId}`、continue、abort 均通过统一入口校验 run 归属；不存在返回 404，非 owner 返回 403。
  - continuation 在 HTTP submit 前获取 Redis lock，并 CAS `WAITING_USER_CONFIRMATION -> RUNNING`；executor reject 时 CAS 恢复 `RUNNING -> WAITING_USER_CONFIRMATION` 并释放 lock。
  - Redis continuation lock 释放改为 token compare-and-delete，避免旧 lock 误删新 lock。
  - `DefaultAgentLoop` 在 LLM 返回、tool commit/scheduling 前、tool wait 后、final 状态写入前检查 run 仍是 `RUNNING`；终态写入统一 CAS `RUNNING -> target`。
  - `RunRepairService.expireWaitingConfirmations` 改为先 CAS `WAITING_USER_CONFIRMATION -> TIMEOUT`，成功后才清 confirm token 和追加超时消息。
  - `AgentLoop` 不再暴露 raw `ContinuationLockService.Lock`，改为 `RunAccessManager.ContinuationPermit`。
  - abort 改为 `abortIfActive`，只允许 active run CAS 到 `CANCELLED`；已终态 run 不被覆盖，也不重复写 Redis abort。
- 验证结果：
  - targeted：`MYSQL_PASSWORD=*** mvn -Dtest=com.ai.agent.RunAccessManagerTest,com.ai.agent.api.AgentControllerAccessTest,com.ai.agent.api.ContinuationLockServiceTest,com.ai.agent.RunRepairServiceIntegrationTest test`
  - targeted 结果：34 tests，0 failures，0 errors，BUILD SUCCESS
  - full：`MYSQL_PASSWORD=*** mvn test`
  - full 结果：50 tests，0 failures，0 errors，BUILD SUCCESS
- review gate：
  - 最终 `java-alibaba-review` 复审未发现 P0/P1/P2 阻断问题。
  - 复审确认 abort CAS、continuation owner/status/lock、executor reject rollback、confirmation expiry CAS 均符合 T01 验收目标。
- 残余边界：
  - assistant tool_call 已 commit 后如果 run 被 abort，仍可能短暂出现未闭合 tool result；该问题归入 T05 `ToolResultCloser` / synthetic close 范围，不作为 T01 阻断。
  - Redis `abort_requested` 对 running tool、late complete 和 cancellation token 的完整传播归入 T06。

### Wave 2 启动记录

- 状态：T02 / T03 / T10 `IN_PROGRESS`
- 主 agent 职责：控制写入边界、集成结果、运行测试、触发 review gate。
- T02 sub agent：实现 run-level `RunContext`，固化 effectiveAllowedTools/model/maxTurns，修复 continuation 权限放大。
- T10 sub agent：拆分 trajectory 读写边界，新增对外脱敏 DTO 查询服务，避免暴露 MyBatis entity。
- T03 explorer：只读分析 `AgentRunApplicationService` 最小搬迁方案，等待 T02/T10 结果后由主 agent 接线。

### Wave 2 Review 修复记录

- 第一轮 review agent 使用 `java-alibaba-review` 发现 3 个阻断点：
  - continuation 已 CAS 到 `RUNNING` 后，如果 `RunContext` 中包含已下线工具，会在写入用户消息后异常，导致 run 卡在 `RUNNING`。
  - V5 migration 对历史 run context 回填 `query_order/cancel_order`，会把写工具权限授予无法证明原始授权的历史 run。
  - create run 与 create run context 不是原子操作，context 创建失败会留下裸 `CREATED` run。
- 修复方式：
  - continuation 在 append 用户消息前先 `load + validateRunContext`；未知工具、缺失 model、非法 maxTurns 都会先恢复到 `WAITING_USER_CONFIRMATION`，并释放 continuation lock。
  - 新增 `V6__conservative_legacy_run_context_tools.sql`，把 V5 形态且仍处于 `CREATED/RUNNING/WAITING_USER_CONFIRMATION` 的历史 run context 工具集改为空，升级路径 fail closed。
  - `DefaultAgentLoop.createRunAndContext` 在 context 创建失败时补偿标记 run 为 `FAILED`，避免悬挂 `CREATED` run。
- TDD 红测记录：
  - `mvn -Dtest=com.ai.agent.api.DefaultAgentLoopRunContextTest test` 曾出现 2 个预期失败：
    - 未知工具 continuation 后 status 从期望 `WAITING_USER_CONFIRMATION` 变成 `RUNNING`
    - context 创建失败后 status 从期望 `FAILED` 变成 `CREATED`

### Wave 2 完成记录

- 完成时间：2026-05-01 19:23 CST
- 状态：T02 / T03 / T10 / T11 `DONE`
- T02 主要改动：
  - 新增 run 级 `RunContext`、`RunContextStore`、`MybatisRunContextStore` 和 `agent_run_context` 表。
  - create run 时持久化 `effectiveAllowedTools`、model、maxTurns；continuation 只复用已持久化 context。
  - `null allowedToolNames` 使用配置默认工具集；显式空集合表示请求侧完全收窄为无工具。
  - context 缺失、context 含未知工具、context create 失败都已有 fail-closed 测试。
- T03 主要改动：
  - 新增 `AgentRunApplicationService`，统一 create、continue、abort、query 的应用编排。
  - `AgentController` 只保留 HTTP/SSE 编排，不再直接依赖 `TrajectoryStore` 或 `RedisToolStore`。
  - 缺失或空白 `X-User-Id` 返回 401；query/continue/abort 统一走 `RunAccessManager`。
- T10 主要改动：
  - 拆分 `TrajectoryWriter`、`TrajectoryReader`、`TrajectorySnapshot`。
  - 新增 `TrajectoryQueryService` 和对外 DTO，trajectory 查询返回脱敏裁剪后的结构，不暴露 MyBatis entity。
  - HTTP query 路径只走 DTO 服务；内部 replay/repair 才使用 `TrajectoryReader`。
- T11 主要改动：
  - 新增 V4/V5/V6 Flyway migration：run context 表、历史 context backfill、run status 索引和保守历史权限修正。
  - 新增 `AgentRunContextEntity`、`AgentRunContextMapper`。
- 验证结果：
  - 红测：`mvn -Dtest=com.ai.agent.api.DefaultAgentLoopRunContextTest test`，新增两个回归测试先失败后修复。
  - targeted：`mvn -Dtest=com.ai.agent.api.DefaultAgentLoopRunContextTest test`，6 tests，0 failures，0 errors，BUILD SUCCESS。
  - targeted：`MYSQL_PASSWORD=*** mvn -Dtest=com.ai.agent.TrajectoryStoreIntegrationTest,com.ai.agent.RunRepairServiceIntegrationTest test`，4 tests，0 failures，0 errors，Flyway 从 v5 成功迁移到 v6。
  - targeted：`mvn -Dtest=com.ai.agent.api.DefaultAgentLoopRunContextTest,com.ai.agent.api.AgentControllerAccessTest,com.ai.agent.RunAccessManagerTest,com.ai.agent.trajectory.TrajectoryQueryServiceTest test`，39 tests，0 failures，0 errors。
  - full：`MYSQL_PASSWORD=*** mvn test`，60 tests，0 failures，0 errors，BUILD SUCCESS，Flyway validated 6 migrations。
- review gate：
  - spec review 确认 T02/T03/T10 代码验收通过，要求同步 `task.md` 与 `progress.md`。
  - final `java-alibaba-review` 复审确认 P1/P2 已清零，未发现阻断 Wave 2 的 issue。
- 残余边界：
  - RUNNING tool 的协作取消、late complete 防护和业务副作用前 cancellation check 仍归入 T06。
  - assistant tool call 在所有失败路径上的统一 synthetic close 仍归入 T05。
  - `DefaultAgentLoop` 仍偏重，后续按 T04 拆 turn orchestration / LLM attempt / tool coordination。

### Wave 3-6 完成记录

- 完成时间：2026-05-01 19:49 CST
- 状态：T04 / T05 / T06 / T07 / T08 / T09 / T12 / T13 `DONE`
- T04 主要改动：
  - 新增 `AgentTurnOrchestrator`，集中 turn loop、终止条件和组件协作。
  - 新增 `LlmAttemptService`，封装 DeepSeek provider 调用、text delta、attempt trace 成功/失败写入。
  - 新增 `ToolCallCoordinator`，负责 assistant tool call commit、precheck、Redis ingest、等待 tool terminal result 和 pending confirmation 识别。
  - `DefaultAgentLoop` 保留 run 创建、continuation 和 prompt 初始化，不再直接处理 precheck、tool wait、synthetic close 等细节。
- T05 主要改动：
  - 新增 `ToolResultCloser`，统一写 `agent_tool_result_trace` 与 matching `TOOL` message。
  - precheck failure、tool wait timeout、run timeout、max turns、abort、executor reject、lease reaper 都通过 synthetic 或 terminal close 闭合 transcript。
  - `ToolResultCloser` 读取 snapshot 做幂等保护，避免重复写 tool result 或重复追加 tool message。
- T06 主要改动：
  - `RedisToolStore.abort` 改为返回 synthetic terminal list，并写入 Redis meta `abort_requested`。
  - `schedule.lua` 在 `abort_requested` 存在时不再启动新工具。
  - `complete.lua` 在 abort 后拒绝晚到的正常 success / failed terminal，只允许 cancel terminal。
  - abort 会原子取消 WAITING 与 RUNNING tool，删除 lease，并生成 synthetic cancel terminal。
  - `RedisToolRuntime` 的 `CancellationToken` 读取 Redis abort 状态；`CancelOrderTool` 在 confirm token consume 和真实业务副作用前检查取消。
- T07 主要改动：
  - 新增 `RunStateMachine`，集中 `CREATED/RUNNING/WAITING_USER_CONFIRMATION/terminal` 状态迁移。
  - confirm token 过期使用 `RunStatus.TIMEOUT`，不复用 tool cancelReason。
  - abort 不覆盖已终态 run。
- T08 主要改动：
  - 新增 `ConfirmationIntentService`，把 continuation 输入分为 `CONFIRM/REJECT/AMBIGUOUS`。
  - 修复 `contains("no")` 误判，`no problem, cancel it` 被识别为确认，裸 `no` 才是拒绝。
  - 模糊输入会追加澄清消息，并让 run 回到 `WAITING_USER_CONFIRMATION`，不会调用 provider。
- T09 主要改动：
  - 新增 `AgentRequestPolicy`，统一 create/continue 请求预算与 LLM 参数边界。
  - 限制 message 数量、单条 content 长度、总字符数、model 白名单、temperature、maxTokens、maxTurns。
  - 非法请求由 Controller 稳定返回 400。
- T12 主要测试覆盖：
  - run owner 越权、continuation 工具权限固化、run context fail closed。
  - synthetic close 幂等与 `TranscriptPairValidator` 配对通过。
  - Redis abort 取消 running/waiting，阻止 schedule，拒绝 late complete。
  - `CancelOrderTool` 在 cancellation token 命中时不产生业务副作用。
  - 确认语义误判、模糊确认、请求参数越界、trajectory DTO 脱敏。
- T13 文档收口：
  - README 更新为当前分层架构、安全边界、abort 行为、synthetic result 设计和带 `X-User-Id` 的 trajectory 查询。
  - Postman collection 增加 reject confirm、query-only permission、forbidden trajectory、invalid model、abort 等负向场景。
  - `spec/requirement.md` 与 `spec/design.md` 同步补充 confirmation intent 与 terminal abort 规则。
- 验证结果：
  - targeted：`mvn -Dtest=com.ai.agent.api.ConfirmationIntentServiceTest,com.ai.agent.tool.ToolResultCloserTest,com.ai.agent.api.DefaultAgentLoopRunContextTest,com.ai.agent.api.AgentRequestPolicyTest test`，18 tests，0 failures，0 errors。
  - targeted：`mvn -Dtest=com.ai.agent.api.AgentControllerAccessTest,com.ai.agent.RunAccessManagerTest,com.ai.agent.api.RunStateMachineTest test`，37 tests，0 failures，0 errors。
  - targeted：`MYSQL_PASSWORD=*** mvn -Dtest=com.ai.agent.RedisToolStoreIntegrationTest,com.ai.agent.CancelOrderToolIntegrationTest,com.ai.agent.RunRepairServiceIntegrationTest,com.ai.agent.TrajectoryStoreIntegrationTest test`，12 tests，0 failures，0 errors。
  - full：`MYSQL_PASSWORD=*** mvn test`，80 tests，0 failures，0 errors，BUILD SUCCESS。
- review gate：
  - 第一轮 review agent 使用 `java-alibaba-review` 发现 2 个 P1 和 1 个 P2：
    - `ConfirmationIntentService` 先匹配确认，导致 `no problem, don't cancel`、`不可以执行` 等否定表达被误判为确认。
    - abort 直接把 RUNNING 非幂等写工具合成为 `CANCELLED`，可能掩盖已经发生的订单取消副作用。
    - abort 后仍可 ingest 新工具，导致工具停在 `WAITING` 并等到 timeout。
  - 已修复：
    - confirmation intent 改为先识别显式拒绝，并补充否定反例测试。
    - Redis abort 只 synthetic cancel `WAITING` 和幂等 `RUNNING` 工具；非幂等 `RUNNING` 写工具保留真实 complete 审计路径。
    - `INGEST_SCRIPT` 增加 `abort_requested` 原子检查；abort 后 ingest 的工具直接写成 synthetic terminal，避免 waiter 空等。
  - 修复后 targeted：`mvn -Dtest=com.ai.agent.api.ConfirmationIntentServiceTest test`，5 tests，0 failures，0 errors。
  - 修复后 targeted：`MYSQL_PASSWORD=*** mvn -Dtest=com.ai.agent.RedisToolStoreIntegrationTest,com.ai.agent.CancelOrderToolIntegrationTest test`，10 tests，0 failures，0 errors。
  - 修复后 full：`MYSQL_PASSWORD=*** mvn test`，83 tests，0 failures，0 errors，BUILD SUCCESS。
- final re-review 收口：
  - review agent 复查后指出确认意图仍需覆盖 `do not go ahead`、`please don't go ahead`、`别继续`、`不要继续取消`、`别确认取消`、`不要取消吧` 等否定短语，并且 `no problem` 不应单独作为写操作确认。
  - 已修复：`ConfirmationIntentService` 收窄确认条件，`no problem` 单独输入改为 `AMBIGUOUS`，明确动作确认仍支持 `no problem, cancel it`、`go ahead`、`确认取消`；补充英文/中文否定短语回归测试。
  - final targeted：`mvn -Dtest=com.ai.agent.api.ConfirmationIntentServiceTest test`，5 tests，0 failures，0 errors。
  - final full：`MYSQL_PASSWORD=*** mvn test`，83 tests，0 failures，0 errors，BUILD SUCCESS。
  - final diff check：`git diff --check`，exit 0。
  - 二次 review agent 继续指出 2 个 P1：
    - 正向 `contains("cancel it") / contains("go ahead") / contains("可以取消")` 仍过宽，询问句可能被误判为确认。
    - Unicode 弯引号 `don’t` 未归一化，`don’t cancel it` 可能跳过拒绝匹配后命中确认。
  - 已按红绿测试修复：
    - normalize 阶段统一 `’`、`‘`、`＇`、`` ` `` 为 ASCII `'`。
    - 疑问句/条件句先返回 `AMBIGUOUS`，例如 `what happens if I cancel it?`、`do I need to cancel it?`、`这个可以取消吗`。
    - 正向确认改为更窄的 exact / 明确命令式匹配；拒绝表达保持宽匹配。
  - 二次修复红测：新增用例后 `mvn -Dtest=com.ai.agent.api.ConfirmationIntentServiceTest test` 先失败，命中 `don’t cancel it` 与询问句误判。
  - 二次修复 targeted：`mvn -Dtest=com.ai.agent.api.ConfirmationIntentServiceTest test`，6 tests，0 failures，0 errors。
  - 二次修复 full：`MYSQL_PASSWORD=*** mvn test`，84 tests，0 failures，0 errors，BUILD SUCCESS。
  - 二次复审：review agent 使用 `java-alibaba-review` 复查确认意图修复，未发现需要阻断的 P0/P1/P2 issue。
- 踩坑记录：
  - `ToolResultCloser` 只写 result trace 不够，必须同步追加 `TOOL` message，否则 provider replay 仍会出现 missing tool result。
  - abort 后 late complete 不能靠 Java 层判断，必须在 Redis Lua complete CAS 中做原子拒绝。
  - 写操作确认的工程原则是“拒绝宽、疑问宽、确认窄”；宁可多一次澄清，也不能把疑问句或移动端弯引号否定当成确认。
  - `RunRepairService` 测试 fake 在状态机 CAS 失败后需要实现 `findRunStatus`，否则不能覆盖真实 race 分支。
  - Postman trajectory 查询必须带 `X-User-Id`，否则会被 401 拦截。

---

# Agent Buyer V2 Progress

本节记录 V2 三个里程碑的开发过程、设计决策和踩坑。延续 V1a hardening 的记录方式：每个任务启动、review、修复、完成都追加一条；每个里程碑结束有一条收口记录。

V2 必须按 `V2.0 -> V2.1 -> V2.2` 顺序推进，里程碑内部按 `task.md` 的 `blockedBy` 依赖图并行。任务状态以 `task.md` 为准，本文档只记录过程与决策。

状态对照（与 `task.md` 一致）：

- `PENDING`：尚未开始
- `IN_PROGRESS`：sub agent 正在开发或主 agent 正在集成
- `BLOCKED`：被前置任务、review finding 或外部条件阻塞
- `DONE`：sub agent 自测通过 + review agent 无 P0/P1/P2 + 主 agent 集成测试通过 + `task.md` 已更新

## V2.0 启动准备

- 计划开始时间：V1a hardening 完成后立即启动。
- V1a 收尾基线（来自上文 Wave 3-6 完成记录）：
  - 全量 `MYSQL_PASSWORD=*** mvn test`，84 tests，0 failures，BUILD SUCCESS。
  - `git diff --check` exit 0。
  - `java-alibaba-review` 复审无 P0/P1/P2。
- V2.0 第一波建议执行顺序（依赖 task.md）：
  - Wave 0：`V20-01`（基线测试 + 环境校验，唯一前置）。
  - Wave 1：`V20-02a` + `V20-04a`（并行，都只依赖 V20-01；写入范围分别是 RunContext provider 字段 + Flyway V7、RunStateMachine PAUSED 迁移）。
  - Wave 2：`V20-02`（依赖 V20-02a，建立 `LlmProviderAdapterRegistry`）。
  - Wave 3：`V20-03` + `V20-06`（并行，前者接 Qwen，后者实现 ContextViewBuilder，互不冲突）。
- 写入范围预约：
  - `RunContextStore` / `agent_run_context` / Flyway V7：归 `V20-02a` 唯一 owner。
  - `RunStateMachine`：归 `V20-04a` 唯一 owner，后续 `V20-05` 只读使用。
  - `DefaultAgentLoop` / `AgentTurnOrchestrator` / `LlmAttemptService`：V2.0 期间是高冲突文件，主 agent 必须先指定 owner 再分发。
  - `LuaRedisToolStore`：V2.0 不动；V2.2 才修改。

## V2.0 任务进度

### V20-01 DONE

- 主 agent 职责：建立 V2.0 测试基线、确认 DeepSeek smoke / MySQL / Redis 可用、把基线写入本节。
- sub agent 任务：执行 `MYSQL_PASSWORD=*** mvn test`，记录 tests/failures/errors；运行 DeepSeek smoke；确认本地 Redis、MySQL 连接配置；输出基线快照。
- review agent：本任务不涉及代码改动，跳过 `java-alibaba-review`，但需要 review 基线记录是否完整。
- 验收前置：全量测试通过；基线写入 `progress.md`。

启动记录：

- 启动时间：2026-05-01。
- 主 agent 已通读 `spec/requirement.md`、`spec/design.md`、`spec/task.md`、`spec/progress.md`。
- 已将 `task.md` 中 `V20-01` 标记为 `IN_PROGRESS`。
- 当前要验证的基线：全量 `mvn test`、MySQL 连接、Redis 连接、DeepSeek smoke、本地配置与工作区状态。

完成记录：

- 完成时间：2026-05-01 22:05 CST。
- JDK / Maven：OpenJDK 21.0.10，Apache Maven 3.6.3。
- Docker 基线：`agent-buyer-mysql8` 使用 MySQL 8.0，映射 `3307->3306`；`agent-buyer-redis7` 使用 Redis 7.2，映射 `6380->6379`。
- MySQL 基线：`127.0.0.1:3307` 可连接，版本 `8.0.46`。
- Redis 基线：`127.0.0.1:6380` 返回 `PONG`。
- 应用配置基线：默认 provider 为 `deepseek`，默认模型为 `deepseek-reasoner`；Qwen V2.0 默认模型按用户确认使用 `qwen-plus`。
- 全量测试：`MYSQL_PASSWORD=*** mvn test`，84 tests，0 failures，0 errors，0 skipped，`BUILD SUCCESS`。
- DeepSeek live smoke：通过 shell profile 读取 `DEEPSEEK_API_KEY`，最小 chat completions 请求返回 HTTP 200，响应包含 `choices`。
- Git 工作区基线：当前存在 V1a hardening 与 V2 spec 相关未提交文件；从 V20 开始按任务提交，避免后续任务混在一起。
- V20-01 不涉及 Java/SQL/Lua/YAML 代码变更，跳过 `java-alibaba-review`，由主 agent 记录基线并验收。

### V20-02a DONE

- 写入范围：`agent_run_context`、`RunContext`、`RunContextStore`、`MybatisRunContextStore`、`AgentRunContextEntity`、`AgentRunContextMapper.xml`、Flyway `V7__run_context_provider_fields.sql`。
- 前置：`V20-01`。
- 关注点：迁移可重复执行；旧 run context 必须有 fail-closed 默认（参照 V6 历史经验，宁可保守也不放大权限）；create run / continuation 两条路径都要测试。

启动记录：

- 启动时间：2026-05-01 22:07 CST。
- worker 写入范围限定为 RunContext provider 字段、V7 Flyway migration、相关 MyBatis entity/store/test。
- 主 agent 要求先补红测，再实现，完成后运行 targeted 测试与 Flyway 校验。

集成记录：

- worker 已完成 provider 选型持久化，`RunContext` 增加 `primaryProvider / fallbackProvider / providerOptions`。
- V7 Flyway migration 已在本地 MySQL 上通过，Flyway validate 显示 7 migrations。
- 主 agent 全量回归：`MYSQL_PASSWORD=*** mvn test`，90 tests，0 failures，`BUILD SUCCESS`。
- review gate 发现历史 context backfill 不能默认启用 Qwen fallback，已改成 `fallback_provider = primary_provider` 且 `provider_options.fallbackEnabled=false`，保证 legacy run fail-closed。
- 修改已执行过的本地 Flyway V7 后出现 checksum mismatch，使用 Flyway repair 修复本地开发库 schema history；记录为坑：共享/已发布 migration 不应再改，当前仍处于未提交开发阶段所以允许。
- 最终全量回归：`MYSQL_PASSWORD=*** mvn test`，92 tests，0 failures，0 errors，`BUILD SUCCESS`。
- `java-alibaba-review` gate：未发现阻断 issue；遗留提醒是 V20-04 必须明确 `{}` providerOptions 的 fallback 默认语义。

### V20-02 DONE

- 写入范围：`LlmProviderAdapter` 接口拆分、`LlmProviderAdapterRegistry`、`DeepSeekProviderAdapter` 改造、`LlmAttemptService` 解耦。
- 前置：`V20-02a`（要从 RunContext 读取 provider 选型）。
- 关注点：现有 DeepSeek 测试不能回归；business code 不再 import 任何具体 provider 类。

启动记录：

- 启动时间：2026-05-01 22:27 CST。
- owner：主 agent 负责验收与集成；worker 负责 provider registry/profile 边界改造；review agent 使用 `java-alibaba-review` gate。
- TDD 目标：先补 registry 根据 provider name 选择 adapter、unknown provider fail closed、`LlmAttemptService` 从 `RunContext.primaryProvider` 读取 provider 的红测。

集成记录：

- worker 已完成 `LlmProviderAdapterRegistry`、`providerName()`、`DeepSeekCompatibilityProfile`、`LlmAttemptService` 按 providerName 选择 adapter。
- 主 agent 集成时发现 `DeepSeekProviderAdapter` 如果继续按 `ProviderCompatibilityProfile` 接口注入，V20-03 增加 Qwen profile 后会出现 Spring 多 bean 歧义；已改为注入 `DeepSeekCompatibilityProfile`，把 provider 方言留在 adapter 边界内。
- targeted：`mvn -Dtest=com.ai.agent.llm.LlmProviderAdapterRegistryTest,com.ai.agent.api.LlmAttemptServiceTest test`，5 tests，0 failures，`BUILD SUCCESS`。
- full：`MYSQL_PASSWORD=*** mvn test`，97 tests，0 failures，0 errors，`BUILD SUCCESS`。
- review gate：未发现阻断 issue；采纳“provider name 首尾空格应 fail closed”的建议，补红测后实现 `strip` 等值校验。
- 最终 targeted：`mvn -Dtest=com.ai.agent.llm.LlmProviderAdapterRegistryTest,com.ai.agent.api.LlmAttemptServiceTest test`，6 tests，0 failures，`BUILD SUCCESS`。
- 最终 full：`MYSQL_PASSWORD=*** mvn test`，98 tests，0 failures，0 errors，`BUILD SUCCESS`。

### V20-03 DONE

- 写入范围：`QwenProviderAdapter`、`QwenCompatibilityProfile`、`config` 增加 Qwen base-url/api-key/model、单元测试。
- 前置：`V20-02`。
- 关注点：Qwen stream tool delta 组装；error mapping 与 DeepSeek 行为对齐；不污染 DeepSeek profile。

启动记录：

- 启动时间：2026-05-01 22:35 CST。
- owner：主 agent 负责验收与 review；worker 负责 Qwen adapter/profile/config/test。
- TDD 目标：先补 Qwen profile tool schema 转换、Qwen streaming tool delta assembler、Qwen usage/finish reason/error mapping 的红测，再实现。

集成记录：

- worker 已完成 `QwenProviderAdapter`、`QwenCompatibilityProfile`、`agent.llm.qwen` 配置和本地 fake SSE provider 测试。
- review gate 发现 `qwen-plus` 被加入默认 allowed models 会造成 “qwen model -> deepseek provider” 错配；已移除默认开放，并补 `AgentRequestPolicyTest`，在 provider routing 真正实现前 fail closed。
- review gate 发现 Qwen finish/error mapping 覆盖不足；已补 missing API key、500 retry、`length`、`content_filter` 测试。
- targeted：`mvn -Dtest=com.ai.agent.api.AgentRequestPolicyTest,com.ai.agent.llm.QwenProviderAdapterTest,com.ai.agent.llm.QwenCompatibilityProfileTest,com.ai.agent.llm.LlmProviderAdapterRegistryTest test`，20 tests，0 failures，`BUILD SUCCESS`。
- full：`MYSQL_PASSWORD=*** mvn test`，106 tests，0 failures，0 errors，`BUILD SUCCESS`。
- `java-alibaba-review` 复审：未发现 P0/P1/P2；确认 Qwen adapter/profile/config 与 DeepSeek 隔离，默认入口暂不开放 `qwen-plus`。

### V20-04 DONE

- 写入范围：`ProviderFallbackPolicy`、`LlmAttemptService` fallback 分支、`agent_llm_attempt` 写入扩展。
- 前置：`V20-03`。
- 关注点：建连前才允许 fallback；stream 已写入 tool delta 后必须禁止 fallback（这是 V2 约束的核心安全边界）；fallback 选型只能从 RunContext 读取。

启动记录：

- 启动时间：2026-05-01 22:45 CST。
- owner：主 agent 负责 fallback 语义验收；worker 负责 `ProviderFallbackPolicy`、异常分类、`LlmAttemptService` fallback 分支与测试。
- TDD 目标：primary retryable pre-stream failure fallback 到 RunContext.fallbackProvider；unknown/disabled fallback fail closed；stream 已产生 delta 后不 fallback；两个 attempt 都写 trajectory。

集成记录：

- worker 已完成 `ProviderCallException` / `ProviderErrorType` / `ProviderFallbackPolicy`、provider 异常分类、`LlmAttemptService` fallback attempt 与 `llm_fallback` event。
- 主 agent 集成时发现 fallback 不能沿用 primary request model，否则会把 `deepseek-reasoner` 打给 Qwen；修复为 primary 使用请求模型，fallback 使用 fallback provider 的 `defaultModel()`。
- review gate 发现 provider 4xx/429/5xx 原始 body 会进入长期 trajectory；已改为关闭错误响应 body，不拼接 body，只持久化 `providerErrorType` / `statusCode` / 稳定 message。
- 补 `DeepSeekProviderAdapterTest`，覆盖 400 非重试、500 重试前置失败、malformed stream -> `STREAM_STARTED`；Qwen 测试同步断言错误 body 不泄漏。
- targeted：`mvn -Dtest=com.ai.agent.llm.QwenProviderAdapterTest,com.ai.agent.llm.DeepSeekProviderAdapterTest,com.ai.agent.api.LlmAttemptServiceTest test`，15 tests，0 failures，`BUILD SUCCESS`。
- full：`MYSQL_PASSWORD=*** mvn test`，114 tests，0 failures，0 errors，`BUILD SUCCESS`。
- `java-alibaba-review` 复审：未发现阻断 issue；残余提醒是后续新增 provider 也必须遵守 `ProviderCallException.message` 不带原始 body 的约束。

### V20-04a DONE

- 写入范围：`RunStateMachine` 迁移表、`RunStatus` 是否新增 `PAUSED` 字面量、`RunStateMachine` 单元测试。
- 前置：`V20-01`。
- 关注点：`PAUSED` 必须可 continuation；`CANCELLED/FAILED/TIMEOUT` 仍是终态不可 continuation；与 V1a 已有 CAS 路径不冲突。

启动记录：

- 启动时间：2026-05-01 22:07 CST。
- worker 写入范围限定为 `RunStatus`、`RunStateMachine` 和状态机单元测试。
- 主 agent 要求先补 `RUNNING -> PAUSED`、`PAUSED` continuation、`CANCELLED` continuation 拒绝的红测，再实现。

集成记录：

- worker 已完成 `PAUSED` 状态、`RUNNING -> PAUSED`、`PAUSED -> RUNNING` continuation。
- 主 agent 集成时发现 `startContinuation` 盲试 `WAITING_USER_CONFIRMATION` 与 `PAUSED` 两个 CAS，会让 CAS race 路径多出一次无效 transition；同时 `RunAccessManager` 仍只允许 `WAITING_USER_CONFIRMATION` continuation。
- 修复方式：`RunAccessManager` 将 continuable 状态定义为 `WAITING_USER_CONFIRMATION / PAUSED`，并把已读取的状态传给 `RunStateMachine.startContinuation(runId, status)`，避免重复 DB 读和无效 CAS。
- targeted：`mvn -Dtest=com.ai.agent.api.RunStateMachineTest,com.ai.agent.RunAccessManagerTest test`，21 tests，0 failures，`BUILD SUCCESS`。
- full：`MYSQL_PASSWORD=*** mvn test`，90 tests，0 failures，`BUILD SUCCESS`。
- review gate 发现 continuation 失败回滚不能硬编码回 `WAITING_USER_CONFIRMATION`，否则 `PAUSED` continuation 在 executor reject / pre-append failure 后会被错误恢复；已在 `ContinuationPermit` 保存原状态并按原状态 CAS 回滚。
- 最终 targeted：`mvn -Dtest=com.ai.agent.api.RunStateMachineTest,com.ai.agent.RunAccessManagerTest,com.ai.agent.api.DefaultAgentLoopRunContextTest test`，31 tests，0 failures，`BUILD SUCCESS`。
- 最终 full：`MYSQL_PASSWORD=*** mvn test`，92 tests，0 failures，0 errors，`BUILD SUCCESS`。
- `java-alibaba-review` gate：未发现阻断 issue；确认 `WAITING_USER_CONFIRMATION / PAUSED` continuation 的原状态恢复路径由 CAS 保护，不覆盖终态。

### V20-05 DONE

- 写入范围：`AgentExecutionBudget`、`AgentTurnOrchestrator` 嵌入预算检查、`agent_event` 新事件类型 `MAIN_TURN_BUDGET` / `RUN_WIDE_BUDGET`。
- 前置：`V20-04`、`V20-04a`。
- 关注点：单 user turn 30 次 + run-wide 80 次双层预算；触发后必须 `RUNNING -> PAUSED`，不允许覆盖已终态 run。

启动记录：

- 启动时间：2026-05-01 22:59 CST。
- 工程判断：预算检查必须卡在真实 provider HTTP 调用前，而不是只在 `AgentTurnOrchestrator` 外层循环加一；否则 fallback 和 provider 内部 retry 不会计入预算。
- TDD 目标：per-turn 超限、run-wide 超限、超限后 run -> `PAUSED` + `agent_event`，并确保 budget denied 不写伪造 LLM attempt。

集成记录：

- 新增 `AgentExecutionBudget`、`RunLlmCallBudgetStore`、`RedisRunLlmCallBudgetStore` 和 `LlmCallObserver`，provider 每次真实 HTTP call 前 reserve budget；provider 内部 retry 与 fallback 都会计入。
- `AgentTurnOrchestrator` 在 budget exceeded 时执行 `RUNNING -> PAUSED`，写 `MAIN_TURN_BUDGET` / `RUN_WIDE_BUDGET` event，并通过 SSE final 返回 `nextActionRequired=user_input`。
- review gate 发现测试辅助构造器曾绕过 run-wide budget；已改为 package-private helper 并使用本地预算 store，避免无条件放行。
- review gate 发现 fallback event 可能在 fallback budget 拒绝前写入；已延后 `llm_fallback` event 到 fallback 成功或真实 provider 失败之后，budget 拒绝不写 orphan event/attempt。
- review gate 发现 Redis budget key 缺少生命周期；已在 Lua 成功与拒绝路径都刷新 7 天 TTL，并补 TTL 集成断言。
- targeted：`MYSQL_PASSWORD=*** mvn -Dtest=com.ai.agent.RedisToolStoreIntegrationTest,com.ai.agent.api.AgentExecutionBudgetTest,com.ai.agent.api.AgentTurnOrchestratorBudgetTest,com.ai.agent.api.LlmAttemptServiceTest,com.ai.agent.llm.QwenProviderAdapterTest test`，24 tests，0 failures，`BUILD SUCCESS`。
- full：`MYSQL_PASSWORD=*** mvn test`，120 tests，0 failures，0 errors，`BUILD SUCCESS`。
- `java-alibaba-review` 复审：未发现阻断 issue；确认 Redis budget key TTL、fallback budget rejection 无 orphan event、helper 构造器不再绕过预算。

### V20-06 DONE

- 写入范围：`ContextViewBuilder`、`ProviderViewMessage`、`LlmAttemptService` 调用前接入 view、复用 `TranscriptPairValidator`。
- 前置：`V20-02`。
- 关注点：原始 trajectory 不能被改写；compact 前后都要走 pair validator。

启动记录：

- 启动时间：2026-05-01 23:12 CST。
- 工程判断：先做 provider view 的明确边界，不急着 compact；后续 `LargeResultSpiller / MicroCompactor / SummaryCompactor` 都只作用于 provider view，不直接改 MySQL raw trajectory。
- TDD 目标：builder 从 `TrajectoryReader` 加载 raw messages；返回 copy；raw/view 都过 `TranscriptPairValidator`；orphan tool result fail fast。

集成记录：

- 新增 `ContextViewBuilder` 与 `ProviderContextView`，provider 请求前统一从 `TrajectoryReader` 加载 raw messages，先校验 raw transcript，再复制成 provider view 并再次校验。
- `AgentTurnOrchestrator` 不再直接依赖 `TrajectoryReader` / `TranscriptPairValidator`，改为通过 `ContextViewBuilder` 获得 provider messages；MySQL 原始 trajectory 不被修改。
- 踩坑记录：review agent 提醒 provider view 当前在 attempt 层仍被拆成 `List<LlmMessage>`，类型边界还不是强约束；V20-07/V20-08 接 compactor 时优先把 compaction 入口收敛在 `ContextViewBuilder`，避免新增调用方绕过 builder。
- targeted：`MYSQL_PASSWORD=*** mvn -Dtest=com.ai.agent.llm.ContextViewBuilderTest,com.ai.agent.api.AgentTurnOrchestratorBudgetTest test`，4 tests，0 failures，`BUILD SUCCESS`。
- full：`MYSQL_PASSWORD=*** mvn test`，122 tests，0 failures，0 errors，`BUILD SUCCESS`。
- `java-alibaba-review`：未发现 P0/P1/P2 阻断 issue。

状态：DONE。

### V20-07 DONE

- 写入范围：`LargeResultSpiller`、`ContextViewBuilder` 接入；不动 MySQL 原始结果。
- 前置：`V20-06`。
- 关注点：threshold（2000 token）从配置读；resultPath 是 logical path，不引入对象存储。

启动记录：

- 启动时间：2026-05-01 23:18 CST。
- 工程判断：large spill 只改变 provider view 中的 tool result 文本，不能改 MySQL raw message；resultPath 使用可追溯的 logical path，V2.0 不引入对象存储。
- TDD 目标：超过 2000 token 的 tool result 保留头 200 token、尾 200 token，中间插入 `<resultPath>`；未超过阈值不改；compact 后 transcript pairing 仍通过。

集成记录：

- 完成时间：2026-05-01 23:20 CST。
- 新增 `LargeResultSpiller` 与 `TokenEstimator`，从 `AgentProperties.context` 读取 `large-result-threshold-tokens=2000`、`large-result-head-tokens=200`、`large-result-tail-tokens=200`；`application.yml` 已补默认配置。
- `ContextViewBuilder` 在 raw transcript 通过 `TranscriptPairValidator` 后对 provider view copy 执行 large result spill，再校验最终 provider view；MySQL raw trajectory message 不删除、不修改。
- `resultPath` 使用 V2.0 logical path：`trajectory://runs/{runId}/tool-results/{toolUseId}/full`，未引入对象存储。
- TDD 验证：先看到 `LargeResultSpillerTest,ContextViewBuilderTest` 因缺失 spiller/config 编译失败；实现后同一测试集 `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0`。
- review gate 发现 `TokenEstimator` 对“带空白 + 超长 segment”的 pretty JSON/blob 低估 token；已先补红测 `spillsPrettyJsonContainingLongWhitespaceSeparatedValue`，再改为按 segment 长度估算。
- targeted：`MYSQL_PASSWORD=*** mvn -Dtest=com.ai.agent.llm.LargeResultSpillerTest,com.ai.agent.llm.ContextViewBuilderTest,com.ai.agent.api.AgentTurnOrchestratorBudgetTest test`，8 tests，0 failures，`BUILD SUCCESS`。
- full：`MYSQL_PASSWORD=*** mvn test`，126 tests，0 failures，0 errors，`BUILD SUCCESS`。
- 踩坑记录：review/subagent 如果没有带 `MYSQL_PASSWORD`，Spring 集成测试会以 root 空密码连接 MySQL 并失败；本项目全量回归统一使用 `MYSQL_PASSWORD=*** mvn test`。
- `java-alibaba-review` 复审：未发现 P0/P1/P2 阻断 issue。

### V20-08 DONE

- 写入范围：`MicroCompactor`、token estimator、`ContextViewBuilder` 接入。
- 前置：`V20-07`。
- 关注点：旧 tool result 替换为 `<oldToolResult>` 占位但保留 message id，pair validator 不能爆。

启动记录：

- 启动时间：2026-05-01 23:28 CST。
- 工程判断：micro compact 作为 large spill 后的第二道 provider view 压缩，只处理旧 `TOOL` message 的 content，不删除 message，不改变 `toolUseId`，并保留最近消息窗口避免压缩当前 turn 的工具结果。
- TDD 目标：总 token 达到 50000（测试用小阈值）后，旧 tool result 替换为 `<oldToolResult>Tool result is deleted due to long context</oldToolResult>`；尾部近期 tool result 不替换；压缩后 `TranscriptPairValidator` 通过。

实现记录：

- 实现时间：2026-05-01 23:30 CST。
- 新增 `MicroCompactor`，从 `AgentProperties.context.micro-compact-threshold-tokens` 读取阈值，默认 `50000`；provider view 总 token 达到阈值时，仅将最后 3 条 message 之前的 `TOOL` content 替换为 `<oldToolResult>Tool result is deleted due to long context</oldToolResult>`。
- `ContextViewBuilder` 接入顺序为 raw `TranscriptPairValidator` -> `LargeResultSpiller` -> `MicroCompactor` -> final `TranscriptPairValidator`；压缩只作用 provider view，不修改 MySQL raw trajectory message。
- TDD 红灯：`mvn -Dtest=com.ai.agent.llm.MicroCompactorTest,com.ai.agent.llm.ContextViewBuilderTest test` 因缺失 `MicroCompactor` 与 `microCompactThresholdTokens` 配置编译失败。
- targeted 绿灯：`mvn -Dtest=com.ai.agent.llm.MicroCompactorTest,com.ai.agent.llm.ContextViewBuilderTest,com.ai.agent.api.AgentTurnOrchestratorBudgetTest test`，9 tests，0 failures，`BUILD SUCCESS`。
- 全量验证：`MYSQL_PASSWORD=*** mvn test`，130 tests，0 failures，`BUILD SUCCESS`。
- `java-alibaba-review`：未发现 P0/P1/P2 阻断 issue；确认只替换旧 `TOOL` content、保留最近 3 条 message、最终 provider view 通过配对校验。
- 主 agent 复跑 full：`MYSQL_PASSWORD=*** mvn test`，130 tests，0 failures，0 errors，`BUILD SUCCESS`。
- 状态：DONE。

### V20-09 DONE

- 写入范围：`SummaryCompactor`、`ProviderSummaryGenerator`、summary JSON schema、`ContextViewBuilder` provider view 接入；不动原始 trajectory。
- 前置：`V20-08`。
- 关注点：summary 必须包含 `summaryText / businessFacts / toolFacts / openQuestions / compactedMessageIds`；保留 system prompt 和最近消息预算窗口。

启动记录：

- 启动时间：2026-05-01 23:34 CST。
- 工程判断：先抽 `SummaryGenerator`/`SummaryCompactor` 边界，保证 summary JSON schema、保留规则和 provider view 压缩正确；summary 生成不写 trajectory，不产生 assistant/tool replay message，只作为 context lifecycle 的内部 provider 调用。
- TDD 目标：summary message 使用 `ASSISTANT + extras.compactSummary=true`；content 是 JSON 且包含 `summaryText/businessFacts/toolFacts/openQuestions/compactedMessageIds`；保留 system、前三条非 system、最近消息预算窗口；被 summary 覆盖的 assistant tool call 与 matching tool result 不被拆散。

实现记录：

- 实现时间：2026-05-01 23:38 CST。
- 新增 `SummaryGenerator` 接口与 `ProviderSummaryGenerator`，生产默认通过配置 provider 生成 JSON summary；`DeterministicSummaryGenerator` 仅保留为测试用 generator，不注册为 Spring bean。
- `ProviderSummaryGenerator` 使用 `agent.llm.provider` 对应 adapter，temperature 固定 `0.0`，`maxTokens` 使用 `agent.context.summary-max-tokens`；请求不传 tools，要求模型只输出 JSON object。
- 新增 `SummaryCompactor`，从 `AgentProperties.context.summary-compact-threshold-tokens` 读取阈值，默认 `30000`；`recent-message-budget-tokens` 默认 `2000`。压缩仅作用 provider view，不写 MySQL raw trajectory。
- `SummaryCompactor` 保留所有 `SYSTEM` message、前三条非 system message、最后 3 条 message，并在最近 3 条总 token 未达到 recent budget 时继续向前保留；assistant tool call 与 matching tool result 使用 block 一起保留或一起进入 summary，避免 provider pair validation 失败。
- 修复 review P2：summary JSON 需要通过 `ObjectMapper` 解析并校验 `summaryText/businessFacts/toolFacts/openQuestions/compactedMessageIds`；`compactedMessageIds` 必须与实际被压缩 message id 完全一致。
- 修复 review P2：assistant tool call block 按原始 transcript index 范围保留，避免多 tool result 按 tool call 顺序重排。
- 修复 review P2：summary compact 后重新估算 token，若 provider view 仍超过 `agent-loop.hard-token-cap` 则 fail closed。
- summary message 使用 `ASSISTANT` role，`extras.compactSummary=true`，`extras.compactedMessageIds` 记录被 summary 覆盖的 message id；summary content 中也包含相同 `compactedMessageIds`。
- `ContextViewBuilder` 接入顺序更新为 raw `TranscriptPairValidator` -> `LargeResultSpiller` -> `MicroCompactor` -> `SummaryCompactor` -> final `TranscriptPairValidator`。
- TDD 红灯：`mvn -Dtest=com.ai.agent.llm.SummaryCompactorTest test` 因缺失 `SummaryGenerator`、`SummaryCompactor`、summary/recent 配置编译失败。
- review 修复红灯：`mvn -Dtest=com.ai.agent.llm.ProviderSummaryGeneratorTest,com.ai.agent.llm.SummaryCompactorTest test` 因缺少 `ProviderSummaryGenerator` 与 `summaryMaxTokens` 配置编译失败。
- 第二轮 review 发现边界：`messagesToCompact.isEmpty()` 分支未校验 hard cap。先新增 `failsClosedWhenThresholdReachedButAllMessagesArePreserved` 红灯测试，再在该分支调用 `assertWithinHardTokenCap`。
- 踩坑：`ProviderSummaryGenerator` 同时存在 public 构造器与 package-private 测试构造器时，Spring 无法选择构造器，导致 `ApplicationContext` 启动失败；修复为只保留一个 public 构造器，测试显式传入 `ObjectMapper`。
- targeted 绿灯：`mvn -Dtest=com.ai.agent.llm.ProviderSummaryGeneratorTest,com.ai.agent.llm.SummaryCompactorTest,com.ai.agent.llm.ContextViewBuilderTest,com.ai.agent.api.AgentTurnOrchestratorBudgetTest test`，18 tests，0 failures，`BUILD SUCCESS`。
- `java-alibaba-review`：三轮 review 后无未解决 P0/P1/P2；确认 provider-backed summary、hard cap fail closed、empty-compaction 分支测试覆盖。
- 全量验证：`MYSQL_PASSWORD=*** mvn test`，142 tests，0 failures，0 errors，`BUILD SUCCESS`。
- 状态：DONE。

### V20-10 DONE

- 写入范围：`agent_context_compaction` 表、Flyway `V8__agent_context_compaction.sql`、`ContextCompactionRepository`、trajectory query DTO 扩展。
- 前置：`V20-09`。
- 关注点：迁移可重复执行；查询接口仍要走 `RunAccessManager`。

启动记录：

- 启动时间：2026-05-01 23:53 CST。
- 工程判断：compactor 保持纯 provider-view 转换，不直接依赖 MySQL；由 `ContextViewBuilder` 在每个阶段比较 before/after 并写 `ContextCompactionStore`，记录策略、整段 view token 前后变化、被压缩 message id。
- 分工：sub agent 负责 MySQL migration、entity/mapper/store、trajectory query DTO；主 agent 负责 builder 记录点、集成测试、review gate 与提交。

实现记录：

- 完成时间：2026-05-02 00:28 CST。
- 新增 `agent_context_compaction` MySQL 轨迹表、`ContextCompactionStore`/MyBatis 实现、`CompactionDto`，trajectory query 返回 compaction strategy、before/after tokens、compactedMessageIds、turnNo、attemptId。
- 为避免本地 Flyway 已应用 V8 后出现 checksum mismatch，`V8__agent_context_compaction.sql` 保持初始结构，新增 `V8_1__context_compaction_attempt_scope.sql` 幂等补充 `turn_no`、`attempt_id` 与索引。
- review P2 修复：compaction 不再由 `ContextViewBuilder` 直接落库；builder 返回 provider view 与 `ContextCompactionDraft`，由 `LlmAttemptService` 在真实 provider attempt 预算通过后按 attemptId 持久化。
- review P2 修复：fallback attempt 复用同一批 compaction drafts，但以 fallbackAttemptId 写入 attribution；同一 provider retry 中第二次撞 budget 时，如果已有 provider call 被放行，则写 FAILED `agent_llm_attempt`，避免 compaction orphan。
- review P2 修复：provider-backed summary compact 也纳入同一 turn LLM budget。`SummaryGenerator` 接收 `SummaryGenerationContext`，`ProviderSummaryGenerator` 使用其中的 observer，并为 summary provider call 写 summary `agent_llm_attempt`；预算耗尽时 summary provider call 不发生。
- TDD 红灯：新增 `budgetExceededDoesNotPersistCompactionForUnattemptedProviderCall`、`fallbackAttemptReceivesOwnCompactionAttribution`、`retryBudgetRejectionAfterAcceptedProviderCallWritesFailedAttemptForCompactionAttribution`、`budgetExceededBeforeSummaryCompactionDoesNotCallSummaryProvider` 后，分别暴露 orphan attribution 与 summary budget gap。
- targeted 绿灯：`MYSQL_PASSWORD=*** mvn -Dtest=com.ai.agent.llm.ContextViewBuilderTest,com.ai.agent.llm.ProviderSummaryGeneratorTest,com.ai.agent.llm.SummaryCompactorTest,com.ai.agent.trajectory.TrajectoryQueryServiceTest,com.ai.agent.TrajectoryStoreIntegrationTest,com.ai.agent.api.AgentTurnOrchestratorBudgetTest,com.ai.agent.api.LlmAttemptServiceTest test`，33 tests，0 failures，`BUILD SUCCESS`。
- 全量验证：`MYSQL_PASSWORD=*** mvn test`，148 tests，0 failures，0 errors，`BUILD SUCCESS`。
- `java-alibaba-review`：多轮 review 后无未解决 P0/P1/P2；确认首次 budget 拒绝、fallback、provider retry、summary provider call 四条 attribution/budget 链路闭合。
- 状态：DONE。

### V20-11 DONE

- 写入范围：`*IntegrationTest` 系列；不动业务代码。
- 前置：`V20-05`、`V20-10`。
- 关注点：测试必须覆盖 RunContext provider 复用、fallback 安全边界、50K context compact、summary fields、PAUSED 迁移、budget exceeded。

启动记录：

- 启动时间：2026-05-02 00:31 CST。
- 工程判断：V20-11 以集成/负向测试为主，不主动重构生产代码；只有测试串联后暴露真实行为缺口时才修业务实现。
- 分工：主 agent 负责梳理现有覆盖、补 provider/context/budget 的集成测试并最终验收；sub agent 负责独立补充或审视 V2.0 场景覆盖；review agent 使用 `java-alibaba-review` 做最终代码审核。

实现记录：

- 新增 provider selection 复用集成测试：paused run continuation 走 `DefaultAgentLoop -> AgentTurnOrchestrator -> LlmAttemptService`，并断言 provider/model 来自已持久化 RunContext，而不是请求或当前配置默认值。
- 新增 50K context compact 串联测试：在默认级阈值下验证 large result spill、micro compact、summary compact 依次发生，provider view 仍通过 tool call/tool result 配对校验，summary JSON 保留 `summaryText/businessFacts/toolFacts/openQuestions/compactedMessageIds`。
- TDD 红灯：首次 50K 用例期望 summary 压掉旧 tool block，但测试数据的最近 3 条消息太短，`recentMessageBudgetTokens=2000` 按设计会继续保留前一个小 block，导致只压缩了旧 user message。
- 修复方式：让最近 3 条消息本身超过 2000 token，真实覆盖“最后 3 条保留后不再继续向前保留”的边界；重跑 targeted 绿灯。
- targeted 绿灯：`MYSQL_PASSWORD=*** mvn -Dtest=com.ai.agent.llm.ContextViewBuilderTest,com.ai.agent.api.V20ProviderSelectionIntegrationTest,com.ai.agent.api.LlmAttemptServiceTest,com.ai.agent.api.AgentTurnOrchestratorBudgetTest,com.ai.agent.api.RunStateMachineTest,com.ai.agent.llm.QwenProviderAdapterTest,com.ai.agent.llm.QwenCompatibilityProfileTest test`，35 tests，0 failures，`BUILD SUCCESS`。
- `java-alibaba-review`：无 P0/P1/P2；P3 建议收窄 provider selection 测试类名，已从 `V20ProviderFallbackIntegrationTest` 改为 `V20ProviderSelectionIntegrationTest`。
- 全量验证：`MYSQL_PASSWORD=*** mvn test`，150 tests，0 failures，0 errors，`BUILD SUCCESS`。
- 状态：DONE。

### V20-12 DONE

- 写入范围：`README.md`、`postman/*.json`、`application*.yml` 示例配置。
- 前置：`V20-11`。
- 关注点：provider/context/budget 配置全部上 README；Postman 增加 multi-provider smoke 与 PAUSED continuation。

启动记录：

- 启动时间：2026-05-02 00:39 CST。
- 工程判断：V20-12 不改运行时代码，只做用户可验证入口、配置显性化和 V2.0 状态说明；provider/context smoke 优先用脚本复用稳定的 targeted test，避免 Postman 依赖真实 provider 故障来触发 fallback。

实现记录：

- README 更新为 V2.0 当前状态：DeepSeek + Qwen provider registry/fallback、RunContext provider 复用、LLM call budget、context compact 三阶段、`agent_context_compaction` 轨迹表。
- `application.yml` 显式补充 `llm-call-budget-per-user-turn=30`、`run-wide-llm-call-budget=80`，provider/context 配置保持可通过环境变量覆盖。
- 新增 `scripts/v2-provider-context-smoke.sh`，覆盖 Qwen adapter/profile、fallback 边界、RunContext provider/model 复用、50K context compact、PAUSED/budget 状态机。
- smoke 绿灯：`./scripts/v2-provider-context-smoke.sh`，35 tests，0 failures，`BUILD SUCCESS`。
- `java-alibaba-review` 发现 2 个文档 P2：`agent.llm.provider` 容易被误读为主 run primary provider 可配置、README 把 V2.1 SubAgent 限制写得像已实现；已修正为主 run V2.0 固定 DeepSeek primary + Qwen fallback，`agent.llm.provider` 仅说明为 summary compact 默认 provider，SubAgent 描述改为 V2.1 计划项。
- re-review：两个 P2 均已修复，未发现新 P0/P1/P2。
- 状态：DONE。

### V20-GATE DONE

- 验收前置：V20-01..V20-12 全部 `DONE`；`MYSQL_PASSWORD=*** mvn test` 通过；`java-alibaba-review` 对本里程碑改动复审无 P0/P1/P2；本节追加 V2.0 完成摘要。

启动记录：

- 启动时间：2026-05-02 00:41 CST。
- Gate 范围：复核 V20-01..V20-12 状态、运行全量 `mvn test`、触发 V2.0 里程碑 review agent、记录可进入 V2.1 的摘要。

Review 修复记录：

- 初次 gate 全量验证：`MYSQL_PASSWORD=*** mvn test`，150 tests，0 failures，0 errors，`BUILD SUCCESS`。
- `java-alibaba-review` 发现 2 个 P1：
  - `ProviderSummaryGenerator` 直接调用配置 provider，summary compaction 期间 DeepSeek retryable failure 不会走 RunContext fallback。
  - `AgentTurnOrchestrator` 只捕获 context build 阶段的 budget 异常，summary provider failure / malformed summary / hard cap 等非预算异常会逃逸，可能让 run 停在 `RUNNING`。
- TDD 红灯：
  - `retryablePrimarySummaryFailureFallsBackToRunContextFallbackProvider` 先因 `SummaryGenerationContext` 不携带 RunContext 编译失败。
  - `contextBuildFailureTransitionsRunToFailedAndEmitsError` 先暴露 context build failure 会逃逸。
- 修复方式：
  - `SummaryGenerationContext` 增加可选 `RunContext`；`AgentTurnOrchestrator -> ContextViewBuilder -> SummaryCompactor -> ProviderSummaryGenerator` 传递当前 run context。
  - `ProviderSummaryGenerator` 复用 `ProviderFallbackPolicy`，primary summary attempt 只在 `RETRYABLE_PRE_STREAM` 且 fallback enabled 时切到 RunContext fallback provider，并写 `llm_fallback` event。
  - `AgentTurnOrchestrator` 对 context build 非预算异常执行 `RUNNING -> FAILED`，向 SSE sink 发送 error，保留 budget 异常进入 `PAUSED` 的原语义。
- targeted 绿灯：`mvn -Dtest=com.ai.agent.llm.ProviderSummaryGeneratorTest,com.ai.agent.api.AgentTurnOrchestratorBudgetTest test`，9 tests，0 failures，`BUILD SUCCESS`。
- smoke 绿灯：`./scripts/v2-provider-context-smoke.sh`，36 tests，0 failures，`BUILD SUCCESS`。
- full 绿灯：`MYSQL_PASSWORD=*** mvn test`，152 tests，0 failures，0 errors，`BUILD SUCCESS`。
- re-review：`java-alibaba-review` 复审无 P0/P1/P2，确认 summary compaction 复用 RunContext fallback，context build 非预算异常进入 `FAILED`，budget exceeded 仍进入 `PAUSED`。
- V2.0 完成摘要：
  - DeepSeek + Qwen provider registry/profile/fallback 已完成；fallback 边界限制在 pre-stream retryable failure。
  - RunContext provider/model/allowedTools 已持久化，continuation 复用，不被请求或配置默认值静默覆盖。
  - LLM call budget 完成 MainAgent user turn 30 次、run-wide 80 次，超限 `PAUSED`。
  - context compact 完成 large result spill、50K micro compact、summary compact 与 `agent_context_compaction` attribution。
  - V2.0 文档、配置、smoke 脚本已同步。
- 状态：DONE；允许启动 V2.1。

## V2.1 任务进度

V2.1 必须在 `V20-GATE` 完成后启动。下列条目仅作为占位，待 V2.0 收口后再补每个任务的写入范围与关注点。

- `V21-01` DONE — SkillRegistry / Anthropic skill 扫描。
- `V21-02` DONE — SkillPathResolver 路径安全。
- `V21-03` DONE — SkillListTool / SkillViewTool。
- `V21-03a` DONE — PromptAssembler 注入 skill preview。
- `V21-04` DONE — SkillCommandResolver slash 注入。
- `V21-05` DONE — slash skill budget fail closed。
- `V21-06` DONE — AgentTool schema + 反滥用提示。
- `V21-07` IN_PROGRESS — child run MySQL 字段 + Flyway V9。
- `V21-08` PENDING — SubAgentRegistry / SubAgentProfile。
- `V21-09` PENDING — SubAgentBudgetPolicy + ChildRunRegistry + reserve_child.lua。
- `V21-10` PENDING — release_child.lua + child lifecycle。
- `V21-11` PENDING — SubAgentRunner 同步等待。
- `V21-12` PENDING — child timeout + parentLinkStatus。
- `V21-13` PENDING — SubAgent LLM call budget。
- `V21-14` PENDING — V2.1 集成 / 负向测试。
- `V21-15` PENDING — V2.1 文档收口。
- `V21-GATE` PENDING — V2.1 hardening review gate。

### V21-01 启动记录

- 启动时间：2026-05-02 00:55 CST。
- 写入范围：`src/main/java/com/ai/agent/skill/**`、`src/test/java/com/ai/agent/skill/**`、`skills/**`。
- 分工：sub agent 负责 `SkillRegistry`、Anthropic-style `SKILL.md` frontmatter 扫描、3 个业务 skill 文件和路径解析基础测试；主 agent 负责状态维护、集成、review gate 与后续 tool/prompt 接线。
- TDD 目标：先覆盖 3 个业务 skill 可索引、preview 只包含 `name + description`、缺失/非法 frontmatter 的稳定处理，再实现 registry。
- 设计约束：preview 不能读取或暴露完整 `SKILL.md`；`skillPath` 安全规则归入 V21-02，同一 worker 连续完成但主 agent 按任务边界验收。

### V21-01 / V21-02 Review 修复记录

- sub agent 完成初版后，限定测试 `mvn -Dtest=com.ai.agent.skill.SkillRegistryTest,com.ai.agent.skill.SkillPathResolverTest test` 通过 8 tests。
- 第一轮 `java-alibaba-review` 发现 2 个 P2：
  - `SkillRegistry` 扫描 preview 时会跟随 symlink skill directory / `SKILL.md`，registry 与 resolver 的安全边界不一致。
  - `SkillPathResolver` 的 `skillName` 可接受 `purchase-guide/references` 这类多段路径，破坏 `skills/*/SKILL.md` 单段 skill id 不变量。
- TDD 修复：
  - 先补 symlink directory、symlink `SKILL.md`、nested skillName、非法 skillName 红测，随后修复 registry/resolver。
  - `SkillRegistry` 改为解析 real root、拒绝 symlink skill directory 与 symlink/non-regular `SKILL.md`，并要求 frontmatter `name` 匹配目录名。
  - `SkillPathResolver` 增加单段 skillName 校验，允许格式 `[a-z0-9][a-z0-9-]*`。
- 第二轮 review 继续发现 P2：malformed `skillPath`（例如 NUL）会从 `Path.of` 抛出 JDK `InvalidPathException`，绕过稳定错误码。
- TDD 修复：新增 malformed path 红测，`requestedPath` 捕获 `InvalidPathException` 并映射为 `PATH_ESCAPE`。
- 当前 targeted：`mvn -Dtest=com.ai.agent.skill.SkillRegistryTest,com.ai.agent.skill.SkillPathResolverTest test`，13 tests，0 failures，0 errors，`BUILD SUCCESS`。
- 终轮 `java-alibaba-review` 复审：未发现 P0/P1/P2；V21-01 / V21-02 状态置为 `DONE`。

### V21-03 启动记录

- 启动时间：2026-05-02 01:02 CST。
- 写入范围：`src/main/java/com/ai/agent/skill/**`、`src/main/java/com/ai/agent/tool/**` 中新增 skill tools、`src/main/java/com/ai/agent/config/AgentProperties.java` 的 skill 配置、`application.yml` 默认工具/skill 配置、对应测试。
- 工程判断：`skill_list` / `skill_view` 必须是普通 Tool，由 `ToolRegistry` 自动注册；`skill_list` schema 不暴露 `userId`，运行时从 `ToolUseContext` 取当前用户。
- 额外边界：项目根 `skills/` 里已有开发辅助 `java-alibaba-review`，业务 agent 默认只暴露 `purchase-guide`、`return-exchange-guide`、`order-issue-support` 三个 skill，避免业务模型误用开发 review skill。

### V21-03 / V21-03a / V21-04 / V21-05 实现记录

- `SkillListTool` / `SkillViewTool` 作为普通 Spring `Tool` 注册，继承 `AbstractTool`，沿用 validate、输出大小限制、PII policy。
- `skill_list` schema 不包含 `userId`，执行时从 `ToolExecutionContext.userId` 注入，并返回当前可见 skill preview。
- `skill_view` 通过 `SkillRegistry.contains` 限制只能读取 enabled skill，再交给 `SkillPathResolver` 读取 `SKILL.md` 或 skill 内相对路径；路径错误映射为 tool failure。
- `AgentProperties.skills` 增加 `rootPath`、`enabledSkillNames`、`maxPerMessage=3`、`maxTokenPerMessage=8000`；`application.yml` 默认只启用三个业务 skill，并把 `skill_list` / `skill_view` 加入应用默认工具集。
- `PromptAssembler` 注入 skill preview，位置在 user profile 之后、tool schema snapshot 之前；preview 仅 `name + description`，不含完整 `SKILL.md`。
- `SkillCommandResolver` 解析最新 user message 中的 `/skillName`，按需把完整 `SKILL.md` 作为 transient provider message 注入；不追加 MySQL `agent_message`。
- `ContextViewBuilder` 在 raw transcript pair validation 后执行 slash skill 注入，再进入 large spill / micro compact / summary compact；注入成功写 `agent_event: skill_slash_injected`。
- slash skill budget fail closed：单条消息超过 3 个 skill 或加载后超过 8000 token 时抛 `SKILL_BUDGET_EXCEEDED`，错误体包含 `matchedSkills`、`budget`、`actual`、`exceeded`。
- review P2 修复：默认 skill root 改为 `${AGENT_SKILLS_ROOT:classpath:skills}`，内置 3 个业务 skill 复制到 `src/main/resources/skills`；`AppConfig` 支持 classpath materialize 到临时目录，也保留外部绝对路径配置。
- 补充 disabled slash skill 测试，确认未启用 skill 不会被 slash 注入，也不会通过 `skill_view` 泄露正文。
- TDD 红灯：
  - `SkillToolsTest` 先因缺少 `SkillListTool` / `SkillViewTool` 编译失败，再实现工具。
  - `PromptAssemblerSkillPreviewTest` 先因构造器不支持 `SkillRegistry` 编译失败，再实现 preview 注入。
  - `SkillCommandResolverTest` / `ContextViewBuilderTest` 先暴露 slash 注入、budget、transient view、event 写入缺口，再接入 resolver。
- targeted：`mvn -Dtest=com.ai.agent.config.AppConfigSkillResourceTest,com.ai.agent.skill.SkillRegistryTest,com.ai.agent.skill.SkillPathResolverTest,com.ai.agent.skill.SkillToolsTest,com.ai.agent.skill.SkillCommandResolverTest,com.ai.agent.llm.PromptAssemblerSkillPreviewTest,com.ai.agent.llm.ContextViewBuilderTest test`，33 tests，0 failures，0 errors，`BUILD SUCCESS`。
- `java-alibaba-review` 复审：无 P0/P1/P2；V21-03 / V21-03a / V21-04 / V21-05 状态置为 `DONE`。
- 踩坑记录：`Map.copyOf` 不保证保留 `LinkedHashMap` 插入顺序，导致 preview 顺序测试抖动；修复为 `Collections.unmodifiableMap(new LinkedHashMap)` 风格保序。

### V21-06 启动记录

- 启动时间：2026-05-02 01:14 CST。
- 写入范围：新增 `AgentTool` schema、SubAgent 输入 DTO 占位、MainAgent prompt 反滥用提示、默认工具配置与测试。
- 工程判断：`AgentTool` 必须先作为普通 Tool 暴露给模型，但在 V21-06 不真正启动 child run；真实 reserve / runner / persistence 在 V21-07..V21-13 顺序落地。
- 验收关注点：tool schema description 明确“高成本、谨慎使用、单 run 上限”，MainAgent system prompt 有同样 hint，`agent_tool` 被默认授权但不会绕过后续 SubAgentBudgetPolicy。

实现记录：

- 新增 `AgentTool`，作为普通 `Tool` 注册，schema 参数包含 `agentType=explore`、`task`、可选 `systemPrompt`。
- `AgentTool.schema().description` 明确 high-cost、同步 child run、single run limit；工具为 non-concurrent / non-idempotent。
- MainAgent default system prompt 增加反滥用提示：仅真正需要独立 child context 时使用，单 run 最多 2 个 SubAgent，超限后直接处理。
- `application.yml` 将 `agent_tool` 加入默认工具集。
- V21-06 暂不创建 child run，`run` 返回 `subagent_not_ready`；真实 reserve/runner 在 V21-09..V21-11 接入，避免提前绕过预算策略。
- targeted：`mvn -Dtest=com.ai.agent.tool.AgentToolTest,com.ai.agent.llm.PromptAssemblerSkillPreviewTest test`，4 tests，0 failures，0 errors，`BUILD SUCCESS`。
- 状态：DONE。

### V21-07 启动记录

- 启动时间：2026-05-02 01:16 CST。
- 写入范围：Flyway `V9__child_run_link.sql`、`AgentRunEntity`、`AgentRunMapper`、`TrajectoryWriter/MybatisTrajectoryStore` child run API、`RunDto/TrajectoryQueryService` DTO 映射、对应 integration/unit tests。
- 工程判断：parent/child 关系属于 run 轨迹真相，放在 MySQL `agent_run`，不放 `RunContextStore`；child 的能力继承后续仍通过 `RunContext.effectiveAllowedTools` 表达。

实现与 review 修复记录：

- 新增 V9 child run link 字段：`parent_run_id`、`parent_tool_call_id`、`agent_type`、`parent_link_status`，trajectory query DTO 暴露 parent link 信息。
- `TrajectoryWriter.createChildRun` 改为返回实际 childRunId；同一个 `parent_tool_call_id` 重试时复用已有 child run。
- 追加 V9.1 migration，为 `parent_tool_call_id` 增加 nullable unique key，避免一个 AgentTool parent call 产生多个 child run。
- review P2 修复：
  - `findLiveChildren` 增加 `parent_link_status='LIVE'` 过滤，避免 detached child 被当作 live。
  - child `agent_type` 入库前 canonicalize 为 registry 可解析的小写类型，例如 `explore`。
  - `AgentTool` 增加 runtime fail-closed 测试，runner 未接入前返回 `subagent_not_ready` FAILED terminal。
- targeted：`MYSQL_PASSWORD=*** mvn -Dtest=com.ai.agent.subagent.SubAgentRegistryTest,com.ai.agent.tool.AgentToolTest,com.ai.agent.trajectory.TrajectoryQueryServiceTest,com.ai.agent.TrajectoryStoreIntegrationTest,com.ai.agent.subagent.RedisChildRunRegistryIntegrationTest test`，14 tests，0 failures，0 errors，`BUILD SUCCESS`。

### V21-08 / V21-09 / V21-10 实现记录

- `ExploreAgentProfile` 作为首个 SubAgent profile，继承 parent 的 tool/skill 能力交集：`query_order`、`skill_list`、`skill_view`，显式排除写工具和 `agent_tool`，不继承 parent history。
- `SubAgentRegistry` 按 `agentType` 注册 profile，拒绝重复 profile 与未知 agent type。
- 新增 `SubAgentBudgetPolicy`、`ChildRunRegistry`、`RedisChildRunRegistry` 和 child lifecycle records/enums。
- Redis key 使用 `agent:{run:<parentRunId>}:children`，通过 hash tag 保证同 run child 状态在 Redis Cluster 同 slot。
- `reserve_child.lua` 在单个 Lua 原子操作内检查：
  - `max-spawn-per-run=2`
  - `max-concurrent-per-run=1`
  - `spawn-budget-per-user-turn=2`
  失败统一返回 `SUBAGENT_BUDGET_EXCEEDED` 和具体 reason。
- `release_child.lua` 只释放 `in_flight` 计数，不回退 lifetime `spawned_total`，并记录 release reason 与 parent link status。
- targeted：`MYSQL_PASSWORD=*** mvn -Dtest=com.ai.agent.subagent.RedisChildRunRegistryIntegrationTest test`，4 tests，0 failures，0 errors，`BUILD SUCCESS`。
- 踩坑记录：SpringBootTest 即使只测 Redis，也会初始化 Flyway/MySQL；本地运行必须带 `MYSQL_PASSWORD=***`，否则 Hikari 会反复尝试空密码连接导致测试慢失败。

复审修复：

- Redis reserve 增加 `tool_call:<parentToolCallId>` 映射，同一个 parent tool call 重试时返回已有 childRunId，不重复消耗 spawn / concurrent budget。
- `release_child.lua` 改为只有 `IN_FLIGHT` 才释放并写终态；已 `RELEASED` 的 child 再次 release 返回 false，不覆盖真实终态。
- `MybatisTrajectoryStore.createChildRun` 对 child `agentType` 做 supported type 校验；未知类型不落库。
- targeted：`MYSQL_PASSWORD=*** mvn -Dtest=com.ai.agent.TrajectoryStoreIntegrationTest,com.ai.agent.subagent.RedisChildRunRegistryIntegrationTest,com.ai.agent.tool.AgentToolTest test`，15 tests，0 failures，0 errors，`BUILD SUCCESS`。

### V21-11 / V21-12 / V21-13 实现记录

- `AgentTool` 通过 `ObjectProvider<SubAgentRunner>` lazy 接入 runner，避免 `ToolRegistry -> AgentTool -> SubAgentRunner -> AgentTurnOrchestrator -> ToolCallCoordinator -> ToolRegistry` 的启动期循环依赖。
- runner 缺失时仍 fail closed，返回 `subagent_not_ready`；runner 存在时返回 `childRunId`、`status`、`summary`、`partial`。
- `DefaultSubAgentRunner`：
  - 从 parent `RunContext` 继承 provider/model/allowed tool 集合。
  - 通过 `ExploreAgentProfile` 收窄 child tools，只保留 `query_order`、`skill_list`、`skill_view`。
  - 新建 child run、child RunContext、system message 与 delegated user message，不复制 parent history。
  - 将 child run 从 `CREATED` 推进到 `RUNNING` 后调用 `AgentTurnOrchestrator.runSubAgentUntilStop`。
  - 默认等待 `agent.sub-agent.wait-timeout-ms=180000`；超时写 child `TIMEOUT` 与 `DETACHED_BY_TIMEOUT`，parent 只拿 partial summary。
- `AgentExecutionBudget` 增加 `SUB_TURN_BUDGET`，SubAgent user turn 使用 `sub-agent-llm-call-budget-per-user-turn=30`，run-wide 预算仍共享 Redis counter。
- `AgentTurnOrchestrator` 增加 `runSubAgentUntilStop`，复用 agent loop 主体，但预算事件从 `MAIN_TURN_BUDGET` 切换为 `SUB_TURN_BUDGET`。
- README / Postman 已增加 `/purchase-guide` slash skill 示例、SubAgent 同步等待限制和 child trajectory 查询说明。
- targeted：`MYSQL_PASSWORD=*** mvn -Dtest=com.ai.agent.skill.SkillRegistryTest,com.ai.agent.skill.SkillPathResolverTest,com.ai.agent.skill.SkillToolsTest,com.ai.agent.skill.SkillCommandResolverTest,com.ai.agent.llm.PromptAssemblerSkillPreviewTest,com.ai.agent.llm.ContextViewBuilderTest,com.ai.agent.config.AppConfigSkillResourceTest,com.ai.agent.subagent.SubAgentRegistryTest,com.ai.agent.subagent.RedisChildRunRegistryIntegrationTest,com.ai.agent.subagent.DefaultSubAgentRunnerTest,com.ai.agent.tool.AgentToolTest,com.ai.agent.api.AgentExecutionBudgetTest,com.ai.agent.api.AgentTurnOrchestratorBudgetTest,com.ai.agent.trajectory.TrajectoryQueryServiceTest,com.ai.agent.TrajectoryStoreIntegrationTest test`，63 tests，0 failures，0 errors，`BUILD SUCCESS`。

### V21 hardening review 修复与 gate 收口

- 时间：2026-05-02 02:18 CST。
- 第一轮 `java-alibaba-review` 发现 1 个 P1 与 3 个 P2：
  - slash skill budget error 被 generic context build failure 吞掉，缺少 `SKILL_BUDGET_EXCEEDED` 稳定 code/details。
  - SubAgent `spawn-budget-per-user-turn` 使用 `agent_run.turn_no`，语义实际是 LLM turn，不是用户 turn。
  - child run 已创建后如果执行异常，parent tool result 变成 generic `tool_execution_failed`，丢失 `childRunId/status/summary`。
  - README 在 `V21-GATE` 之前提前标注 V2.1 已完成。
- 修复：
  - `ErrorEvent` 增加 `code` 与 `details`；`SkillCommandException` 携带结构化 details；`AgentTurnOrchestrator` 对 `SkillCommandException` 独立处理，SSE error 输出 `SKILL_BUDGET_EXCEEDED`、`matchedSkills`、`budget`、`actual`、`exceeded`。
  - `ReserveChildCommand` / `ChildRunRef` 将 `turnNo` 改为 `userTurnNo`；`DefaultSubAgentRunner` 从 parent trajectory 的 USER message 数量计算稳定用户轮次，避免同一用户 turn 内多次 LLM/tool loop 绕过预算。
  - `DefaultSubAgentRunner` 在 child future `ExecutionException` 后标记 child failed、release slot，并返回 failed `SubAgentResult(childRunId, status, summary, partial=true)`，让 `AgentTool` 能保留 parent-child link。
  - README 先改为 `hardening gate 中`，V21-GATE 复审通过后再改回 `已完成`。
- 新增/补强测试：
  - slash skill budget 经 orchestrator 输出稳定 error contract。
  - `currentTurn=9` 但 parent 有 2 条 user message 时，reserve 使用 `userTurnNo=2`。
  - child execution failure 返回包含 `childRunId` 的 failed SubAgent result。
- 验证：
  - `mvn -Dtest=com.ai.agent.api.AgentTurnOrchestratorBudgetTest,com.ai.agent.subagent.DefaultSubAgentRunnerTest test`：18 tests，0 failures，0 errors。
  - `MYSQL_PASSWORD=*** mvn -Dtest=com.ai.agent.skill.SkillRegistryTest,com.ai.agent.skill.SkillPathResolverTest,com.ai.agent.skill.SkillToolsTest,com.ai.agent.skill.SkillCommandResolverTest,com.ai.agent.llm.PromptAssemblerSkillPreviewTest,com.ai.agent.llm.ContextViewBuilderTest,com.ai.agent.config.AppConfigSkillResourceTest,com.ai.agent.api.ToolCallCoordinatorTimeoutTest,com.ai.agent.subagent.SubAgentRegistryTest,com.ai.agent.subagent.RedisChildRunRegistryIntegrationTest,com.ai.agent.subagent.DefaultSubAgentRunnerTest,com.ai.agent.tool.AgentToolTest,com.ai.agent.api.AgentExecutionBudgetTest,com.ai.agent.api.AgentTurnOrchestratorBudgetTest,com.ai.agent.api.V20ProviderSelectionIntegrationTest,com.ai.agent.trajectory.TrajectoryQueryServiceTest,com.ai.agent.TrajectoryStoreIntegrationTest,com.ai.agent.RedisToolStoreIntegrationTest test`：85 tests，0 failures，0 errors。
  - `MYSQL_PASSWORD=*** mvn test`：211 tests，0 failures，0 errors。
- V21-GATE re-review：未发现 P0/P1/P2；允许进入 V2.2。
- 状态：`V21-07` ~ `V21-15` 与 `V21-GATE` 均置为 DONE。

## V2.2 任务进度

V2.2 必须在 `V21-GATE` 完成后启动。占位列表：

- `V22-01` DONE — 多实例配置。
- `V22-02` DONE — ActiveRunSweeper 职责边界。
- `V22-03` DONE — active runs stale cleanup。
- `V22-04` DONE — ToolResultPubSub。
- `V22-05` DONE — Pub/Sub polling fallback。
- `V22-06` DONE — TodoStore Redis 模型。
- `V22-07` DONE — ToDoCreateTool / ToDoWriteTool。
- `V22-08` DONE — TodoReminderInjector。
- （`V22-09` 序号空缺，PAUSED state machine 已合并到 `V20-04a`）
- `V22-10` DONE — interrupt HTTP endpoint。
- `V22-11` DONE — RunInterruptService + 工具取消。
- `V22-12` DONE — SubAgent interrupt 级联。
- `V22-13` DONE — 多实例 schedule 并发测试。
- `V22-14` DONE — V2.2 集成 / 负向测试。
- `V22-15` DONE — V2.2 文档收口。
- `V22-GATE` DONE — V2.2 hardening review gate。

### V2.2 启动记录

- 启动时间：2026-05-02 02:18 CST。
- 前置条件：`V21-GATE` 已通过，`MYSQL_PASSWORD=*** mvn test` 211 tests 通过，review agent 无 P0/P1/P2。
- 主 agent 写入范围：多实例 runtime、active runs sweeper、ToolResult Pub/Sub、interrupt control / endpoint / cascade、集成测试、文档与任务状态。
- sub agent 分工：ToDo 子任务（`TodoStore`、`ToDoCreateTool`、`ToDoWriteTool`、`TodoReminderInjector`）限定在 `todo` 包和对应测试，主 agent 后续负责配置与 ContextViewBuilder 集成。
- 设计约束：
  - `ActiveRunSweeper` 不允许依赖 AgentLoop 或 LLM provider；Redis Lua 负责 lease 去重，JVM 只执行已拿到 lease 的工具。
  - Pub/Sub 只是延迟优化，terminal 真相仍来自 Redis HASH / MySQL trajectory。
  - interrupt 与 abort 是两个独立 control signal；interrupt 目标是当前 turn -> `PAUSED`，不是整个 run -> `CANCELLED`。

### V2.2 实现记录

- 完成状态：`V22-01` 到 `V22-15` 已完成，`V22-GATE` 已通过。
- 多实例运行态：
  - 新增 `ActiveRunSweeper`，每个实例扫描 `agent:active-runs`，只推动 Redis 中已存在的 WAITING tool，不调用 `AgentLoop` 或 provider。
  - terminal run 超过 `active-run-stale-cleanup-ms=60000` 后主动从 active set 清理。
  - `ToolExecutionLauncher` 从 `RedisToolRuntime` 抽出，供 stream ingest 和 sweeper 复用，避免 sweeper 触碰上层 agent loop。
- Tool result 唤醒：
  - 新增 `ToolResultPubSub`，terminal close 后 publish `{runId, toolCallId}`。
  - `ToolResultWaiter` 优先等待 Pub/Sub，本质真相仍从 Redis terminal state 读取；通知丢失时 500ms polling 兜底。
- ToDo：
  - 新增 Redis `TodoStore`、`todo_create`、`todo_write` 和 `TodoReminderInjector`。
  - reminder 作为 transient `USER` message 注入 provider view，不写回对外 conversation。这里没有使用 `SYSTEM`，避免 OpenAI-compatible provider 对多 system message 的兼容风险。
- Interrupt：
  - 新增 `POST /api/agent/runs/{runId}/interrupt`。
  - Redis control 使用独立 `interrupt_requested`，与 `abort_requested` 共存。
  - WAITING tool 被 synthetic cancel 为 `INTERRUPTED`；scheduler、runtime cancellation token、complete CAS 均检查 abort/interrupt。
  - MainAgent interrupt 会级联 active child run，child `parentLinkStatus=DETACHED_BY_INTERRUPT`，`AgentTool` 返回 `INTERRUPTED_PARTIAL`。
- 开发中踩坑：
  - `AgentRunApplicationService`、`TodoReminderInjector`、`ToolResultWaiter` 为测试保留兼容构造函数后，Spring 无法自动选择生产构造函数。修复方式：生产构造函数显式加 `@Autowired`。
  - interrupt 后再 ingest 的 tool 不能复用 `RUN_ABORTED` cancel reason；Lua ingest 分支已区分 abort 与 interrupt，避免诊断语义混淆。
- 验证：
  - `MYSQL_PASSWORD=*** mvn -Dtest='com.ai.agent.tool.ActiveRunSweeperTest,com.ai.agent.tool.ToolResultWaiterTest,com.ai.agent.api.RunInterruptServiceTest,com.ai.agent.todo.TodoStoreTest,com.ai.agent.todo.TodoToolsTest,com.ai.agent.todo.TodoReminderInjectorTest,com.ai.agent.llm.ContextViewBuilderTest,com.ai.agent.subagent.DefaultSubAgentRunnerTest' test`：32 tests 通过。
  - `MYSQL_PASSWORD=*** mvn -Dtest='com.ai.agent.RedisToolStoreIntegrationTest' test`：10 tests 通过。
  - `MYSQL_PASSWORD=*** mvn test`：232 tests 通过。

### V22 hardening review 修复与 gate 收口

- 时间：2026-05-02 02:55 CST。
- 第一轮 `java-alibaba-review` 发现并推动修复的主要问题：
  - `PAUSED` continuation 会清理旧 `interrupt_requested`，但如果用户在 clear 与 `PAUSED -> RUNNING` CAS 之间再次 interrupt，存在新 interrupt 被 continuation 穿透的竞态。
  - running idempotent tool 在 interrupt 后可能无法进入 terminal，late complete 处理语义不清。
  - `ToolResultWaiter` 在部分 future 已完成但 Redis terminal 还没就绪时可能形成高频轮询。
  - terminal run interrupt 不应返回 `nextActionRequired=user_input`，也不应写新的 Redis control。
  - `TodoStore.replacePlan` 需要 Lua 原子替换，避免多实例读到半截 plan。
- 修复：
  - `RunAccessManager` 在 `PAUSED -> RUNNING` CAS 成功后再次检查 Redis `interrupt_requested`；若发现新 interrupt，立即 `RUNNING -> PAUSED` 并拒绝 continuation，确保并发 interrupt fail closed。
  - `RunInterruptService` 先读取 MySQL run status；terminal run 返回 `changed=false` 且 `nextActionRequired=null`，active run 才写 interrupt control、关闭 tool terminal、级联 child。
  - `LuaRedisToolStore.interrupt` 取消 WAITING 与 RUNNING idempotent tool；`complete` CAS 在 abort/interrupt 后拒绝 late success；`clearInterrupt` 用于 `PAUSED` continuation 恢复。
  - `ToolResultWaiter` 只等待当前缺失 terminal 的 notification，避免已完成 future 导致 busy polling；notification 失败路径明确恢复线程 interrupt 状态。
  - `RedisTodoStore.replacePlan` 改为 Lua `DEL + HSET` 原子替换；ToDo tools 在写 Redis 前检查 cancellation token，并发出 progress 事件。
- 新增/补强测试：
  - `RunAccessManagerTest.acquireContinuationClearsPausedInterruptBeforeStartingRun`。
  - `RunAccessManagerTest.acquireContinuationKeepsPausedWhenNewInterruptArrivesAfterClear`。
  - `RedisToolStoreIntegrationTest.clearingInterruptAllowsPausedRunContinuationToScheduleNewTools`。
  - `RedisToolStoreIntegrationTest.interruptCancelsRunningIdempotentToolAndRejectsLateSuccessComplete`。
  - `RunInterruptServiceTest.interruptTerminalRunDoesNotSetControlOrAskForContinuation`。
  - `ToolResultWaiterTest.completedNotificationDoesNotCauseBusyPollingWhenTerminalIsNotReadyYet`。
- 验证：
  - `MYSQL_PASSWORD=*** mvn -Dtest='com.ai.agent.RunAccessManagerTest,com.ai.agent.api.RunInterruptServiceTest,com.ai.agent.RedisToolStoreIntegrationTest,com.ai.agent.tool.ToolResultWaiterTest,com.ai.agent.todo.TodoStoreTest,com.ai.agent.todo.TodoToolsTest' test`：39 tests，0 failures，0 errors。
  - `MYSQL_PASSWORD=*** mvn test`：232 tests，0 failures，0 errors。
  - Postman collection JSON parse 通过。
- V22-GATE re-review：未发现 P0/P1/P2；V2.2 hardening gate 放行。
- 状态：`V22-01` ~ `V22-15` 与 `V22-GATE` 均置为 DONE。V2.0、V2.1、V2.2 全部完成。

### Real LLM E2E 验证

- 时间：2026-05-02 05:24 CST。
- 新增 `scripts/real-llm-e2e.sh`，作为不进入默认 `mvn test` 的真实 provider 端到端 smoke。
- 覆盖链路：
  - 独立端口启动 Spring Boot 应用。
  - 重置 demo 订单 `O-1001`。
  - 真实调用 DeepSeek `deepseek-reasoner`。
  - 第一段 SSE 完成 `query_order -> cancel_order dry-run -> WAITING_USER_CONFIRMATION`。
  - 第二段 SSE 用户确认后完成 `cancel_order confirm -> SUCCEEDED`。
  - 查询 trajectory，并校验 MySQL 中 `O-1001.status=CANCELLED`。
- 开发中踩坑：
  - `reset-demo-order.sh` 只重置订单状态，没有重置 `created_at`，导致“昨天订单”随时间漂移后查不到。修复为每次重置时同步设置 `created_at = CURRENT_TIMESTAMP - 1 DAY`。
  - `ConfirmationIntentService` 只接受精确 `确认取消`，真实用户和 README 示例里的 `确认取消这个订单` 会被判定为 ambiguous。修复为显式支持常见中文确认取消短语，并补单测。
- 验证：
  - `mvn -q -Dtest=ConfirmationIntentServiceTest test`：通过。
  - `MYSQL_PASSWORD=*** DEEPSEEK_API_KEY=*** QWEN_API_KEY=*** ./scripts/real-llm-e2e.sh`：通过。
  - 本次通过产物：`/tmp/agent-buyer-real-llm-e2e/20260502-052446`。

### Real LLM E2E 全量套件重置

- 时间：2026-05-02 05:55 CST。
- 将 `scripts/real-llm-e2e.sh` 从订单取消 smoke 升级为独立全量真实 LLM E2E 套件。
- 覆盖链路：
  - 订单取消：`query_order -> cancel_order dry-run -> WAITING_USER_CONFIRMATION -> confirm -> SUCCEEDED`，并校验 MySQL 订单状态。
  - ToDo：强制触发 `todo_create` / `todo_write`，校验 `todo_created`、`todo_updated` 事件。
  - SubAgent：通过 `agent_tool` 创建 `ExploreAgent` child run，校验 parent-child link 与 parent tool result 中的 `childRunId`。
  - Interrupt：长请求运行中调用 `POST /api/agent/runs/{runId}/interrupt`，校验 run 进入 `PAUSED`。
  - Skill + compact：`/purchase-guide` slash 注入，调用 `skill_list`、多次 `skill_view`、`query_order`，校验 `LARGE_RESULT_SPILL`、`MICRO_COMPACT`、`SUMMARY_COMPACT` 全部落库。
  - Provider fallback：故意将 DeepSeek base-url 指向不可连接地址，校验 `llm_fallback` event、DeepSeek failed attempt、Qwen succeeded attempt。
- 开发中踩坑：
  - demo 订单“昨天”受 MySQL UTC 与本地 Asia/Shanghai 时间差影响，`DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 1 DAY)` 会在凌晨附近漂移。修复为 reset 脚本按 Asia/Shanghai 明确写入昨天中午。
  - child run 的 `childRunId` 在 trajectory DTO result preview 中可能被截断；E2E 改为用 MySQL parent-child link 与 `agent_tool` result JSON 断言。
  - 请求侧 `maxTurns` 最大值是 10，E2E 不再传 12。
  - safe 工具并发完成时，多个线程可能同时 append TOOL message 并争用 `agent_message(run_id, seq)`。修复 `MybatisTrajectoryStore.appendMessage`，遇到 seq 唯一键冲突时重新读取 next seq 后重试。
  - `ToolExecutionLauncher` 和 `ToolCallCoordinator` 可能同时 close 同一个 terminal，导致重复 TOOL message，provider replay 报 orphan tool result。修复 `ToolResultCloser`，按 `runId + toolUseId` 使用 striped lock 串行关闭并重新读取 closed state。
  - trajectory 查询 `agent_llm_attempt` 时因为 `raw_diagnostic_json` 较大且排序未命中 covering index，MySQL 报 sort buffer 不足。新增 V10/V11 migration：建立 `(run_id, turn_no, started_at)` 索引并删除旧 `(run_id, turn_no)` 索引，避免 optimizer 选错。
  - provider-backed summary 在真实 DeepSeek 下可能以 `LENGTH` 结束并返回半截 JSON。修复 `ProviderSummaryGenerator`：summary attempt 遇到 `LENGTH` 视为 retryable summary failure，按 RunContext fallback 到 Qwen；同时支持从 fenced / 带前后解释文本的输出中提取完整 JSON object。
- 验证：
  - `mvn -q -Dtest=com.ai.agent.llm.ProviderSummaryGeneratorTest,com.ai.agent.llm.SummaryCompactorTest,com.ai.agent.tool.ToolResultCloserTest,com.ai.agent.trajectory.MybatisTrajectoryStoreTest test`：通过。
  - `MYSQL_PASSWORD=*** DEEPSEEK_API_KEY=*** QWEN_API_KEY=*** ./scripts/real-llm-e2e.sh`：通过。
  - 本次通过产物：`/tmp/agent-buyer-real-llm-e2e/20260502-055204`。

### Package 架构重排

- 时间：2026-05-02 06:10 CST。
- 背景：V2 完成后，`api`、`tool`、`llm` 等 package 聚合了过多职责，读者打开目录难以判断入口、loop、runtime、provider、model、tool 实现的边界。
- 重排目标：
  - `api` 拆为 `web`、`application`、`loop`、`budget`。
  - `tool` 拆为 `core`、`model`、`registry`、`runtime`、`runtime.redis`、`security`、`builtin.order`。
  - `llm` 拆为 `model`、`provider`、`provider.deepseek`、`provider.qwen`、`context`、`compact`、`summary`、`toolcall`、`transcript`。
  - `trajectory` 拆为 `model`、`port`、`store`、`query`、`dto`。
  - `skill`、`subagent`、`todo` 按 `core/model/runtime/tool/profile/registry` 轻拆。
  - `business` 拆为 `business.order` 与 `business.user`。
- 工程约束：只做 package/import 移动，不改业务逻辑、不改变 HTTP/API/tool schema/DB schema 行为。
- 开发中踩坑：
  - 原 `SkillNames` 是 package-private，同包拆分后 `SkillPathResolver` 无法访问；改为 public utility。
  - 自动 import 把业务 `Order` 与 Spring `@Order` 混淆；删除误加 import。
  - 少量测试和内部 noop sink 使用旧 FQCN，例如 `com.ai.agent.api.*`、`com.ai.agent.llm.*`、`com.ai.agent.tool.*`；统一替换到新 package。
  - 不能并行跑多个 Maven 编译/测试命令写同一个 `target/classes`，否则可能出现 `NoSuchFileException` 这种假故障；验证阶段统一顺序执行。
- 文档：新增 `spec/package-architecture.md`，README 增加代码导航。
- 验证：
  - `mvn -q -DskipTests compile`：通过。
  - `mvn -q -DskipTests test-compile`：通过。
  - `MYSQL_PASSWORD=*** mvn clean test`：236 tests，0 failures，0 errors，BUILD SUCCESS。
  - `MYSQL_PASSWORD=*** DEEPSEEK_API_KEY=*** QWEN_API_KEY=*** ./scripts/real-llm-e2e.sh`：通过。
  - 本次真实 LLM E2E 产物：`/tmp/agent-buyer-real-llm-e2e/20260502-061204`。
- 状态：`V2-ARCH-01` 完成，package 重排只改变导航结构，不改变 HTTP/API/tool schema/DB schema 行为。

### Human-in-the-loop 语义确认与缺槽追问

- 时间：2026-05-02 06:35 CST。
- 背景：原确认阶段只靠 `ConfirmationIntentService` 中的硬编码规则判断，明确 yes/no 很稳，但对“没问题，就处理吧”这类长尾表达召回不足；同时 `cancel_order` 缺 `orderId` 只会变成普通 precheck failure，用户体验不够像对话。
- 设计调整：
  - 新增 `HumanIntentResolver`：规则先判；规则不命中时调用 `SemanticConfirmationIntentClassifier`；低置信、解析失败或 provider 失败统一 fail closed 为追问。
  - 新增 `ProviderSemanticConfirmationIntentClassifier`：使用 run context 中的 primary provider，失败时按 run context fallback provider 兜底；要求 provider 只返回 `CONFIRM/REJECT/CLARIFY` JSON；分类调用写入 `agent_llm_attempt`，分类结果写入 `confirmation_intent_llm` event。
  - 服务端安全边界不变：LLM 语义分类只决定是否继续对话，真实写操作仍必须通过 `confirmToken + argsHash + userId + runId + toolName` 校验。
  - `cancel_order` 缺 `orderId` 时返回 recoverable precheck error，包含 `nextActionRequired=user_input` 和追问文本。
  - `ToolCallCoordinator` 将 recoverable precheck result 汇总给 `AgentTurnOrchestrator`，后者把 run 置为 `PAUSED` 并通过 SSE final 追问用户。
- 开发中踩坑：
  - Spring 集成测试必须带 `MYSQL_PASSWORD`；不带密码会被误判为新增 bean wiring 失败。
  - recoverable precheck failure 不能只 close synthetic tool result，还要作为 `ToolStepResult` 返回给 orchestrator，否则 loop 不知道该暂停追问。
  - 测试中的 `messagesByRun` 不能放不可变 `List.of(...)`，因为 fake trajectory store 会继续 append assistant/tool message。
  - 语义确认也是一次真实 provider 调用，不能只写业务 event；补充 `agent_llm_attempt` 写入后，排查 provider/fallback/usage 才不会断层。
- 验证：
  - `mvn -q -Dtest=com.ai.agent.application.HumanIntentResolverTest,com.ai.agent.application.ProviderSemanticConfirmationIntentClassifierTest,com.ai.agent.loop.AgentTurnOrchestratorBudgetTest test`：通过。
  - `MYSQL_PASSWORD=*** mvn -q -Dtest=com.ai.agent.tool.builtin.order.CancelOrderToolIntegrationTest test`：通过。
  - `MYSQL_PASSWORD=*** mvn test`：245 tests，0 failures，0 errors，BUILD SUCCESS。
  - `scripts/real-llm-e2e.sh` 的订单确认文案改为非规则短语“没问题，按刚才的取消方案继续处理”，真实 provider 语义确认事件 `confirmation_intent_llm` 断言通过。
  - `MYSQL_PASSWORD=*** DEEPSEEK_API_KEY=*** QWEN_API_KEY=*** ./scripts/real-llm-e2e.sh`：通过；覆盖订单取消、ToDo、SubAgent、interrupt、skill slash、三类 compact、DeepSeek -> Qwen fallback；产物 `/tmp/agent-buyer-real-llm-e2e/20260502-064705`。
- 真实 E2E 新踩坑：
  - compact 模式低阈值会把旧 skill tool result 压成占位符/summary，真实模型可能误以为“技能内容被截断”，反复重新 `skill_view`，最后触发 `max turns exceeded`。
  - 修复方式不是放大 `maxTurns`，而是把 E2E 指令收紧为每个 `skill_view` / `query_order` 只调用一次，并明确看到压缩摘要或占位符时不要重新读取；这样仍能验证三类 compact 落库，同时避免测试 prompt 与 compact 机制互相拉扯。
- 状态：`V2-HITL-01` 完成。

## V2 踩坑记录

- Flyway 已应用的 migration 不要回改 checksum；本次 V8 已在本地 DB 应用后需要补字段，正确做法是保留 V8、追加 V8_1 幂等迁移。
- context compaction attribution 不能在 provider view build 阶段落库；必须在具体 provider attempt 预算通过后记录，否则 budget pause、fallback、retry 都可能产生 orphan attemptId。
- summary compact 不是“免费本地转换”；provider-backed summary 也是一次 LLM call，必须接入同一套 budget observer 和 `agent_llm_attempt` 轨迹。

## V2 变更日志

- 2026-05-01：起草 V2 progress 框架，沿用 V1a hardening 结构；V2.0 第一波执行计划与写入范围预约写入「V2.0 启动准备」。

# Agent Buyer V3 Progress

## V3 启动准备

- 时间：2026-05-02 CST。
- 背景：V2 已完成 agent loop、多 provider、context compact、skill、SubAgent、ToDo、多实例运行态、interrupt、真实 LLM E2E 与 HITL 语义确认；V3 开始建设 Agent Buyer Console，用浏览器展示 run list、trajectory timeline、runtime state、chat/SSE、HITL、interrupt 和 abort。
- 当前文档状态：
  - `requirement.md` 附录 B 已定义 V3 产品定位、边界、接口与验收。
  - `design.md` 第 12 章已定义 V3 架构、数据流、安全边界、前端工程与里程碑。
  - `task.md` 已拆解 `V3-M1` 到 `V3-M5`，当前全部为 `PENDING`。
  - `package-architecture.md` 已补充 V3 `web.admin` 后端 package 与 `admin-web` 前端目录边界。
- V3 关键约束：
  - 对话能力复用现有 `/api/agent/*`，不新增 `/api/admin/chat`。
  - Admin 只新增 `GET /api/admin/console/runs` 与 `GET /api/admin/console/runs/{runId}/runtime-state`。
  - Console 不是通用 MySQL / Redis browser，不提供任意 SQL、任意 Redis key 查询或动态 sort/table 参数。
  - Runtime state 只返回当前 run 固定 key 的投影；`agent:active-runs` 只用于计算 `activeRun` 布尔值，不返回完整 set。
  - `confirmToken`、admin token、provider key、原始 provider payload 和大 tool result 必须在后端 DTO 与前端展示双层脱敏。
  - V3 前端 POST SSE 必须使用自研 parser；`tool_use` 事件以 `toolName` 为规范字段，并兼容历史 `name`。
- 下一步：
  - 从 `V3M1-01` 开始，先补 V3 实施基线与现有 endpoint / DTO / Redis key 清单。
  - 每完成一个 V3 task，同步更新 `task.md` 状态与本文件进度记录。
  - 每个里程碑 gate 沿用主 agent 分发与验收、sub agent 执行的工作流；Java/YAML/SQL 变更使用 `java-alibaba-review` gate，前端 React/TypeScript 变更使用 `front-alibaba-review` gate。

### V3M1-01 基线梳理记录

- 时间：2026-05-02 CST。
- 状态：`IN_PROGRESS` → `DONE`（纯文档任务）。
- 输出：梳理 V3 实施基线与契约，记录现有 endpoint、DTO、Redis key、admin 配置缺口。

#### 现有 `/api/agent/*` Endpoint 清单

来源：`com.ai.agent.web.controller.AgentController`

| Endpoint | Method | 功能 | SSE | Header |
|---|---|---|---|---|
| `/api/agent/runs` | POST | 创建新 run | 是 | `X-User-Id` |
| `/api/agent/runs/{runId}/messages` | POST | 继续对话 | 是 | `X-User-Id` |
| `/api/agent/runs/{runId}` | GET | 查询 trajectory | 否 | `X-User-Id` |
| `/api/agent/runs/{runId}/abort` | POST | 终止 run | 否 | `X-User-Id` |
| `/api/agent/runs/{runId}/interrupt` | POST | 中断当前 turn | 否 | `X-User-Id` |

V3 Console 复用以上全部 endpoint，不新增 `/api/admin/chat`。

#### Trajectory DTO 清单

来源：`com.ai.agent.trajectory.dto.*`

| DTO | 用途 |
|---|---|
| `AgentRunTrajectoryDto` | trajectory 顶层容器，含 `run`、`messages`、`llmAttempts`、`toolCalls`、`toolResults`、`events`、`compactions` |
| `RunDto` | run summary：runId、userId、status、turnNo、agentType、parentRunId、parentLinkStatus、primaryProvider、fallbackProvider、model、maxTurns、startedAt、updatedAt、completedAt、lastError |
| `MessageDto` | user/assistant message，含 role、content、timestamp |
| `MessageToolCallDto` | message 中嵌入的 tool call 引用 |
| `LlmAttemptDto` | LLM 调用记录：attemptId、provider、model、usage、finishReason、error |
| `ToolCallDto` | tool call 详情：toolCallId、toolName、args、status、createTime |
| `ToolProgressDto` | tool 执行进度：percent、message |
| `ToolResultDto` | tool 结果：toolCallId、result/synthetic/cancelReason、createTime |
| `EventDto` | 状态事件：eventType、details、timestamp |
| `CompactionDto` | compaction 记录：strategy、beforeTokens、afterTokens、compactedMessageIds |

V3-M1 Run List DTO 需参考 `RunDto` 字段；Timeline Panel 直接复用 `AgentRunTrajectoryDto` 结构。

#### RedisKeys 中 V3 会读取的 Key

来源：`com.ai.agent.tool.runtime.redis.RedisKeys`

| Key Pattern | 类型 | V3 用途 |
|---|---|---|
| `agent:{run:<runId>}:meta` | HASH | run 元数据：status、userId、turnNo、createdAt |
| `agent:{run:<runId>}:queue` | ZSET | tool queue：score 为执行时间 |
| `agent:{run:<runId>}:tools` | HASH | tool 状态：toolUseId -> status/resultPath |
| `agent:{run:<runId>}:leases` | HASH | tool lease：toolUseId -> expireAt |
| `agent:{run:<runId>}:control` | HASH | abort/interrupt 控制字段 |
| `agent:{run:<runId>}:children` | SET | SubAgent child runId 集合 |
| `agent:{run:<runId>}:todos` | HASH | ToDo steps |
| `agent:{run:<runId>}:todo-reminder` | STRING | transient reminder content |
| `agent:{run:<runId>}:llm-call-budget` | STRING | budget 计数 |
| `agent:{run:<runId>}:tool-use-ids` | SET | 当前 turn tool use ID 集合 |
| `agent:{run:<runId>}:continuation-lock` | STRING | continuation 锁 |
| `agent:active-runs` | SET | 全局 active runId 集合（只用于计算 `activeRun` 布尔值，不返回完整 set） |

V3 Runtime State DTO 只返回当前 run 固定 key 投影，不支持任意 Redis key 输入。

#### 当前 AgentProperties Admin 配置缺口

来源：`com.ai.agent.config.AgentProperties`

当前配置结构：
- `agent.redis-key-prefix`
- `agent.default-allowed-tools`
- `agent.max-parallel` / `max-scan` / `lease-ms` / `confirmation-ttl`
- `agent.reaper.enabled` / `interval-ms`
- `agent.agent-loop.*`（maxTurns、timeout、budget）
- `agent.executor.*`
- `agent.llm.*`（provider、deepseek、qwen）
- `agent.rate-limit.*`
- `agent.request-policy.*`
- `agent.context.*`（compact threshold）
- `agent.skills.*`
- `agent.sub-agent.*`
- `agent.runtime.*`（sweeper、pubsub、interrupt）
- `agent.todo.*`

**缺口**：缺少 V3 Console Admin 访问控制配置：
- `agent.admin.enabled`：boolean，是否启用 Admin Console API
- `agent.admin.token`：string，Admin 访问令牌

V3M1-02 需新增以上配置项，并实现 `AdminAccessGuard` 校验。

#### V3M1-01 结论

- 基线信息已梳理完成，无功能代码变更。
- 下一步：V3M1-02，新增 `agent.admin.enabled/token` 配置与 `AdminAccessGuard` 校验。

### V3M1-06 Review Gate 记录

- 时间：2026-05-02 CST。
- 状态：`DONE`。
- 测试命令：`MYSQL_PASSWORD='Qaz1234!' mvn test`
- 测试结果：278 tests, 0 failures, 0 errors, BUILD SUCCESS

#### java-alibaba-review 结论

- **无 P0/P1/P2 issue**
- 审查范围：`web.admin.controller/service/dto`、`AgentProperties.Admin`、`application.yml`
- 安全边界确认：
  - SQL 参数绑定正确，无动态 sort/table 参数
  - pageSize clamp 到 1-100
  - confirmToken/abortToken 过滤不暴露
  - active-runs set 只返回 `activeRun` boolean
  - AdminAccessGuard 校验所有入口

#### 修复记录

- **P2 修复**：多 profile token 校验
  - 问题：`activeProfile` 字符串匹配会误伤 `production,localish`
  - 修复：使用 `Environment.getActiveProfiles()` 数组精确匹配
  - 新增测试：`local,test` 允许空 token；`production,localish` 拒绝空 token

- **P3 修复**：删除 sortBy 字段
  - 问题：API 接受 sortBy 但完全忽略，契约不透明
  - 修复：删除 `AdminRunListQuery.sortBy` 字段
  - 测试改为验证固定 ORDER BY updated_at DESC

#### 下一步

- V3M1-GATE：手工 smoke 验证两个 endpoint
- 然后开始 V3-M2 Frontend Shell

### V3M1-GATE Smoke 验证记录

- 时间：2026-05-02 CST。
- 状态：`DONE`。
- 环境：`mvn spring-boot:run -Dspring-boot.run.profiles=local`

#### Run List Endpoint 验证

- URL：`GET /api/admin/console/runs`
- 响应：HTTP 200，返回 20 条 run 记录
- 字段验证：runId, userId, status, turnNo, agentType, parentRunId, parentLinkStatus, primaryProvider, fallbackProvider, model, maxTurns, startedAt, updatedAt, completedAt, lastError
- 排序验证：按 updatedAt DESC 排序

#### Runtime State Endpoint 验证

- URL：`GET /api/admin/console/runs/{runId}/runtime-state`
- 测试 runId：`run_moni70ez_ad9ce2b7e03e1210`（PAUSED）
- 响应：HTTP 200
- 字段验证：
  - `runId` 正确
  - `activeRun=false`（布尔值，非完整 set）
  - `entries` 包含固定 key：meta, queue, tools, leases, control, children, todos, todo-reminder
  - `meta` 包含 interrupt_requested/interrupt_at，无 confirmToken
  - 无完整 `agent:active-runs` set

#### 结论

- V3-M1 Backend Console API 完成

## 2026-05-02 V3 Frontend Console 完成

### V3M2 Frontend Shell 完成

- **状态**：`DONE`
- **实现内容**：
  - ConsoleShell 组件：Header + 三栏布局
  - Settings Modal：userId、adminToken 配置
  - localStorage 持久化：userId、adminToken、debug 状态
  - 响应式布局：Desktop 三栏并排，Mobile 底部 Tabs 切换
  - Header 颜色：用户反馈后改为蓝色 `bg-blue-600`

### V3M3 Run List + Timeline + Runtime Debug 完成

- **状态**：`DONE`
- **实现内容**：
  - RunListPanel：分页、状态过滤、userId 过滤、run 选择
  - TimelinePanel：MESSAGE、LLM_ATTEMPT、TOOL_CALL、TOOL_PROGRESS、TOOL_RESULT、EVENT、COMPACTION
  - DebugDrawer：Runtime State 可视化，敏感字段脱敏 `[REDACTED]`
  - Hooks：useRunList、useRunDetail、useRuntimeState
  - API：createAdminApi、createAgentApi

### V3M4 Chat + SSE 完成

- **状态**：`DONE`
- **实现内容**：
  - ChatPanel：消息流、ToolCard 显示、HITL 确认按钮
  - useChatStream：text_delta、tool_use、tool_progress、tool_result、final、error 处理
  - useChatMessages：sendMessage、sendConfirmation
  - RunControls：New Chat、Refresh、Interrupt、Abort
  - SSE streaming：POST + ReadableStream（非 EventSource）
  - confirmToken 脱敏：事件处理前删除，不存入 state

### V3M5 Hardening + Demo 完成

- **状态**：`DONE`
- **实现内容**：
  - V3M5-01：启动脚本 `scripts/start-console-dev.sh`
  - V3M5-02：README 中文文档
  - V3M5-03：测试 fixtures（RunSummary、TrajectoryNode、RuntimeState、SseEvent）
  - V3M5-04：App.integration.test.tsx（完整 UI 流测试）
  - V3M5-05：Playwright browser smoke（Desktop 1440x900、Mobile 390x844）
  - V3M5-06：ESLint 修复、最终验证

### V3 最终验证结果

- **前端测试**：164 tests pass（vitest）
- **前端构建**：npm run build 成功
- **前端 Lint**：ESLint 0 errors, 0 warnings
- **后端测试**：278 tests pass（mvn test）
- **代码审查**：
  - front-alibaba-review：无 P0/P1/P2 阻断 issue
  - java-alibaba-review：已通过（V3M1-GATE）

### V3-GATE 验收清单

- V3M1-GATE：DONE（Backend Console API）
- V3M2-GATE：DONE（Frontend Shell）
- V3M3-GATE：DONE（Run List + Timeline + Debug）
- V3M4-GATE：DONE（Chat + SSE）
- V3M5-GATE：DONE（Hardening + Demo）

### Console 功能清单验证

- Run List：分页浏览、状态过滤、userId 过滤 ✓
- Timeline：完整 trajectory 节点展示 ✓
- Runtime State：Debug Drawer 显示 Redis state ✓
- Chat：创建新 run、发送消息、SSE streaming ✓
- HITL：WAITING_USER_CONFIRMATION 确认/放弃按钮 ✓
- PAUSED：user_input placeholder 提示 ✓
- Run Controls：New Chat、Refresh、Interrupt、Abort ✓
- 安全边界：confirmToken、adminToken、API key 脱敏 ✓

### 安全边界确认

Console 不做：
- 不做通用 MySQL table browser ✓
- 不做任意 Redis key browser ✓
- 不新增 `/api/admin/chat` ✓
- 不实现生产级多租户 admin RBAC ✓
- 不暴露原始 confirmToken、provider key、admin token ✓

### 下一步

- E2E 真实 LLM 测试（启动后端 + 前端，使用真实 DeepSeek/Qwen API）
- V3 完成，准备 push
- 开始 V3-M2 Frontend Shell
