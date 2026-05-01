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

### V20-03 PENDING

- 写入范围：`QwenProviderAdapter`、`QwenCompatibilityProfile`、`config` 增加 Qwen base-url/api-key/model、单元测试。
- 前置：`V20-02`。
- 关注点：Qwen stream tool delta 组装；error mapping 与 DeepSeek 行为对齐；不污染 DeepSeek profile。

### V20-04 PENDING

- 写入范围：`ProviderFallbackPolicy`、`LlmAttemptService` fallback 分支、`agent_llm_attempt` 写入扩展。
- 前置：`V20-03`。
- 关注点：建连前才允许 fallback；stream 已写入 tool delta 后必须禁止 fallback（这是 V2 约束的核心安全边界）；fallback 选型只能从 RunContext 读取。

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

### V20-05 PENDING

- 写入范围：`AgentExecutionBudget`、`AgentTurnOrchestrator` 嵌入预算检查、`agent_event` 新事件类型 `MAIN_TURN_BUDGET` / `RUN_WIDE_BUDGET`。
- 前置：`V20-04`、`V20-04a`。
- 关注点：单 user turn 30 次 + run-wide 80 次双层预算；触发后必须 `RUNNING -> PAUSED`，不允许覆盖已终态 run。

### V20-06 PENDING

- 写入范围：`ContextViewBuilder`、`ProviderViewMessage`、`LlmAttemptService` 调用前接入 view、复用 `TranscriptPairValidator`。
- 前置：`V20-02`。
- 关注点：原始 trajectory 不能被改写；compact 前后都要走 pair validator。

### V20-07 PENDING

- 写入范围：`LargeResultSpiller`、`ContextViewBuilder` 接入；不动 MySQL 原始结果。
- 前置：`V20-06`。
- 关注点：threshold（2000 token）从配置读；resultPath 是 logical path，不引入对象存储。

### V20-08 PENDING

- 写入范围：`MicroCompactor`、token estimator、`ContextViewBuilder` 接入。
- 前置：`V20-07`。
- 关注点：旧 tool result 替换为 `<oldToolResult>` 占位但保留 message id，pair validator 不能爆。

### V20-09 PENDING

- 写入范围：`SummaryCompactor`、summary JSON schema、`PromptAssembler` 注入 summary；不动原始 trajectory。
- 前置：`V20-08`。
- 关注点：summary 必须包含 `summaryText / businessFacts / toolFacts / openQuestions / compactedMessageIds`；保留 system prompt 和最近消息预算窗口。

### V20-10 PENDING

- 写入范围：`agent_context_compaction` 表、Flyway `V8__agent_context_compaction.sql`、`ContextCompactionRepository`、trajectory query DTO 扩展。
- 前置：`V20-09`。
- 关注点：迁移可重复执行；查询接口仍要走 `RunAccessManager`。

### V20-11 PENDING

- 写入范围：`*IntegrationTest` 系列；不动业务代码。
- 前置：`V20-05`、`V20-10`。
- 关注点：测试必须覆盖 RunContext provider 复用、fallback 安全边界、50K context compact、summary fields、PAUSED 迁移、budget exceeded。

### V20-12 PENDING

- 写入范围：`README.md`、`postman/*.json`、`application*.yml` 示例配置。
- 前置：`V20-11`。
- 关注点：provider/context/budget 配置全部上 README；Postman 增加 multi-provider smoke 与 PAUSED continuation。

### V20-GATE PENDING

- 验收前置：V20-01..V20-12 全部 `DONE`；`MYSQL_PASSWORD=*** mvn test` 通过；`java-alibaba-review` 对本里程碑改动复审无 P0/P1/P2；本节追加 V2.0 完成摘要。

## V2.1 任务进度

V2.1 必须在 `V20-GATE` 完成后启动。下列条目仅作为占位，待 V2.0 收口后再补每个任务的写入范围与关注点。

- `V21-01` PENDING — SkillRegistry / Anthropic skill 扫描。
- `V21-02` PENDING — SkillPathResolver 路径安全。
- `V21-03` PENDING — SkillListTool / SkillViewTool。
- `V21-03a` PENDING — PromptAssembler 注入 skill preview。
- `V21-04` PENDING — SkillCommandResolver slash 注入。
- `V21-05` PENDING — slash skill budget fail closed。
- `V21-06` PENDING — AgentTool schema + 反滥用提示。
- `V21-07` PENDING — child run MySQL 字段 + Flyway V9。
- `V21-08` PENDING — SubAgentRegistry / SubAgentProfile。
- `V21-09` PENDING — SubAgentBudgetPolicy + ChildRunRegistry + reserve_child.lua。
- `V21-10` PENDING — release_child.lua + child lifecycle。
- `V21-11` PENDING — SubAgentRunner 同步等待。
- `V21-12` PENDING — child timeout + parentLinkStatus。
- `V21-13` PENDING — SubAgent LLM call budget。
- `V21-14` PENDING — V2.1 集成 / 负向测试。
- `V21-15` PENDING — V2.1 文档收口。
- `V21-GATE` PENDING — V2.1 hardening review gate。

## V2.2 任务进度

V2.2 必须在 `V21-GATE` 完成后启动。占位列表：

- `V22-01` PENDING — 多实例配置。
- `V22-02` PENDING — ActiveRunSweeper 职责边界。
- `V22-03` PENDING — active runs stale cleanup。
- `V22-04` PENDING — ToolResultPubSub。
- `V22-05` PENDING — Pub/Sub polling fallback。
- `V22-06` PENDING — TodoStore Redis 模型。
- `V22-07` PENDING — ToDoCreateTool / ToDoWriteTool。
- `V22-08` PENDING — TodoReminderInjector。
- （`V22-09` 序号空缺，PAUSED state machine 已合并到 `V20-04a`）
- `V22-10` PENDING — interrupt HTTP endpoint。
- `V22-11` PENDING — RunInterruptService + 工具取消。
- `V22-12` PENDING — SubAgent interrupt 级联。
- `V22-13` PENDING — 多实例 schedule 并发测试。
- `V22-14` PENDING — V2.2 集成 / 负向测试。
- `V22-15` PENDING — V2.2 文档收口。
- `V22-GATE` PENDING — V2.2 hardening review gate。

## V2 踩坑记录

随开发追加。延续 V1a 形式：一行结论 + 必要的根因或测试约束。当前为空。

## V2 变更日志

- 2026-05-01：起草 V2 progress 框架，沿用 V1a hardening 结构；V2.0 第一波执行计划与写入范围预约写入「V2.0 启动准备」。
