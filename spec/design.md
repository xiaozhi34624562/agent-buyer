# Agent Loop Design

## 目录

- 1. 背景与目标
- 2. 总体架构
  - 2.1 审查后重构架构
  - 2.2 Message Role 模型
  - 2.2 Session / Run / Turn / Attempt
- 3. HTTP / SSE 接入
  - 3.1 创建 run
  - 3.2 继续同一个 run
  - 3.3 Abort run
  - 3.4 Interrupt current turn
  - 3.5 查询 trajectory
- 4. Redis 数据模型
- 5. 调度语义
- 6. Java 组件设计
  - 6.1 AgentLoop
  - 6.2 DeepSeek Provider Adapter
  - 6.3 PromptAssembler
  - 6.4 Tool
  - 6.5 ToolRegistry
  - 6.6 业务 Tool 示例
  - 6.7 ToolRuntime / RedisToolStore
  - 6.8 Executor 与 Lease Reaper
  - 6.9 核心 Model
  - 6.10 MySQL Trajectory Store
  - 6.11 Tool Result Waiter
  - 6.12 分布式调度
  - 6.13 安全边界
  - 6.14 可观测性
  - 6.15 Graceful Shutdown
  - 6.16 演示部署
- 7. Lua 脚本设计
- 8. 错误处理与边界
- 9. 配置建议
- 10. 测试计划
- 11. V2 设计
  - 11.0 V2 里程碑拆分
  - 11.1 V2 新增组件
  - 11.2 Provider Registry 与 Fallback
  - 11.3 Context Compact
  - 11.4 Skill 渐进式加载
  - 11.5 SubAgent / AgentTool
  - 11.6 ToDo 短期计划
  - 11.7 中断与预算控制
  - 11.8 多实例推进
  - 11.9 V2 配置
  - 11.10 V2 测试计划
- 12. V3 Agent Buyer Console 设计
  - 12.1 V3 架构定位
  - 12.2 前端架构
  - 12.3 后端 Admin Console API
  - 12.4 Chat / SSE 集成
  - 12.5 Runtime State 安全模型
  - 12.6 V3 配置
  - 12.7 V3 测试计划
- 13. 后续演进
- 14. 关键结论

## 1. 背景与目标

本文档设计已有 Java 业务系统的 AI 赋能层。用户通过对话触发原有业务能力，Agent 把这些能力作为 tool 调用并组合多步流程。

V1a 落地为单 Docker 容器应用，外接单实例 MySQL 与 Redis。Agent 应用内置 mock business module 和 seed data，用来模拟已有业务系统；后续接真实业务系统时，只替换 `BusinessClient` / `BusinessService` adapter，不改 AgentLoop、ToolRuntime、trajectory 和 SSE 协议。多实例分布式架构只作为演进方向描述，V1a 不实现。

V1a 的核心目标：

- 让用户通过 HTTP + SSE 对话入口完成真实业务流程
- 只接入 DeepSeek，先跑稳 OpenAI-compatible streaming tool calling
- 通过 Redis 管理活跃 tool queue、lease、CAS complete
- 通过 MySQL 追溯完整 trajectory
- 提供 2-3 个业务 tool 示例，而不是文件系统 tool demo
- 提供可复现演示：用户取消昨天的订单，覆盖 query、dry-run、用户确认、confirm、final
- 保留多实例、多 provider、context compact、skill、subAgent 和 ToDo 的演进接口，但不把它们放进 V1a 热路径

V1a 主流程：

```text
HTTP POST /api/agent/runs
  ↓
AgentController 从 header/JWT 解析 userId
  ↓
AgentLoop 创建 run，写 agent_run / user message
  ↓
PromptAssembler 组装 materialized system prompt + history + provider tools
  ↓
DeepSeekProviderAdapter.streamChat
  ↓
AttemptBuffer 暂存 text delta / tool_call delta
  ↓
provider attempt 成功完成
  ↓
AttemptCommitter 写 assistant message + tool call trace + Redis WAITING
  ↓
ToolRuntime schedule / execute / complete
  ↓
ToolResultWaiter polling Redis 等待 tool result
  ↓
append tool result message
  ↓
如 tool result.actionStatus=PENDING_CONFIRM:
  run -> WAITING_USER_CONFIRMATION，等待用户继续输入
  ↓
下一 turn 或 final answer
```

关键取舍：

- V1a 不做 Claude Code 式 eager streaming tool execution；tool call 只有在 provider attempt 完整成功后才会进入 Redis
- V1a 不做 provider fallback；DeepSeek 失败则当前 run failed 或由用户重试
- V1a 不做 context compact；超过上下文硬上限直接 fail
- V1a 不实现 RBAC；复用外部系统认证权限，只做工具白名单与审计
- `agent_event` 与 `agent_tool_progress` 异步批量写，可降级丢弃；关键 trajectory 同步写，失败则 fail closed
- SSE `text_delta` 是 provisional output，只有 committed assistant message / final event 才代表正式回答

## 2. 总体架构

```text
AgentController
  |
  | REST request + SSE response
  v
AgentLoop
  |
  | assemble prompt + provider tools
  v
DeepSeekProviderAdapter
  |
  | streaming events
  v
AttemptBuffer + ToolCallAssembler
  |
  | attempt success
  v
AttemptCommitter
  |
  | MySQL critical trajectory + Redis WAITING
  v
ToolRuntime
  |
  | Redis Lua schedule
  v
Bounded Executor
  |
  | Tool.run
  v
Redis complete CAS
  |
  | polling result
  v
AgentLoop next turn / final
```

V1a 组件表：

| 组件 | 职责 |
|---|---|
| `AgentController` | 暴露 HTTP REST + SSE，解析 userId，调用 AgentLoop |
| `AgentLoop` | 控制 LLM turn、tool call、tool result、final answer |
| `PromptAssembler` | 生成 materialized system prompt、history、provider tools |
| `UserProfileStore` | 从现有用户表/服务读取用户身份、租户、角色、偏好 |
| `BusinessClient` | 业务系统适配边界；V1a 调用 in-process mock business module，后续可替换为 REST client |
| `MockBusinessModule` | V1a 内置订单和用户资料 seed data，保证 demo 可独立运行 |
| `LlmProviderAdapter` | Provider 统一接口；V1a 只有 DeepSeek 实现 |
| `DeepSeekProviderAdapter` | DeepSeek OpenAI-compatible streaming 接入 |
| `LlmParamsParser` | 解析 model、temperature、maxTokens、timeout、maxTurns |
| `ToolCallAssembler` | 从 streaming delta 组装完整 tool call |
| `AttemptBuffer` | 暂存当前 provider attempt 的 text/tool_call/usage/finish reason |
| `AttemptCommitter` | attempt 成功后提交 assistant message、tool call trace、Redis WAITING |
| `TrajectoryStore` | 同步写关键 MySQL trajectory |
| `AsyncTrajectoryEventWriter` | 异步批量写 agent_event / agent_tool_progress |
| `TranscriptPairValidator` | provider 请求前校验 assistant tool_calls 与 tool results 配对 |
| `ProviderCompatibilityProfile` | 描述 DeepSeek 的 schema、message、stream、tool id 方言 |
| `ToolRuntime` | 对 AgentLoop 暴露 ingest / schedule / submit / complete |
| `RedisToolStore` | 封装 Redis key、Lua 脚本、状态读写 |
| `ToolLeaseReaper` | V1a 单 JVM 后台任务，处理过期 RUNNING |
| `ToolRegistry` | Spring 策略工厂，按规范化 toolName 找到 Tool |
| `Tool` | 业务工具接口，包含 schema / validate / run |
| `AbstractTool` | 模板基类，统一 validate 调用、输出脱敏、大小限制、progress 包装 |
| `RunRepairService` | 启动时关闭崩溃遗留的非 terminal run 和 orphan tool call |
| `ExecutorService` | 本机 bounded 工具执行线程池 |
| Redis Lua | 去重、调度、complete CAS、abort/cancel 的原子性 |

V1a 不在组件表中引入：

- `SkillRegistry / SkillListTool / SkillViewTool`
- `SubAgent / AgentTool / SubAgentRegistry / SubAgentRunner`
- `ToDoCreate / ToDoWrite / TodoStore / TodoReminderInjector`
- `ContextCompactor / SummaryCompactor / LargeResultStore`
- `LlmProviderAdapterRegistry / ProviderFallbackPolicy`
- `RedisMysqlReconciler`

这些能力进入 V2 演进章节。

### 2.1 审查后重构架构

代码审查后，V1a 的目标架构需要从“一个 AgentLoop 直接串起所有能力”调整为“入口、应用编排、turn 控制、工具协调、轨迹读写、治理策略”分层。

```text
AgentController
  |
  | HTTP/SSE only
  v
AgentRunApplicationService
  |
  | create / continue / abort / query
  v
RunAccessManager + AgentRequestPolicy
  |
  | owner check / effective tools / request budget
  v
AgentTurnOrchestrator
  |
  | while turns
  v
LlmAttemptService
  |
  | provider call + attempt trace
  v
ToolCallCoordinator
  |
  | commit tool calls + ingest + wait
  v
ToolResultCloser
  |
  | synthetic close on failure / timeout / abort
  v
RedisToolRuntime + ToolRegistry + Tool
```

新增或重定位组件：

| 组件 | 分层 | 职责 |
|---|---|---|
| `AgentRunApplicationService` | application | 对外用例服务，承载 create/continue/abort/query 的业务编排 |
| `RunAccessManager` | domain/service | 统一校验 run 归属、run 状态、continuation lock 和可访问 trajectory |
| `RunContextStore` | trajectory/domain | 持久化 create run 时确定的 `effectiveAllowedTools`、model、maxTurns 等运行上下文 |
| `AgentRequestPolicy` | policy | 校验 message 数量、content 长度、model 白名单、temperature、maxTokens、maxTurns、工具授权交集 |
| `AgentTurnOrchestrator` | application | 只负责 turn loop、终止条件和组件协作，不直接操作 Redis Lua 或 HTTP |
| `LlmAttemptService` | infrastructure boundary | 调用 provider，处理 retry、usage、attempt trace 和 text delta |
| `ToolCallCoordinator` | domain/service | 提交 assistant tool calls、执行 precheck、ingest Redis、等待 terminal result |
| `ToolResultCloser` | domain/service | 为 timeout、abort、executor reject、precheck failure 等路径生成 synthetic tool result |
| `RunStateMachine` | domain | 集中表达 run 状态迁移，拒绝非法状态跳转 |
| `ConfirmationIntentService` | domain/service | 高置信规则分类，快速判断明确确认、拒绝或需要语义兜底的输入 |
| `HumanIntentResolver` | domain/service | 编排 human-in-the-loop 判断：规则先判，规则不命中时调用 LLM 结构化分类，低置信或失败时 fail closed 为追问 |
| `ProviderSemanticConfirmationIntentClassifier` | infrastructure boundary | 使用当前 run provider / fallback provider 判断长尾确认语义，只返回结构化 JSON，不直接执行写操作；分类调用写入 `agent_llm_attempt` 与 `confirmation_intent_llm` event |
| `TrajectoryWriter` | persistence boundary | 写 run、message、attempt、tool call、tool result 等关键轨迹 |
| `TrajectoryReader` | persistence boundary | 给 provider replay 和内部 repair 读取原始 messages |
| `TrajectoryQueryService` | application/query | 对外 trajectory DTO 查询，负责权限、字段裁剪和脱敏 |

调整后的核心规则：

- Controller 不直接调用 `TrajectoryStore`、`RedisToolStore` 或具体 loop 细节。
- `allowedToolNames` 只在 create run 时参与计算；continuation 使用 `RunContextStore` 中的 `effectiveAllowedTools`。
- run 初始化写入 `agent_run` 与 `agent_run_context` 必须保持一致；如果 context 创建失败，run 要补偿进入 `FAILED`。
- continuation 写入用户消息前必须先验证 `RunContext` 中的 model、maxTurns 和工具集合；历史 context 中的未知工具不能让 run 卡在 `RUNNING`。
- 历史 migration 对无法证明原始授权的 active/waiting run context 采用空工具集，避免升级时默认授予写工具。
- `abort_requested` 是 run control 信号，必须被 scheduler、runtime、complete 和 tool cancellation token 共同使用。
- abort 对工具的处理必须区分副作用风险：`WAITING` 和幂等 `RUNNING` 工具可以 synthetic cancel；非幂等 `RUNNING` 写工具不立即合成取消结果，后续真实 complete 仍可写入 trajectory，以保证审计不掩盖已发生的业务副作用。
- assistant tool call 一旦写入 MySQL，就必须由 `ToolResultCloser` 负责在任何失败路径上闭合。
- human-in-the-loop 确认采用三层边界：`ConfirmationIntentService` 先做高置信规则判断；规则不命中时 `HumanIntentResolver` 调用 provider-backed classifier 进行结构化语义判断；低置信、解析失败或 provider 失败统一 fail closed 为追问，并把 run 恢复到 `WAITING_USER_CONFIRMATION`。
- provider-backed classifier 本身也是一次 LLM 调用，必须写入 `agent_llm_attempt`；分类结果额外写 `confirmation_intent_llm` event，方便区分真实 agent turn 与 human-in-the-loop 策略判断。
- provider-backed classifier 只能影响“继续/拒绝/追问”的控制流，不能绕过服务端 `confirmToken + argsHash + userId + runId + toolName` 校验。
- tool precheck failure 必须区分 fatal 与 recoverable；缺少 `orderId` 这类可由用户补充的信息时，tool result 带 `recoverable=true` 和 `nextActionRequired=user_input`，AgentLoop 将 run 置为 `PAUSED` 并通过 SSE 追问。
- abort 只对 active run 做 CAS 迁移；已终态 run 返回原状态，避免用户重复点击或客户端重试覆盖历史结论。
- 对外 trajectory 查询只能返回 DTO，不能直接返回 MyBatis entity 或 provider raw payload。

### 2.2 Message Role 模型

V1a 内部 role：

```java
public enum MessageRole {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL
}
```

约束：

- `SYSTEM`：存本 run 的 materialized system prompt
- `USER`：存用户输入
- `ASSISTANT`：存模型文本、tool calls、final answer
- `TOOL`：存 tool result，必须关联 `toolUseId`
- V1a 没有 `SUMMARY` role
- V2 如果引入 summary compact，summary 使用 `ASSISTANT` role，metadata 放 `LlmMessage.extras`

为什么不保留 `SUMMARY` role：

- OpenAI-compatible provider 不认识 summary role，最终仍要映射
- 多一个内部 role 会增加 provider replay 与 transcript validation 的复杂度
- summary 本质是 assistant 对历史的压缩叙述，用 `ASSISTANT + extras` 更贴近 provider transcript

### 2.2 Session / Run / Turn / Attempt

| 概念 | 说明 |
|---|---|
| `session` | 长期会话容器，可包含多个 run |
| `run` | 一次任务执行，从用户输入到 final/failed/cancelled/timeout |
| `user turn` | 一次用户驱动的执行周期，对应一次 create run SSE 或 continuation SSE；V2 的 30 次 LLM 调用预算按 user turn 计算 |
| `turn` | V1a 表结构中的模型推进轮次，通常是一轮 provider 请求，以及可能随后的 tool result 回填 |
| `llm call` | 一次真实 provider 请求；primary、fallback、建连前失败 retry 都计入 V2 LLM 调用预算 |
| `attempt` | 同一个 turn 的一次 provider 请求；V1a 通常只有一个 DeepSeek attempt |
| `tool call` | assistant 发起的工具调用，必须最终匹配 tool result |

Run 状态：

```java
public enum RunStatus {
    CREATED,
    RUNNING,
    WAITING_USER_CONFIRMATION,
    PAUSED,
    SUCCEEDED,
    FAILED,
    FAILED_RECOVERED,
    CANCELLED,
    TIMEOUT
}
```

`WAITING_USER_CONFIRMATION` 是 dry-run 写操作后的产品状态。它不代表 provider 还在运行，也不代表 tool 还在 RUNNING；它表示当前 run 的上一轮已经完成，正在等待用户通过 continuation endpoint 追加确认/取消消息。

`PAUSED` 是 V2 的非终态状态，用于用户 interrupt、LLM 调用预算耗尽、SubAgent 等待超时等场景。`PAUSED` run 可以通过 continuation 继续；`CANCELLED` run 不再继续。

V1a 单容器内，run 的 schedule、executor、reaper 都在同一 JVM。但 Redis 仍是活跃状态真相源，为后续多实例演进保留边界。

## 3. HTTP / SSE 接入

### 3.1 创建 run

```http
POST /api/agent/runs
Authorization: Bearer <jwt>
Accept: text/event-stream
Content-Type: application/json
```

请求体：

```json
{
  "messages": [
    {"role": "user", "content": "取消我昨天的那个订单"}
  ],
  "allowedToolNames": ["query_order", "cancel_order"],
  "llmParams": {
    "model": "deepseek-chat",
    "temperature": 0.2,
    "maxTokens": 4096
  }
}
```

规则：

- `userId` 只从 header/JWT 获取
- 请求体里的 `allowedToolNames` 只能进一步收窄外部权限系统给出的工具集合，不能放大权限
- `llmParams.maxTurns` 默认 10，最大 10
- 单 run wallclock timeout 300 秒

SSE events：

```text
event: text_delta
data: {"attemptId":"att_1","delta":"我先帮你查询昨天的订单。"}

event: tool_use
data: {"toolUseId":"call_1","toolName":"query_order","args":{"date":"yesterday"}}

event: tool_progress
data: {"toolCallId":"tc_1","stage":"querying","message":"正在查询订单","percent":40}

event: tool_result
data: {"toolUseId":"call_1","status":"SUCCEEDED","result":{"count":1}}

event: final
data: {"runId":"run_123","finalText":"已为你取消订单 O-1001。","status":"SUCCEEDED","nextActionRequired":null}

event: final
data: {"runId":"run_123","finalText":"将取消订单 O-1001，请确认是否继续。","status":"WAITING_USER_CONFIRMATION","nextActionRequired":"user_confirmation"}

event: error
data: {"runId":"run_123","message":"run timeout"}
```

`text_delta` 是 provisional output。Provider attempt 成功并由 AttemptCommitter 写入 `agent_message` 后，文本才成为 committed assistant message。客户端必须以 `final / error` 作为本轮 UI 状态收敛点。

SSE 连接要求：

- 服务端每 15 秒发送一次 `event: ping`
- `text_delta` 带 `attemptId`，前端在 attempt failed / retry 时可以清理旧 provisional 文本
- `final` event 带 `nextActionRequired`，取值 `null / user_confirmation / user_input`
- 客户端断开时，服务端记录 disconnect reason；不因为客户端断开自动取消 run，除非请求显式 abort

### 3.2 继续同一个 run

```http
POST /api/agent/runs/{runId}/messages
Authorization: Bearer <jwt>
Accept: text/event-stream
Content-Type: application/json
```

请求体：

```json
{
  "message": {
    "role": "user",
    "content": "确认取消"
  }
}
```

用途：

- 用户确认 dry-run 写操作
- 用户补充缺失信息
- run 处于 `WAITING_USER_CONFIRMATION` 时继续推进

行为：

- 校验 run 归属和状态
- 通过 Redis `SET lockKey value NX PX 300000` 获取 continuation lock
- 未拿到锁时返回 HTTP 409，避免两个浏览器 tab 同时推进同一个 run
- 追加 user message
- 重新进入 AgentLoop
- 复用同一个 `runId`
- SSE 返回新的 `text_delta / tool_use / tool_result / final`
- 本轮结束或失败时释放 continuation lock；TTL 作为异常兜底

### 3.3 Abort run

```http
POST /api/agent/runs/{runId}/abort
Authorization: Bearer <jwt>
```

行为：

- 校验 run 归属或访问权限
- 设置 `abort_requested`
- WAITING tool calls 转为 `CANCELLED`
- RUNNING tool calls 通过 cancellation token 协作取消
- 必要时生成 synthetic tool result 闭合 transcript

### 3.4 Interrupt current turn

```http
POST /api/agent/runs/{runId}/interrupt
Authorization: Bearer <jwt>
```

V2 行为：

- 校验 run 归属或访问权限
- 写入 Redis control：`interrupt_requested=true`
- 当前 turn 主动结束，不再发起新的 provider 请求
- WAITING tool calls 转为 `CANCELLED + synthetic`
- RUNNING tool calls 通过 cancellation token 协作取消
- 通过 `ChildRunRegistry` 级联 interrupt active child runs
- run 进入 `PAUSED`，`nextActionRequired=user_input`

### 3.5 查询 trajectory

```http
GET /api/agent/runs/{runId}
Authorization: Bearer <jwt>
```

返回 run、messages、llm attempts、tool calls、tool results、events、progress。只能查询当前用户有权限访问的 run。

## 4. Redis 数据模型

V1a 使用 Redis 单实例，但 key 使用 hash tag 形式，方便后续 Redis Cluster：

```text
agent:{run:<runId>}:meta
agent:{run:<runId>}:queue
agent:{run:<runId>}:tools
agent:{run:<runId>}:tool-use-ids
agent:{run:<runId>}:leases
agent:{run:<runId>}:confirm-tokens
agent:{run:<runId>}:continuation-lock
agent:rate-limit:user:<userId>:runs-per-min
agent:rate-limit:user:<userId>:tokens-per-day
```

| Key | 类型 | 内容 |
|---|---|---|
| `meta` | HASH | run 控制状态：max_parallel、abort_requested、deadline |
| `queue` | ZSET | score=`seq`，member=`toolCallId` |
| `tools` | HASH | field=`toolCallId`，value=`ToolCallRuntimeState` JSON |
| `tool-use-ids` | HASH | provider `toolUseId` 去重 |
| `leases` | ZSET | score=`leaseUntilMs`，member=`toolCallId` |
| `confirm-tokens` | HASH | dry-run 写操作生成的 confirm token，绑定 userId/toolName/argsHash/expiresAt |
| `continuation-lock` | STRING | 同一 run 的 continuation 互斥，TTL 5 分钟 |
| `rate-limit:user:<userId>:runs-per-min` | STRING/计数器 | 每用户每分钟 run 限流 |
| `rate-limit:user:<userId>:tokens-per-day` | STRING/计数器 | 每用户每日 token 预算 |

`ToolCallRuntimeState`：

```json
{
  "runId": "run_123",
  "toolCallId": "tc_1",
  "seq": 1,
  "toolUseId": "call_1",
  "rawToolName": "cancelOrder",
  "toolName": "cancel_order",
  "argsJson": "{}",
  "isConcurrent": false,
  "status": "WAITING",
  "attempt": 0,
  "leaseToken": null,
  "leaseUntilMs": null,
  "workerId": null,
  "cancelReason": null,
  "resultJson": null,
  "errorJson": null
}
```

Redis TTL：

- active run 不设置短 TTL
- run terminal 后设置 TTL，例如 24 小时
- 长期追溯依赖 MySQL，不依赖 Redis

## 5. 调度语义

状态：

```java
public enum ToolStatus {
    WAITING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED
}

public enum CancelReason {
    USER_ABORT,
    RUN_ABORTED,
    TOOL_TIMEOUT,
    PRECHECK_FAILED,
    EXECUTOR_REJECTED,
    LEASE_EXPIRED
}
```

不引入 `TOMBSTONED`。取消类事件统一落到 `CANCELLED + cancelReason`；synthetic 只存 MySQL result trace：

```text
agent_tool_result_trace.synthetic = true
```

调度规则：

- 如果存在 `RUNNING isConcurrent=false`，不启动任何新工具
- terminal 状态跳过
- `RUNNING isConcurrent=true` 跳过，继续扫描
- `WAITING isConcurrent=true` 在容量允许时启动
- `WAITING isConcurrent=false` 只有当前没有任何 RUNNING，且本轮未启动工具时才能启动
- `isConcurrent=false` 后面的工具不能越过它

示例：

```text
A query_order(concurrent), B query_order(concurrent), C cancel_order(exclusive), D query_order(concurrent)
```

执行：

```text
batch 1: A, B
batch 2: C
batch 3: D
```

## 6. Java 组件设计

### 6.1 AgentLoop

`AgentLoop` 是 harness 控制循环：

```java
public interface AgentLoop {
    AgentRunResult run(AgentRunRequest request, AgentEventSink sink);

    AgentRunResult continueRun(String runId, UserMessage message, AgentEventSink sink);
}

public record AgentRunRequest(
    String userId,
    List<UserMessage> messages,
    Set<String> allowedToolNames,
    LlmParams llmParams
) {}

public record UserMessage(
    String content,
    Map<String, Object> metadata
) {}

public record LlmParams(
    String model,
    Double temperature,
    Integer maxTokens,
    Integer maxTurns,
    Integer timeoutMs
) {}

public record AgentRunResult(
    String runId,
    RunStatus status,
    String finalText,
    String errorMessage,
    String nextActionRequired
) {}

public interface AgentEventSink {
    void onTextDelta(TextDeltaEvent event);

    void onToolUse(ToolUseEvent event);

    void onToolProgress(ToolProgressEvent event);

    void onToolResult(ToolResultEvent event);

    void onFinal(FinalEvent event);

    void onError(ErrorEvent event);

    void onPing();
}

public record TextDeltaEvent(String runId, String attemptId, String delta) {}

public record ToolUseEvent(String runId, String toolUseId, String toolName, String argsJson) {}

public record ToolProgressEvent(String runId, String toolCallId, String stage, String message, Integer percent) {}

public record ToolResultEvent(String runId, String toolUseId, ToolStatus status, String resultJson, String errorJson) {}

public record FinalEvent(String runId, RunStatus status, String finalText, String nextActionRequired) {}

public record ErrorEvent(String runId, String message, String errorType) {}
```

`run(...)` 只创建新 run；`continueRun(...)` 推进已有 run，供 `POST /api/agent/runs/{runId}/messages` 使用。Continuation 必须先校验 run 归属、run 状态和 continuation lock。

这些支撑类型是 AgentLoop 的接口契约，不是数据库模型。`userId` 由 Controller 从 JWT/header 注入，不能信任请求体传入的 userId。

核心流程：

```text
create run
  ↓
append user message
  ↓
for turn in 1..maxTurns:
  build prompt envelope
  TranscriptPairValidator.validate(messages)
  call DeepSeek stream
  AttemptBuffer collect text/tool calls
  if attempt failed: fail run
  commit assistant message/tool calls
  if no tool calls: final
  ToolRuntime.onToolUse for each call
  ToolResultWaiter polling Redis
  append tool result messages
  continue
```

约束：

- `maxTurns` 默认 10
- run wallclock timeout 5 分钟
- 每次 provider 请求前必须校验 transcript pairing
- provider attempt 成功前不得执行 tool
- tool progress 只走 SSE，不进入 LLM messages
- event/progress 写失败不能影响 AgentLoop 主链路
- 如果 tool result 包含 `actionStatus=PENDING_CONFIRM`，AgentLoop 将 run 标记为 `WAITING_USER_CONFIRMATION`，发送 `final(status=WAITING_USER_CONFIRMATION)`，然后停止本次 SSE；用户通过 continuation endpoint 继续

### 6.2 DeepSeek Provider Adapter

V1a 只有一个 provider：

```java
public interface LlmProviderAdapter {
    LlmProviderType type();

    Stream<LlmStreamEvent> streamChat(LlmChatRequest request);
}

public final class DeepSeekProviderAdapter implements LlmProviderAdapter {
    // baseUrl = https://api.deepseek.com/v1
    // model = deepseek-chat
}
```

请求体：

```json
{
  "model": "deepseek-chat",
  "messages": [],
  "tools": [],
  "tool_choice": "auto",
  "temperature": 0.2,
  "max_tokens": 4096,
  "stream": true
}
```

`ProviderCompatibilityProfile`：

```java
public interface ProviderCompatibilityProfile {
    LlmProviderType type();

    ProviderToolsPayload toProviderTools(Collection<ToolSchema> schemas);

    List<Map<String, Object>> toProviderMessages(List<LlmMessage> messages);

    ToolCallIdPolicy toolCallIdPolicy();

    TokenEstimator tokenEstimator();
}
```

V1a 只有 DeepSeek profile，但保留该接口是为了把 provider 方言隔离在边界内。

V1a provider retry：

- 网络错误、HTTP 429、HTTP 5xx、stream 建连前失败：最多 2 次指数退避重试，等待 200ms、800ms
- stream 已经开始后中断：不重试同一请求，避免重复输出和重复 tool_call；当前 attempt failed
- retry 仍属于同一个 turn 的同一个 logical attempt，只有成功完整返回后才进入 AttemptCommitter
- 失败重试的错误摘要写入 `agent_llm_attempt.extras` 或 `agent_event`

V2 provider 演进：

```java
public interface LlmProviderAdapterRegistry {
    LlmProviderAdapter getRequired(LlmProviderType providerType);
}

public interface ProviderFallbackPolicy {
    Optional<LlmProviderType> fallbackFor(
        LlmProviderType failedProvider,
        LlmProviderError error,
        AttemptCommitState commitState
    );
}
```

V2 provider 列表：

| Provider | Adapter | Profile | 说明 |
|---|---|---|---|
| DeepSeek | `DeepSeekProviderAdapter` | `DeepSeekCompatibilityProfile` | V1a 已有，V2 可作为主或备 |
| Qwen | `QwenProviderAdapter` | `QwenCompatibilityProfile` | V2 新增，OpenAI-compatible streaming |

V2 不接入 GLM。

Provider fallback 的安全边界：

```text
call primary provider
  ↓
如果建连前失败、429、5xx、网络错误
  ↓
检查 attempt 还没有 committed assistant message / tool call
  ↓
按 ProviderFallbackPolicy 切换到 fallback provider
  ↓
重新构建 provider request
  ↓
重新执行 TranscriptPairValidator
```

不允许 fallback 的场景：

- stream 已经开始并产生 tool call delta
- assistant message 已经 committed
- tool call 已经写入 MySQL 或 Redis
- 错误是 schema 400、权限 401/403、请求参数非法

这样做的原因是 provider fallback 的真正风险不是“再试一次”，而是把旧 provider 的 partial `toolUseId`、partial arguments 或 provisional text 混入新请求，导致 transcript 与工具执行状态不一致。

### 6.3 PromptAssembler

```java
public interface PromptAssembler {
    PromptEnvelope assemble(PromptAssemblyRequest request);
}

public record PromptEnvelope(
    List<LlmMessage> messages,
    List<ToolSchema> toolSchemas,
    String materializedSystemPrompt,
    String systemPromptHash,
    String toolSchemaHash
) {}
```

V1a system prompt：

```text
default system prompt
+ user info prompt
+ tool-use guidance
+ business tool schema text snapshot
```

规则：

- default system prompt 是全局固定模板
- user info 来自 `UserProfileStore`
- tool-use guidance 描述何时查询、何时写操作、何时要求用户确认
- tool schema text snapshot 来自每个工具自己的 `Tool.schema()`
- provider `tools` 字段也来自 `Tool.schema()`，但由 provider profile 转换成结构化 payload
- materialized system prompt 写入 `agent_message(role=SYSTEM)`
- `systemPromptHash / toolSchemaHash` 写入 run 和 llm attempt

V2 system prompt 增加 skill preview：

```text
default system prompt
+ user info prompt
+ skill preview(name + description)
+ tool-use guidance
+ business tool schema text snapshot
```

完整 skill 内容不进入初始 prompt。模型需要时必须调用 `skill_view`，这样可以把上下文预算留给当前业务任务，也能让 trajectory 记录模型何时、为什么加载了某个 skill。

### 6.4 Tool

V1a 的 Tool 接口简化为三段：

```java
public interface Tool {
    ToolSchema schema();              // LLM-facing + runtime metadata 合一

    ToolValidation validate(ToolUseContext ctx, ToolUse use);

    ToolTerminal run(
        ToolExecutionContext ctx,
        StartedTool running,
        CancellationToken token
    ) throws Exception;
}

public record ToolSchema(
    String name,
    String description,
    String parametersJsonSchema,
    boolean isConcurrent,
    boolean idempotent,
    Duration timeout,
    int maxResultBytes,
    List<String> sensitiveFields
) {}

public record ToolValidation(
    boolean accepted,
    String normalizedArgsJson,
    String errorJson
) {}
```

`AbstractTool` 使用模板方法封装通用 lifecycle：

```text
execute(ctx, toolUse)
  ↓
validate(ctx, toolUse)
  ↓
if rejected: return FAILED synthetic-capable terminal
  ↓
run(ctx, toolCall, token)
  ↓
mask PII
  ↓
enforce maxResultBytes
  ↓
return ToolTerminal
```

业务工具只关心：

- schema 怎么描述给模型
- 参数如何校验和规范化
- 如何调用已有 service bean / REST API
- 返回什么业务结果

业务工具不关心：

- Redis lease
- MySQL trajectory
- transcript pairing
- SSE event 格式
- PII 脱敏细节
- 输出大小截断

### 6.5 ToolRegistry

每个工具是 Spring Bean：

```java
public final class ToolRegistry {
    private final Map<String, Tool> toolsByCanonicalName;

    public ToolRegistry(List<Tool> tools) {
        this.toolsByCanonicalName = buildIndex(tools);
    }

    public Tool resolve(String rawName) {
        return toolsByCanonicalName.get(normalize(rawName));
    }
}
```

normalize 规则：

- lowercase
- 去掉 `_`
- 去掉 `-`
- 去掉空格

示例：

```text
query_order -> queryorder
query-order -> queryorder
Query Order -> queryorder
```

V1a 不做 fuzzy match。找不到工具时，生成 `FAILED` tool result，让模型下一轮自我修正。

### 6.6 业务 Tool 示例

V1a 内置 mock business module，保证 demo 不依赖外部业务系统也能跑通。业务工具通过 `BusinessClient` 调用业务能力；V1a 的 `BusinessClient` 实现直接调用 in-process mock service，后续可以替换为 REST client。

最小业务域：

```java
public record Order(
    String orderId,
    String userId,
    OrderStatus status,
    Instant createdAt,
    BigDecimal amount,
    String itemName
) {}

public enum OrderStatus {
    CREATED,
    PAID,
    SHIPPED,
    CANCELLED
}

public record Profile(
    String userId,
    String displayName,
    String phone,
    String email,
    String address
) {}
```

订单取消规则：

- `CREATED / PAID` 可以取消
- `SHIPPED / CANCELLED` 不可取消
- 只能操作当前 `userId` 可见订单
- 第一次调用 `cancel_order` 只能 dry-run，不能直接产生副作用

Seed data：

- 当前用户昨天有一个 `PAID` 订单，可取消
- 当前用户有一个 `SHIPPED` 订单，不可取消
- 其他用户有订单，用于验证越权不可见

业务 adapter 接口：

```java
public interface OrderClient {
    List<Order> queryOrders(String userId, OrderQuery query);

    CancelPreview previewCancelOrder(String userId, String orderId);

    CancelResult cancelOrder(String userId, String orderId);
}

public interface ProfileClient {
    Profile getProfile(String userId);

    ProfileUpdatePreview previewUpdateProfile(String userId, ProfilePatch patch);

    ProfileUpdateResult updateProfile(String userId, ProfilePatch patch);
}

@Component
@Profile("demo")
public final class InProcessOrderClient implements OrderClient {
    // delegates to MockBusinessModule
}

@Component
@Profile("prod")
public final class RestOrderClient implements OrderClient {
    // delegates to existing business REST API
}
```

AgentLoop、ToolRuntime 和 trajectory 只依赖 `OrderClient / ProfileClient` 接口，不依赖 mock 实现。这样 demo 自包含，迁移真实业务时只换 adapter。

#### query_order

```text
name = "query_order"
isConcurrent = true
idempotent = true
timeout = 5s
maxResultBytes = 32KB
```

能力：

- 查询当前用户可见订单
- 支持日期、状态、关键词、金额范围过滤
- 只读，不产生业务副作用

#### cancel_order

```text
name = "cancel_order"
isConcurrent = false
idempotent = false
timeout = 10s
maxResultBytes = 16KB
```

能力：

- 取消指定订单
- 必须 dry-run / confirm 两段式
- validate 阶段校验订单是否属于当前用户、是否可取消

#### update_profile

```text
name = "update_profile"
isConcurrent = false
idempotent = false
timeout = 10s
maxResultBytes = 16KB
```

能力：

- 更新用户资料
- 必须 dry-run / confirm 两段式
- 输出前脱敏旧值和新值中的 PII

dry-run / confirm 的状态语义：

```text
tool terminal status = SUCCEEDED
tool result.actionStatus = PENDING_CONFIRM
run status = WAITING_USER_CONFIRMATION
```

`PENDING_CONFIRM` 不是 `ToolStatus`。它是业务动作状态，表示工具 dry-run 成功、真实写操作尚未执行。真实写操作必须等用户通过 `POST /api/agent/runs/{runId}/messages` 追加确认消息后，由模型再次调用带 `confirmToken` 的工具完成。

`confirmToken` 必须由服务端生成并写入 Redis：

```text
agent:{run:<runId>}:confirm-tokens[confirmToken]
  = {userId, toolName, argsHash, expiresAt}
```

confirm 阶段执行真实写操作前必须校验：

- token 存在且未过期
- token 属于当前 run 和 user
- token 绑定的 toolName 与当前工具一致
- 当前参数 hash 与 dry-run 参数 hash 一致
- 同一业务动作重复 dry-run 时，生成新的 confirmToken，并删除旧 token

`argsHash` 计算规则：

```text
argsHash = SHA-256(canonicalJson(args minus {"confirmToken", "dryRun"}))
```

`canonicalJson` 要求：

- object key 按字典序排序
- 去掉无意义空白
- 数字、布尔、null 使用 JSON 标准表示
- 数组保留原顺序

dry-run 和 confirm 阶段都按同一规则计算 hash。这样 `{orderId:"O1",dryRun:true}` 与 `{orderId:"O1",confirmToken:"ct_x"}` 可以绑定到同一个业务动作。

### 6.7 ToolRuntime / RedisToolStore

```java
public interface ToolRuntime {
    void onToolUse(String runId, ToolCall call);
}

public interface RedisToolStore {
    boolean ingestWaiting(String runId, ToolCall call);

    List<StartedTool> schedule(String runId);

    boolean complete(StartedTool running, ToolTerminal terminal);

    List<ToolTerminal> cancelWaiting(String runId, CancelReason reason);

    boolean abort(String runId, String reason);
}
```

`ToolRuntime.onToolUse`：

```text
ingestWaiting
  ↓
schedule
  ↓
submit startable tools to executor
```

`complete` 成功后再次触发 schedule，推进队列。

### 6.8 Executor 与 Lease Reaper

Executor：

- bounded thread pool
- bounded queue
- 拒绝时调用 Redis release/cancel，不能丢任务
- 每个 tool run 携带 cancellation token
- 每个 tool run 定期上报 progress，但 progress 写失败不影响工具结果

Lease reaper：

```text
每 10 秒扫描 leases
  ↓
leaseUntilMs < now
  ↓
RUNNING 且 leaseToken/attempt 匹配
  ↓
如果 ToolSchema.idempotent=true：WAITING
否则：CANCELLED + cancelReason=LEASE_EXPIRED + synthetic result
```

V1a 最小 reaper 的目标不是完美恢复外部副作用，而是避免 run 永久悬挂。业务工具默认 `idempotent=false`；只有只读或天然幂等工具才能显式声明 `idempotent=true`。例如 `query_order` 可重试，`cancel_order` 和 `update_profile` 不自动重试。

### 6.9 核心 Model

V1a 删除 `ToolUse / ToolCallDraft / StartTool` 三层 record，但不把所有字段塞进一个对象。模型分三层：

```text
ToolCall:
  稳定调用身份和参数，写 MySQL agent_tool_call_trace

ToolCallRuntimeState:
  Redis 运行态，包含 status、attempt、lease、cancelReason

ToolTerminal:
  终态结果，写 Redis terminal state 与 MySQL agent_tool_result_trace
```

`ToolCall`：

```java
public record ToolCall(
    String runId,
    String toolCallId,
    long seq,
    String toolUseId,
    String rawToolName,
    String toolName,
    String argsJson,
    boolean isConcurrent,
    boolean precheckFailed,
    String precheckErrorJson
) {}
```

`Tool` 接口中的 `ToolUse` 只表示 provider parser 刚解析出的临时输入，不进入 Redis/MySQL，不作为 runtime 状态模型。进入 validate 之后，系统立即生成 `ToolCall`；后续 WAITING / RUNNING 状态由 `ToolCallRuntimeState` 承载，terminal 结果由 `ToolTerminal` 承载。

`ToolCallRuntimeState`：

```java
public record ToolCallRuntimeState(
    ToolCall call,
    ToolStatus status,
    int attempt,
    String leaseToken,
    Instant leaseUntil,
    String workerId,
    CancelReason cancelReason
) {}
```

`StartedTool` 是 `schedule.lua` 返回给 executor 的轻量执行凭证：

```java
public record StartedTool(
    ToolCall call,
    int attempt,
    String leaseToken,
    Instant leaseUntil,
    String workerId
) {}

public record ToolTerminal(
    String toolCallId,
    ToolStatus status,
    String resultJson,
    String errorJson,
    CancelReason cancelReason,
    boolean synthetic
) {}
```

`Tool.run` 和 `RedisToolStore.complete` 都必须使用 `StartedTool`，因为 complete CAS 需要 `attempt + leaseToken`。不要把这些 lease 字段塞回 `ToolCall`，否则稳定调用身份和运行态会重新耦合。

V1a `workerId` 生成规则：

```java
"jvm-" + ManagementFactory.getRuntimeMXBean().getName()
```

该字段主要为 V2 多实例调度预留；V1a 单 JVM 也写入，便于日志和 trajectory 对齐。

不同阶段的数据归属：

| 阶段 | 关键字段 |
|---|---|
| provider parsed | runId、seq、toolUseId、rawToolName、argsJson |
| validated | toolCallId、toolName、isConcurrent、precheck fields |
| WAITING | `ToolCallRuntimeState.status=WAITING` |
| RUNNING | `attempt、leaseToken、leaseUntil、workerId` |
| terminal | terminal result 写 Redis 与 MySQL |

消息模型：

```java
public record LlmMessage(
    String messageId,
    MessageRole role,
    String content,
    List<ToolCallMessage> toolCalls,
    String toolUseId,
    Map<String, Object> extras
) {}
```

V1a 不使用 summary role；后续 summary metadata 放 `extras`。

### 6.10 MySQL Trajectory Store

关键表：

```sql
create table agent_run (
  run_id varchar(64) primary key,
  user_id varchar(64) not null,
  status varchar(32) not null,
  provider varchar(32) not null,
  model varchar(128) not null,
  max_turns int not null,
  wallclock_deadline_at datetime(3) not null,
  system_prompt_hash varchar(128) not null,
  tool_schema_hash varchar(128) not null,
  created_at datetime(3) not null,
  finished_at datetime(3) null
);

-- V2 child run link migration:
alter table agent_run add column parent_run_id varchar(64) null;
alter table agent_run add column parent_tool_call_id varchar(64) null;
alter table agent_run add column agent_type varchar(64) null;
alter table agent_run add column parent_link_status varchar(32) null;

create table agent_message (
  message_id varchar(64) primary key,
  run_id varchar(64) not null,
  turn int not null,
  role varchar(32) not null,
  content mediumtext null,
  tool_calls json null,
  tool_use_id varchar(128) null,
  extras json null,
  created_at datetime(3) not null,
  index idx_message_run_turn (run_id, turn, created_at)
);

-- agent_message.tool_calls 只保存 provider replay 所需的最小信息：
-- [{"toolUseId":"call_1","toolName":"query_order"}]
-- canonical args、raw tool name、precheck、isConcurrent、seq 等以 agent_tool_call_trace 为准。

create table agent_llm_attempt (
  id bigint primary key auto_increment,
  run_id varchar(64) not null,
  turn int not null,
  attempt_no int not null,
  provider varchar(32) not null,
  model varchar(128) not null,
  status varchar(32) not null,
  finish_reason varchar(64) null,
  error_type varchar(128) null,
  error_message text null,
  partial_text mediumtext null,
  partial_tool_calls json null,
  input_tokens int null,
  output_tokens int null,
  system_prompt_hash varchar(128) not null,
  tool_schema_hash varchar(128) not null,
  started_at datetime(3) not null,
  finished_at datetime(3) null,
  unique key uk_llm_attempt (run_id, turn, attempt_no)
);

-- finish_reason 取值：STOP / TOOL_CALLS / LENGTH / CONTENT_FILTER / ERROR

create table agent_tool_call_trace (
  id bigint primary key auto_increment,
  run_id varchar(64) not null,
  tool_call_id varchar(64) not null,
  tool_use_id varchar(128) not null,
  seq_no bigint not null,
  raw_tool_name varchar(256) not null,
  tool_name varchar(256) not null,
  args_json json not null,
  is_concurrent boolean not null,
  precheck_failed boolean not null default false,
  precheck_error_json json null,
  created_at datetime(3) not null,
  unique key uk_tool_call (run_id, tool_call_id),
  index idx_tool_use (run_id, tool_use_id)
);

create table agent_tool_result_trace (
  id bigint primary key auto_increment,
  run_id varchar(64) not null,
  tool_call_id varchar(64) not null,
  tool_use_id varchar(128) not null,
  status varchar(32) not null,
  result_json json null,
  error_json json null,
  synthetic boolean not null default false,
  cancel_reason varchar(64) null,
  created_at datetime(3) not null,
  unique key uk_tool_result (run_id, tool_call_id)
);
```

异步表：

```sql
create table agent_event (
  id bigint primary key auto_increment,
  run_id varchar(64) not null,
  event_type varchar(64) not null,
  payload json not null,
  created_at datetime(3) not null,
  index idx_event_run (run_id, id)
);

create table agent_tool_progress (
  id bigint primary key auto_increment,
  run_id varchar(64) not null,
  tool_call_id varchar(64) not null,
  tool_use_id varchar(128) not null,
  stage varchar(128) not null,
  message text not null,
  percent int null,
  created_at datetime(3) not null,
  index idx_tool_progress (run_id, tool_call_id, id)
);
```

写入策略：

关键路径同步写，失败时 V1a fail closed：

- `agent_run`
- `agent_message`
- `agent_llm_attempt`
- `agent_tool_call_trace`
- `agent_tool_result_trace`

异步批量写：

- `agent_event`
- `agent_tool_progress`

异步实现：

```text
Disruptor / ring buffer
  ↓
flush interval = 1s
flush batch size = 100
  ↓
batch insert MySQL
```

降级策略：

- 队列满 80% 触发 WARN 日志和 metric
- 队列满 100% 直接丢弃 event/progress
- event/progress 可丢，不能因为它们写失败拖累 run

AttemptCommitter 提交顺序：

```text
1. 对成功 attempt 的 raw tool calls 做 ToolRegistry.resolve + Tool.validate
2. 生成 ToolCall，记录 canonical toolName、isConcurrent、precheck 信息
3. MySQL 事务写 assistant message + agent_tool_call_trace
4. 对 precheck failed 的 tool call 同事务写 FAILED tool result，用于闭合 transcript
5. MySQL commit 成功
6. 仅对 precheck passed 的 tool call 执行 Redis ingest WAITING
7. ingest 成功后触发 schedule
```

失败处理：

- MySQL 事务失败：不写 Redis，不执行工具，run fail closed
- Redis ingest 失败：写 synthetic FAILED tool result，关闭 assistant tool call；run 可进入 FAILED，或让下一轮 LLM 看到工具错误
- MySQL tool result 写失败：run fail closed，abort Redis 中未完成工具
- JVM 在 MySQL commit 后、Redis ingest 前崩溃：由启动 repair 关闭 orphan tool call

`RunRepairService`：

```text
应用启动
  ↓
扫描 status in (CREATED, RUNNING, WAITING_USER_CONFIRMATION) 且超过安全窗口的 run
  ↓
查找 assistant tool_call 已存在但缺少 matching tool result 的记录
  ↓
写 synthetic FAILED tool result
  ↓
将 run 标记 FAILED 或 FAILED_RECOVERED
  ↓
写 repair event / log
```

V1a 不引入 outbox，所以 repair 是必要的最小一致性补偿。它不追求恢复业务副作用，只保证 transcript 不永久 orphan，run 不永久悬挂。

### 6.11 Tool Result Waiter

V1a 使用短间隔 polling Redis：

```java
public interface ToolResultWaiter {
    List<ToolTerminal> awaitResults(
        String runId,
        List<String> toolUseIds,
        Duration timeout
    );
}
```

策略：

- polling interval 100-300ms
- 所有 toolUseId terminal 后返回
- timeout 时调用 abort/cancel，把缺失 tool call 转成 `CANCELLED + synthetic result`
- 不返回孤儿 tool result

V2 可改 Redis Pub/Sub。

### 6.12 分布式调度（多实例演进）

V1a 单容器，所有 schedule、executor、reaper 在同一个 JVM。

多实例演进方案作为设计储备，V2 视需要实现。

Active Runs Set：

```text
Redis SET: agent:active-runs
```

触发 schedule 的三个时机：

```text
1. ToolRuntime.onToolUse 后立即在本实例触发
2. complete CAS 成功后立即在本实例触发
3. 每实例后台 sweeper 每 2s 兜底扫描 active-runs
```

去重保证：

- `schedule.lua` 原子执行
- 两实例同时 schedule 同一 run，只有一方能把 WAITING 改成 RUNNING 并拿到 lease
- 工具执行 affinity = 拿到 lease 的实例
- 不需要额外分布式锁

Sweeper 职责边界：

- `ActiveRunSweeper` 只允许调用 `RedisToolStore.schedule(runId)`
- `ActiveRunSweeper` 不得调用 `AgentLoop.run`、`AgentLoop.continueRun` 或任何 `LlmProviderAdapter`
- `ActiveRunSweeper` 只负责推动 Redis 中已经 ingest 的 WAITING tool 进入 RUNNING
- 任何会产生新 LLM 调用或新 assistant message 的动作，必须由持有 continuation lock 的实例发起
- 实现侧要求 sweeper 路径不依赖 provider 包，编译期保证不会误调用

工具结果通知：

```text
V1a:
  ToolResultWaiter polling Redis

V2:
  PUBLISH agent:run:<runId>:result <toolUseId>
  SUBSCRIBE 替代 polling
```

为什么 V1a 不实现多实例：

- 求职 demo 更需要清晰闭环，而不是分布式复杂度
- Redis Lua 已经保留多实例调度原子性
- 单容器更容易本地演示、录屏和部署

### 6.13 安全边界

认证与权限：

- 外部已有系统完成认证
- 本系统从 HTTP header / JWT 取 `userId`
- `allowedToolNames` 由调用方传入或从外部权限系统查询
- 本系统不实现 RBAC
- ToolRegistry 只暴露 allowed tool subset

有效工具集合：

```text
effectiveAllowedTools =
  externalPermissionTools(userId)
  ∩ request.allowedToolNames
```

如果请求未传 `allowedToolNames`，则使用 `externalPermissionTools(userId)`。请求体只能收窄权限，不能放大权限。

Prompt Injection 防护：

- 永远不在 system prompt 拼接用户消息原文
- 所有 tool result 在 transcript 中包裹：

```xml
<tool_result name="query_order">
...
</tool_result>
```

- 外部业务数据被视为 data，不被视为 instruction
- 写操作类业务 tool 必须支持 dry-run / confirm 两段式

dry-run / confirm：

```text
第一次调用:
  cancel_order({orderId, dryRun:true})
  ↓
  返回 PENDING_CONFIRM + summary + confirmToken

用户确认后:
  cancel_order({orderId, confirmToken})
  ↓
  执行真实写操作
```

PII 脱敏：

- 在 `AbstractTool` 输出后统一执行
- 优先按字段名递归脱敏，例如 `phone / mobile / email / idCard / cardNo / token / apiKey`
- 工具可通过 `Tool.schema().sensitiveFields` 声明额外敏感字段
- 兜底 regex 只处理高置信 pattern，例如 `sk-[A-Za-z0-9]{20,}` 这类 API key
- 避免对整个 JSON 字符串做宽松 regex 扫描，减少订单号、金额、普通 ID 被误伤
- `update_profile` dry-run 给用户确认的 SSE summary 可以显示旧值末 4 位和新值完整值，帮助用户确认操作
- 进入 LLM transcript / MySQL / log 的 tool result 仍必须脱敏；用户可见 confirmation summary 和模型可见 transcript 要分开生成
- 命中脱敏写 metric：

```text
agent.pii.masked{type}
```

Secret 管理：

- provider api key、DB 密码只从环境变量读
- 启动时校验存在
- 永不进入 prompt / log / MySQL

限流：

- Redis token bucket
- 每用户每分钟 5 run
- 每用户每天 100k token
- 单 run wallclock 5 分钟硬超时

### 6.14 可观测性

Metrics 使用 Micrometer + Prometheus，通过 `/actuator/prometheus` 暴露。

业务层：

```text
agent.run.started{user_tier}                counter
agent.run.completed{status}                 counter
agent.run.duration                          histogram
agent.turn.per_run                          histogram
```

LLM 层：

```text
agent.llm.attempt.total{provider,status}    counter
agent.llm.attempt.duration{provider}        histogram
agent.llm.token{provider,model,type}        counter
```

工具层：

```text
agent.tool.executed{tool,status}            counter
agent.tool.duration{tool}                   histogram
agent.tool.queue_wait{tool}                 histogram
```

基础设施：

```text
agent.redis.lua.duration{script}            histogram
agent.mysql.write.duration{table}           histogram
agent.executor.queue_depth                  gauge
agent.event_queue.depth                     gauge
agent.active_runs                           gauge
```

SSE：

```text
agent.sse.connections.active                gauge
agent.sse.events.sent{event_type}           counter
agent.sse.client_disconnect{reason}         counter
```

Logging：

- 结构化 JSON
- MDC 注入：`runId`、`turnNo`、`attemptNo`、`userId`、`toolCallId`
- 关键事件 INFO
- 可恢复异常 WARN
- 不可恢复异常 ERROR
- 日志输出前统一 secret / PII mask

Tracing：

- OpenTelemetry 可选
- span 层级：

```text
HTTP
  -> AgentRun
     -> Turn
        -> LlmAttempt
        -> ToolCall
```

Health：

```text
/actuator/health
  redis
  mysql
  provider
  executor
  reaper
```

### 6.15 Graceful Shutdown

Docker stop / SIGTERM 时：

```text
1. AgentController 拒绝新 run 和 continuation 请求，返回 503
2. 已有 run 最多等待 30 秒自然完成
3. 超时仍未完成的 run 执行 abort
4. WAITING tool call 转 CANCELLED synthetic
5. RUNNING tool call 发送 cancellation token
6. 主动释放本 JVM 持有的 lease 或缩短 leaseUntil
7. SSE 连接发送 error event 后关闭
8. 关闭 executor 与异步 event writer
```

V1a 是单容器，因此释放 lease 主要用于重启后的 reaper 更快收敛；V2 多实例时，其他实例可通过 Redis lease 接管可重试工具。

### 6.16 演示部署

V1a 必须可以用一条命令启动本地 demo：

```text
docker compose up
```

服务拓扑：

```text
agent-app
  ├─ Spring Boot AgentLoop
  ├─ in-process mock business module
  └─ business seed data loader

mysql
  └─ trajectory tables

redis
  └─ active run / tool queue / lease / rate limit
```

启动要求：

- agent-app 启动时执行 schema migration
- mock business module 加载 seed data
- 启动健康检查必须覆盖 DeepSeek API key、MySQL、Redis、executor、reaper
- README 或脚本必须提供固定演示输入：“取消我昨天的那个订单”
- 演示路径必须覆盖 query_order、cancel_order dry-run、用户确认、confirm cancel、final answer

README 必须包含：

1. 一句话项目定位
2. 一张架构图
3. `docker compose up` 与 5 步演示流程
4. 关键设计取舍：单 provider、polling、dry-run、Redis 调度、MySQL trajectory
5. V2 演进规划
6. 已知限制

预估成本：

```text
典型 run:
  10 turn
  每 turn 约 4k input token + 1k output token
  约 50k token / run

用户日预算:
  100k token/day
  约 2 个重度 demo run
```

具体金额随 DeepSeek 官方价格变化，README 里只给估算公式，不硬编码长期价格。

## 7. Lua 脚本设计

V1a 需要 5 个 Lua 脚本：

| 脚本 | 作用 |
|---|---|
| `ingest.lua` | 去重并写入 WAITING |
| `schedule.lua` | 原子选择可启动工具并标记 RUNNING |
| `complete.lua` | CAS 写入 terminal 状态 |
| `release_running.lua` | executor reject / 本地启动失败时释放 RUNNING |
| `cancel_pending.lua` | abort/timeout 时把 WAITING 转为 CANCELLED synthetic |

### 7.1 ingest.lua

原子操作：

```text
if abort_requested:
  return error aborted

if toolUseId exists:
  return duplicate

HSET tool-use-ids toolUseId toolCallId
HSET tools toolCallId ToolCallRuntimeState(status=WAITING)
ZADD queue seq toolCallId
return inserted
```

### 7.2 schedule.lua

原子操作：

```text
if abort_requested:
  return []

runningExclusive = exists RUNNING where isConcurrent=false
if runningExclusive:
  return []

runningCount = count RUNNING
started = []

for toolCallId in queue by seq:
  state = HGET tools toolCallId

  if state terminal:
    continue

  if state RUNNING:
    continue

  if state WAITING and state.isConcurrent=true:
    if runningCount + len(started) < maxParallel:
      mark RUNNING with attempt+1, leaseToken, leaseUntil
      started.add(state)
      continue

  if state WAITING and state.isConcurrent=false:
    if runningCount == 0 and len(started) == 0:
      mark RUNNING with attempt+1, leaseToken, leaseUntil
      started.add(state)
    break

return started
```

### 7.3 complete.lua

CAS 条件：

```text
status == RUNNING
attempt == expectedAttempt
leaseToken == expectedLeaseToken
```

成功后：

```text
status = SUCCEEDED / FAILED / CANCELLED
resultJson / errorJson = terminal payload
ZREM leases toolCallId
HSET tools toolCallId state
return true
```

失败返回 false，不覆盖已有 terminal。

### 7.4 release_running.lua

用于 executor reject 或本地启动失败：

```text
if status == RUNNING and attempt/leaseToken match:
  status = WAITING
  leaseToken = null
  leaseUntil = null
  ZREM leases toolCallId
  return true
return false
```

如果 executor 明确不可恢复，也可以直接 complete `FAILED`。

### 7.5 cancel_pending.lua

用于 abort / timeout：

```text
for tool in queue:
  if status == WAITING:
    status = CANCELLED
    cancelReason = reason
    synthetic = true
    append terminal result

return synthetic results
```

RUNNING 工具优先通过 cancellation token 协作取消。run wallclock timeout 可以选择 includeRunning，把 RUNNING 也转 synthetic cancel，晚到 complete CAS 会失败。

## 8. 错误处理与边界

### 8.1 Provider stream 失败

失败 attempt 的 partial text / partial tool call 只写 `agent_llm_attempt`：

```text
partial_text
partial_tool_calls
status = FAILED
```

不会写 `agent_message`，不会 ingest Redis。

### 8.2 工具名不存在

V1a 不做 fuzzy repair。找不到工具时：

```text
assistant tool call 已经进入 transcript
  ↓
生成 FAILED tool result
  ↓
errorJson = {"type":"unknown_tool","allowedTools":[...]}
```

这样模型下一轮可以自我修正。

### 8.3 参数非法

`Tool.validate` 返回 rejected：

```text
status = FAILED
errorJson = {"type":"invalid_arguments", ...}
synthetic = false
```

如果 validate 在 Redis ingest 前执行失败，也仍要写 tool result，闭合 transcript。

### 8.4 工具执行超时

工具超时：

```text
complete CANCELLED
cancelReason = TOOL_TIMEOUT
synthetic = false 或 true，取决于工具是否实际开始执行
```

### 8.5 Run timeout

单 run wallclock 超过 5 分钟：

- 设置 run timeout
- abort Redis
- WAITING tool call 转 `CANCELLED + RUN_ABORTED + synthetic`
- RUNNING 尝试 cancellation token
- 必要时 includeRunning synthetic close
- SSE 返回 error/final cancelled

### 8.6 Redis / MySQL 错误

- Redis 错误：run fail closed
- MySQL 关键写错误：run fail closed，并 abort Redis
- MySQL event/progress 写错误：记录指标和日志，不影响 run

## 9. 配置建议

```yaml
agent:
  redis-key-prefix: agent
  max-parallel: 6
  max-scan: 256
  lease-ms: 90000
  reaper:
    enabled: true
    interval-ms: 10000
  agent-loop:
    max-turns: 10
    tool-result-timeout-ms: 90000
    run-wallclock-timeout-ms: 300000
    confirm-token-ttl-ms: 600000
    continuation-lock-ttl-ms: 300000
    shutdown-grace-period-ms: 30000
  executor:
    core-pool-size: 8
    max-pool-size: 32
    queue-capacity: 256
  llm:
    provider: deepseek
    deepseek:
      base-url: https://api.deepseek.com/v1
      api-key: ${DEEPSEEK_API_KEY}
      default-model: deepseek-chat
  rate-limit:
    runs-per-user-per-minute: 5
    tokens-per-user-per-day: 100000
  sse:
    ping-interval-ms: 15000
  event-queue:
    ring-buffer-size: 8192
    flush-interval-ms: 1000
    flush-batch-size: 100
  mysql:
    datasource:
      url: ${MYSQL_URL}
      username: ${MYSQL_USERNAME}
      password: ${MYSQL_PASSWORD}
  redis:
    url: ${REDIS_URL}
```

配置原则：

- provider key、DB 密码、Redis URL 只来自环境变量
- 启动时校验必填配置
- 默认配置适合 demo，不假装是大规模生产配置

## 10. 测试计划

### 10.1 单元测试

- `ToolCallAssembler` 能组装 DeepSeek streaming tool call delta
- `AttemptBuffer` 暂存 partial，不直接提交 Redis
- provider stream 失败时 partial tool call 不进入 Redis
- `AttemptCommitter` 成功路径写 MySQL assistant/tool_call，再 ingest Redis
- `TranscriptPairValidator` 能识别 orphan tool call / orphan tool result
- `ToolRegistry` 能规范化 `query_order / QueryOrder / query-order`
- `ToolRegistry` 启动时发现规范化名称冲突会 fail fast
- `AbstractTool` 必须调用 `validate`
- `AbstractTool` 会执行 PII 脱敏
- `AbstractTool` 会执行 `maxResultBytes` 限制
- `cancel_order` dry-run 返回 PENDING_CONFIRM
- `cancel_order` confirmToken 正确时才执行真实取消
- dry-run tool terminal status 为 SUCCEEDED，业务 result.actionStatus 为 PENDING_CONFIRM
- confirmToken 必须校验 runId、userId、toolName、argsHash 和过期时间
- PENDING_CONFIRM 会把 run 状态推进为 WAITING_USER_CONFIRMATION
- 用户拒绝确认时，confirmToken 清理，run 以 SUCCEEDED 结束并说明未执行写操作
- WAITING_USER_CONFIRMATION 超过 confirm token TTL 未确认时，run 进入 TIMEOUT；不复用 tool 的 cancelReason
- confirm 阶段调用不同 toolName 或不同 orderId 导致 argsHash 不匹配时，返回 FAILED tool result
- 同一订单重复 dry-run 时，生成新的 confirmToken，并使旧 token 失效
- `update_profile` dry-run / confirm 正确
- 限流命中后返回 429
- 异步 event queue 80%/100% 阈值行为正确
- `RunRepairService` 能关闭 assistant tool_call 已写但缺少 tool result 的 orphan run
- DeepSeek 建连前 429/5xx/网络错误会按 200ms、800ms 退避重试，stream 开始后中断不重试

### 10.2 Redis Lua 测试

- 重复 `toolUseId` 只 ingest 一次
- 连续 concurrent 工具同批启动
- concurrent + exclusive + concurrent 不越过屏障
- exclusive 独占执行
- 并发 schedule 同一个 run 只会有一个执行者拿到 lease
- 错误 leaseToken complete 返回 false
- 错误 attempt complete 返回 false
- abort 后 WAITING 转 CANCELLED synthetic
- release_running 能把 executor reject 的 RUNNING 放回 WAITING
- lease reaper 能处理过期 RUNNING

### 10.3 端到端测试

- HTTP SSE 能返回 `text_delta / tool_use / tool_progress / tool_result / final`
- `POST /api/agent/runs/{runId}/messages` 能继续 WAITING_USER_CONFIRMATION run
- 用户说“取消我昨天的订单”，完成 query -> dry-run cancel -> confirm cancel -> final
- DeepSeek tool call 闭环：LLM -> tool_result -> LLM final
- 工具失败后，模型能看到 tool error 并给出解释
- 单 run wallclock 超时后 abort + synthetic close
- MySQL 可按 `runId` 查询完整 trajectory
- 关键 MySQL 写失败时 run fail closed
- event/progress 写失败不影响 final
- PII 脱敏触发后 metric +1，输出已脱敏
- docker compose 启动后 seed data 可支撑固定演示场景
- graceful shutdown 会拒绝新请求、等待已有 run、超时 abort 并 synthetic close
- SSE 指标会记录连接数、事件发送数和客户端断开原因

## 11. V2 设计

V2 合并原后续阶段的候选能力，目标是在 V1a 已经跑通的业务型 AgentLoop 上补齐六个工程能力：

```text
多 provider
+ context compact
+ skill 渐进式加载
+ SubAgent
+ ToDo 短期计划
+ 多实例推进
```

V2 不接入 GLM，不引入知识库检索链路，不让外部框架接管 AgentLoop。

### 11.0 V2 里程碑拆分

V2 不作为一次性大包交付，而是按里程碑顺序推进：

| 里程碑 | 内容 | 周期估算 | 验收信号 |
|---|---|---:|---|
| V2.0 | Multi-provider（DeepSeek + Qwen）+ Context compact（spill / micro / summary） | 1-2 周 | DeepSeek 故障可 fallback Qwen；50K token 上下文不 fail closed；compact summary 保留 `businessFacts/toolFacts/openQuestions` |
| V2.1 | Skill 渐进式加载 + SubAgent / AgentTool | 2-3 周 | `/skillName` slash 注入工作；SubAgent 继承 tool/skill 但不继承 history；child run trajectory 可独立查询 |
| V2.2 | 多实例部署 + Pub/Sub + ToDo + Interrupt | 2 周 | 双实例并发 schedule 不重复执行；interrupt 写入 `PAUSED`；ToDo reminder 每 3 turn 注入但不污染对外 conversation |

每个里程碑独立做 hardening review，沿用 V1a 的主 agent 分发、sub agent 并行开发、`java-alibaba-review` 独立审核流程。里程碑之间不并行交付，避免同时改 provider、context、SubAgent、多实例运行态导致问题定位失焦。

### 11.1 V2 新增组件

| 组件 | 分层 | 职责 |
|---|---|---|
| `LlmProviderAdapterRegistry` | provider boundary | 按 provider type 查找 DeepSeek/Qwen adapter |
| `QwenProviderAdapter` | provider boundary | Qwen OpenAI-compatible streaming 接入 |
| `QwenCompatibilityProfile` | provider boundary | Qwen tools/messages/stream/usage/error 方言转换 |
| `ProviderFallbackPolicy` | policy | 判断 provider 错误是否允许 fallback |
| `ContextViewBuilder` | context | 从 MySQL trajectory 构建本次 provider message view |
| `ContextCompactor` | context | 编排 large spill、micro compact、summary compact |
| `LargeResultSpiller` | context | 大 tool result 逻辑占位 |
| `MicroCompactor` | context | 旧 tool result 占位压缩 |
| `SummaryCompactor` | context | 调用 LLM 生成 JSON summary |
| `CompactionStore` | trajectory | 记录 compactedMessageIds、token 前后变化、策略 |
| `SkillRegistry` | skill | 扫描并索引 Anthropic Agent Skills 格式目录 |
| `SkillListTool` | tool | 返回当前用户可访问 skill preview |
| `SkillViewTool` | tool | 渐进式读取 SKILL.md 或 skill 内文件 |
| `SkillCommandResolver` | skill/context | 解析用户消息中的 `/skillName`，校验预算并注入 transient skill view |
| `SkillPathResolver` | skill/security | 防止 `..`、绝对路径、符号链接逃逸 |
| `AgentTool` | tool/subagent | 暴露给模型，用于创建 child run |
| `SubAgentRegistry` | subagent | 注册 ExploreAgent 等具体子 Agent |
| `SubAgentRunner` | subagent | 创建 child run、同步等待 child result summary、处理超时和中断 |
| `SubAgentBudgetPolicy` | policy | 限制单 run child 数量与并发 child 数量 |
| `AgentExecutionBudget` | policy | 限制每个用户 turn 内 MainAgent/SubAgent 最多 30 次 LLM 调用 |
| `RunInterruptService` | runtime/control | 写入 interrupt control 信号，级联中断 child run 与工具执行 |
| `ChildRunRegistry` | runtime/control | 维护 parent run 与 child run 的运行期关系 |
| `TodoStore` | todo | Redis ToDo 状态读写 |
| `ToDoCreateTool` | tool/todo | 创建复杂任务步骤 |
| `ToDoWriteTool` | tool/todo | 更新步骤状态 |
| `TodoReminderInjector` | context | 每 3 turn 注入未完成 ToDo reminder |
| `ActiveRunSweeper` | runtime | 多实例兜底推进 active run |
| `ToolResultPubSub` | runtime | Redis Pub/Sub 通知 tool terminal result |

### 11.2 Provider Registry 与 Fallback

V2 的 provider 选择在 create run 时确定，并写入 `RunContext`：

```text
primaryProvider
fallbackProvider
model
providerOptions
```

Continuation 必须复用 run context，不能因为请求参数或默认配置变化而静默切换 provider。

Fallback 状态机：

```text
NEW_ATTEMPT
  ↓ call primary
PROVIDER_CONNECTING
  ↓ 可恢复错误且未产生 committed 内容
FALLBACK_SELECTED
  ↓ call fallback
PROVIDER_STREAMING
  ↓ success
COMMITTABLE
```

如果状态已经进入 `PROVIDER_STREAMING` 且出现 tool call delta，中断后只记录失败 attempt，不 fallback。原因是此时已经存在 provider 私有的 partial tool call id 和 partial arguments，切换 provider 会制造难以解释的 orphan 风险。

### 11.3 Context Compact

V2 把“原始历史”和“发送给 provider 的 view”分开：

```text
TrajectoryReader 读取完整 MySQL messages
  ↓
ContextViewBuilder 构建候选 provider view
  ↓
TranscriptPairValidator 校验原始配对
  ↓
ContextCompactor 按策略压缩
  ↓
TranscriptPairValidator 校验压缩后配对
  ↓
ProviderCompatibilityProfile 转换 provider messages
```

压缩顺序固定：

1. `LargeResultSpiller`：单个 tool result > 2000 token 时保留头 200 token、尾 200 token，中间插入：

```xml
<resultPath>trajectory://runs/{runId}/tool-results/{toolCallId}/full</resultPath>
```

2. `MicroCompactor`：总 context 达到 50000 token 时，将旧 tool result content 替换为：

```xml
<oldToolResult>Tool result is deleted due to long context</oldToolResult>
```

3. `SummaryCompactor`：仍超预算时，调用 LLM 生成 JSON summary，并用 `ASSISTANT + extras.compactSummary=true` 作为压缩后的历史 view。

Summary JSON：

```json
{
  "summaryText": "...",
  "businessFacts": [],
  "toolFacts": [],
  "openQuestions": [],
  "compactedMessageIds": []
}
```

字段含义：

- `summaryText`：给模型读的自然语言压缩叙述
- `businessFacts`：稳定业务事实，例如订单、商品、用户意图、售后状态；用于防止 compact 后丢失关键业务约束
- `toolFacts`：工具调用事实，例如已查询的数据、已执行的 dry-run、已确认或失败的操作；用于避免重复调用和误判状态
- `openQuestions`：尚未解决的问题、缺失信息、等待用户确认的点；用于让下一轮知道任务还卡在哪里
- `compactedMessageIds`：被 summary 覆盖的 message id；用于审计和回溯

保留规则：

- system prompt 永远保留
- 前三条非 system message 保留
- 最后三条 message 保留
- 如果最后三条不足 2000 token，可以继续向前保留，直到再多一条会超过 2000 token
- tool call 与 matching tool result 必须一起保留或一起进入 summary

`CompactionStore` 写入：

```text
runId
compactionId
strategy
beforeTokens
afterTokens
compactedMessageIds
createdAt
```

### 11.4 Skill 渐进式加载

Skill 目录使用 Anthropic Agent Skills 风格：

```text
skills/{skillName}/SKILL.md
skills/{skillName}/references/...
skills/{skillName}/scripts/...
```

`SKILL.md` frontmatter：

```yaml
---
name: java-alibaba-review
description: 按阿里巴巴 Java 开发规范审查 Java/Spring Boot 代码
---
```

初始 prompt 只放 preview：

```json
{"name":"java-alibaba-review","description":"按阿里巴巴 Java 开发规范审查 Java/Spring Boot 代码"}
```

`skill_list()`：

- schema 无参
- `userId` 从 `ToolUseContext` 注入
- 返回当前用户可访问 skill 的 preview 列表

`skill_view(skillName, skillPath?)`：

- 只有 `skillName` 时返回完整 `SKILL.md`
- 同时传 `skillPath` 时返回 skill 根目录内指定文件
- `SkillPathResolver` 必须校验路径不能逃逸 skill 根目录
- 返回内容作为普通 tool result，因此自动进入 trajectory、脱敏、大小限制和 context compact

Slash skill 加载：

```text
用户消息: /purchase-guide 帮我判断这件商品是否适合买
  ↓
SkillCommandResolver 解析 purchase-guide
  ↓
校验 skill 存在且当前用户可访问
  ↓
读取 skills/purchase-guide/SKILL.md
  ↓
作为 transient provider message 加入本轮 message view
```

注入格式：

```xml
<skill name="purchase-guide">
...SKILL.md content...
</skill>
```

规则：

- `/skillName` 触发的是本轮 provider view 注入，不改写 materialized system prompt
- `/skillName` 不作为普通 user message 持久化，但 skill access 写入 `agent_event`
- 同一条用户消息可引用多个 skill，按出现顺序加载
- slash skill 加载受 token budget 和单轮最大 skill 数限制
- 默认 `agent.skills.max-per-message=3`
- 默认 `agent.skills.max-token-per-message=8000`
- 超过数量或 token 预算时整条 SSE 请求 fail closed，HTTP 400 或 SSE `error` 使用错误码 `SKILL_BUDGET_EXCEEDED`
- 错误体必须包含命中的 skill 列表、预算值、实际 token 估算和超出量
- 禁止静默截断 skill；用户显式写了 `/skillName`，模型就必须完整看到该 skill，或者请求明确失败
- V2 内置 3 个业务 skill：`purchase-guide`、`return-exchange-guide`、`order-issue-support`

### 11.5 SubAgent / AgentTool

`AgentTool` 是提供给 LLM 的普通 tool。它的特殊性不在 tool 接口，而在 run 关系：

```text
parent assistant tool call
  ↓
AgentTool.run(task, systemPrompt, agentType)
  ↓
SubAgentRunner.createChildRun
  ↓
child AgentLoop 独立执行
  ↓
child result summary
  ↓
parent tool result
```

SubAgent 继承 MainAgent 的 tool/skill 能力集合，但不继承 MainAgent 的对话上下文。

继承内容：

- 继承 parent run 的 `effectiveAllowedTools`
- 继承 MainAgent 当前用户可访问的 skill preview
- 继承 `skill_list` / `skill_view` 能力
- 如果 MainAgent 在当前任务中通过 `/skillName` pin 了 skill，SubAgent task package 可以携带该 skill 内容

不继承内容：

- 不继承 MainAgent message history
- 不继承 MainAgent ToDo 状态
- 不继承 MainAgent compact 后的 provider view
- 不直接读取 MainAgent 的完整 trajectory 作为上下文

MainAgent 必须按任务显式传给 SubAgent：

```text
systemPrompt
task payload
allowedTools narrowing rule
availableSkills narrowing rule
maxTurns
tokenBudget
llmCallBudgetPerUserTurn = 30
parentRunId
parentToolCallId
```

这样设计的原因是：SubAgent 应该继承“能用什么能力”，但不应该继承“MainAgent 脑子里所有上下文”。它拿到完成子任务所需的最小任务包，完成后只把结果总结交回 MainAgent。

数据关系：

```text
agent_run.parent_run_id
agent_run.parent_tool_call_id
agent_run.agent_type
```

子 Agent 配置：

```java
public interface SubAgentProfile {
    String agentType();

    String defaultSystemPrompt();

    String renderSystemPrompt(SubAgentTask task);

    Set<String> allowedTools();

    int maxTurns();

    int tokenBudget();

    int llmCallBudgetPerUserTurn();
}
```

权限规则：

- child allowed tools 默认等于 parent effectiveAllowedTools，也可以被 MainAgent 按任务收窄
- child available skills 默认等于 parent 当前用户可访问 skill 集合，也可以被 MainAgent 按任务收窄
- child tool/skill 权限不能超过 parent
- ExploreAgent 默认只允许查询类工具和 skill 工具，不允许写业务工具
- child run 失败不会直接让 parent run 失败，而是返回 FAILED `AgentTool` result，由 parent 模型决定下一步

SubAgent 预算：

- `max-spawn-per-run=2`：整个 parent run 生命周期累计最多创建 2 个 child run
- `max-concurrent-per-run=1`：同一时刻最多 1 个 in-flight child run
- `spawn-budget-per-user-turn=2`：单个 user turn 内最多尝试 spawn 2 个 child run
- 计数真相源是 Redis `agent:{run:<runId>}:children` HASH
- 超过任一限制时，`AgentTool` 立即返回 `SUBAGENT_BUDGET_EXCEEDED` 的 FAILED tool result
- slot reserve 必须通过 `reserve_child.lua` 原子完成：同一 Lua 脚本内检查 `max-spawn-per-run`、`max-concurrent-per-run`、`spawn-budget-per-user-turn`，并写入 `agent:{run:<runId>}:children` HASH
- reserve 失败立即返回 `SUBAGENT_BUDGET_EXCEEDED`
- child terminal / timeout / interrupt / detach 时通过 `release_child.lua` 释放 in-flight 计数
- lifetime spawn 计数不释放，因为 `max-spawn-per-run` 是整个 run 的累计上限
- `spawn-budget-per-user-turn` 用于防止单个 user turn 内突发 spawn；当前默认值与 `max-spawn-per-run` 都是 2，所以不会先触发，未来调大 lifetime cap 时才有独立意义
- `AgentTool.schema().description` 必须提示模型这是高成本工具，请谨慎使用
- MainAgent default system prompt 必须加入反滥用提示：仅在任务确实需要独立上下文时使用 AgentTool，单 run 累计最多 2 次，超出后直接处理

同步等待规则：

- `AgentTool` 调用 SubAgent 后同步等待 child result summary
- 默认等待上限 3 分钟
- 如果 3 分钟内 child 完成，`AgentTool` 把 child summary 作为 tool result 返回 parent
- 如果超过 3 分钟仍未返回，`AgentTool` 通过 `RunInterruptService` 中断 child run，并返回 `SUBAGENT_WAIT_TIMEOUT` tool result
- 如果 child 后续产生 late result，不自动注入 parent transcript，只保留在 child trajectory 中

输出规则：

- Parent transcript 只保存 child result summary、状态、关键证据引用和 childRunId
- Parent transcript 不展开 child 全量消息
- 完整 child trajectory 通过查询 API 追溯
- SubAgent 如果达到 30 次 LLM 调用仍未完成，必须总结当前获得的结果，返回 partial summary 给 MainAgent，然后 child run 进入 `PAUSED`

Parent link status：

```text
LIVE
DETACHED_BY_TIMEOUT
DETACHED_BY_INTERRUPT
DETACHED_BY_PARENT_FAILED
```

- child 创建时 `parent_link_status=LIVE`
- AgentTool 等待超过 3 分钟后写 `DETACHED_BY_TIMEOUT`，并 interrupt child
- MainAgent 被 interrupt 级联 child 时写 `DETACHED_BY_INTERRUPT`
- MainAgent failed/cancelled 且 child 未结束时写 `DETACHED_BY_PARENT_FAILED`
- Trajectory query DTO 必须暴露 `parentLinkStatus`

### 11.6 ToDo 短期计划

ToDo 是 agent 的短期工作记忆，用于复杂任务分解，不是业务系统事实源。

Redis key：

```text
agent:{run:<runId>}:todos
agent:{run:<runId>}:todo-reminder
```

ToDo step：

```json
{
  "stepId": "step_1",
  "title": "查询昨天订单",
  "status": "PENDING",
  "notes": null,
  "updatedAt": "..."
}
```

工具：

```text
ToDoCreate({items:[...]})
ToDoWrite({stepId,status,notes?})
```

Reminder 注入：

```text
turnNo % 3 == 0
  ↓
TodoStore.findOpenTodos(runId)
  ↓
如果存在未完成步骤
  ↓
TodoReminderInjector 注入 transient message
```

Reminder 不伪装成用户消息。内部使用 `LlmMessage.extras.todoReminder=true` 标记，不写入 `agent_message`，只写 `agent_event` 方便审计。

### 11.7 中断与预算控制

V2 增加 interrupt。它和 abort 的差异必须在架构里分清：

| 操作 | 目的 | 状态结果 | 是否可继续 |
|---|---|---|---|
| `abort` | 终止整个 run | `CANCELLED` | 否 |
| `interrupt` | 打断当前 turn，停止本轮剩余工作 | `PAUSED` | 是 |

HTTP 入口：

```http
POST /api/agent/runs/{runId}/interrupt
Authorization: Bearer <jwt>
```

控制流：

```text
AgentController.interrupt
  ↓
RunAccessManager 校验 owner
  ↓
RunInterruptService 写 Redis control
  ↓
AgentTurnOrchestrator / ToolRuntime / SubAgentRunner 感知 interrupt
  ↓
停止新的 LLM 调用
  ↓
停止新的 tool schedule
  ↓
取消 WAITING 工具
  ↓
向 RUNNING 工具发送 cancellation token
  ↓
级联 interrupt child runs
  ↓
当前 run -> PAUSED
```

Redis control：

```text
agent:{run:<runId>}:control
  interrupt_requested=true
  interrupt_reason=USER_INTERRUPT
  interrupt_at=...
```

工具处理：

- scheduler 每次启动工具前检查 `interrupt_requested`
- WAITING tool 转 `CANCELLED + synthetic`
- RUNNING tool 通过 cancellation token 协作取消
- 写操作 tool 必须在真实业务副作用前检查 cancellation token
- 已经越过副作用边界的非幂等工具不能伪造“未执行”，只能记录真实 terminal 或 late terminal

SubAgent 处理：

- MainAgent 被 interrupt 时，`ChildRunRegistry` 找到所有 active child run
- `RunInterruptService` 对 child run 写入 interrupt control
- child run 不再发起新的 LLM 请求，不再启动新工具
- child run 尝试总结当前已获得结果，返回 `INTERRUPTED_PARTIAL`
- 如果 parent 已经结束等待，late child result 只进入 child trajectory

LLM 调用预算：

```text
每个用户 turn：
  MainAgent LLM calls <= 30
  每个 SubAgent LLM calls <= 30
整个 run：
  total LLM calls <= 80
```

这里的用户 turn 指一次 create run SSE 或 continuation SSE 的执行周期，不是单次 provider attempt。一次 fallback provider 调用也计入 LLM calls；建连前失败的 retry 也计入。

超过 30 次：

- MainAgent：停止本轮执行，run -> `PAUSED`，`nextActionRequired=user_input`
- SubAgent：先生成 partial summary 给 MainAgent，然后 child run -> `PAUSED`
- 如果 partial summary 也无法生成，返回结构化 `LLM_CALL_BUDGET_EXCEEDED` error result
- 任意预算先到达都触发 `PAUSED`
- 触发原因写入 `agent_event`：`MAIN_TURN_BUDGET`、`SUB_TURN_BUDGET`、`RUN_WIDE_BUDGET`

### 11.8 多实例推进

V2 按多台 Spring Boot 实例部署实现。Redis 是活跃状态真相源，MySQL 是 trajectory 真相源。任何 HTTP request、continuation、interrupt、tool schedule 或 SubAgent 操作都不能依赖请求粘性。

Active Runs Set：

```text
agent:active-runs
agent:{run:<runId>}:control
agent:{run:<runId>}:children
```

触发 schedule 的三个入口：

1. `ToolRuntime.onToolUse` 后本实例立即 schedule
2. `complete.lua` CAS 成功后本实例立即 schedule
3. 每个实例的 `ActiveRunSweeper` 每 2 秒扫描 `agent:active-runs`

去重仍由 Redis Lua 保证。即使多个实例同时扫到同一个 run，只有一个实例能把某个 WAITING tool 改成 RUNNING 并拿到 lease。

`ActiveRunSweeper` 职责边界：

- 只允许调用 `RedisToolStore.schedule(runId)`
- 不得调用 `AgentLoop.run`、`AgentLoop.continueRun` 或 provider adapter
- 只负责推动 Redis 中已经 ingest 的 WAITING tool 进入 RUNNING
- 任何会产生新 LLM 调用或新 assistant message 的动作，必须由持有 continuation lock 的实例发起
- sweeper 模块不依赖 `LlmProviderAdapter`，用编译期依赖边界防止误调用
- 每轮顺带检查 MySQL 已 terminal 且超过 `active-run-stale-cleanup-ms` 的 run，并从 `agent:active-runs` 主动 `SREM`

ToolResultWaiter：

```text
V2 默认:
  Redis Pub/Sub result notification

兜底:
  short polling Redis terminal state every 500ms
```

Pub/Sub 只优化延迟，不承担一致性真相源；真正结果仍以 Redis HASH / MySQL trajectory 为准。

### 11.9 V2 配置

```yaml
agent:
  agent-loop:
    llm-call-budget-per-user-turn: 30
    sub-agent-llm-call-budget-per-user-turn: 30
    run-wide-llm-call-budget: 80
  llm:
    primary-provider: deepseek
    fallback-provider: qwen
    deepseek:
      base-url: https://api.deepseek.com/v1
      api-key: ${DEEPSEEK_API_KEY}
      default-model: deepseek-reasoner
    qwen:
      base-url: https://dashscope.aliyuncs.com/compatible-mode/v1
      api-key: ${DASHSCOPE_API_KEY}
      default-model: qwen-plus
  context:
    large-result-threshold-tokens: 2000
    large-result-head-tokens: 200
    large-result-tail-tokens: 200
    micro-compact-threshold-tokens: 50000
    recent-message-budget-tokens: 2000
  skills:
    root: ${AGENT_SKILLS_ROOT}
    max-per-message: 3
    max-token-per-message: 8000
  sub-agent:
    enabled: true
    max-depth: 1
    max-spawn-per-run: 2
    max-concurrent-per-run: 1
    wait-timeout-ms: 180000
    spawn-budget-per-user-turn: 2
    llm-call-budget-per-user-turn: 30
    spawn-system-prompt-hint: |
      仅在任务确实需要独立上下文（例如长链路探查、与主任务隔离的子目标）时使用 AgentTool。
      单 run 累计最多 2 次，超出后请直接处理。
  todo:
    reminder-turn-interval: 3
  runtime:
    active-run-sweeper-enabled: true
    active-run-sweeper-interval-ms: 2000
    active-run-stale-cleanup-ms: 60000
    tool-result-pubsub-enabled: true
    tool-result-poll-interval-ms: 500
    interrupt-enabled: true
```

### 11.10 V2 测试计划

- DeepSeek/Qwen profile 分别转换 tools、messages、finish reason、usage、error
- Qwen streaming tool call delta 能组装成完整 `ToolCall`
- fallback 只在未提交 transcript 前发生
- stream 中断产生 partial tool call 后不 fallback、不执行工具
- context large spill 保留头尾 token 和 `resultPath`
- micro compact 不破坏 tool pair
- summary compact 生成 JSON summary，并记录 `compactedMessageIds`
- compact 后 provider request 通过 `TranscriptPairValidator`
- skill preview 只包含 `name + description`
- `skill_view` 支持 `SKILL.md` 与相对路径文件，并阻止路径逃逸
- 用户输入 `/purchase-guide` 时，本轮 provider view 注入完整 `purchase-guide/SKILL.md`
- `/skillName` 超过数量或 token budget 时 fail closed，返回 `SKILL_BUDGET_EXCEEDED`
- `AgentTool` 创建 child run，parent 只接收 child summary result
- child run 工具权限不能超过 parent effectiveAllowedTools
- 单 run 第 3 次调用 AgentTool 返回 `SUBAGENT_BUDGET_EXCEEDED`
- 同一 run 已有 in-flight child 时再次调用 AgentTool 返回 `SUBAGENT_BUDGET_EXCEEDED`
- 两个线程或两个实例同时调用 AgentTool 时，`reserve_child.lua` 只能让一个 child 成功 reserve slot
- child terminal / timeout / interrupt 后，`release_child.lua` 释放 in-flight 计数但不减少 lifetime spawn 计数
- child trajectory 暴露 `parentLinkStatus`
- child run 继承 MainAgent tool/skill 能力集合，但不继承 MainAgent message history、ToDo 状态或 compact view
- AgentTool 等待 SubAgent 超过 3 分钟后中断 child run，并返回 `SUBAGENT_WAIT_TIMEOUT`
- SubAgent 达到 30 次 LLM 调用后返回 partial summary 并进入 `PAUSED`
- `ToDoCreate`/`ToDoWrite` 正确更新 Redis plan
- 每 3 turn 注入 reminder，且不写成用户消息
- 多实例同时 schedule 不重复执行工具
- ActiveRunSweeper 只依赖 `RedisToolStore.schedule(runId)`，不依赖 AgentLoop 或 provider
- ActiveRunSweeper 能推进丢失事件的 run
- MySQL 已 terminal 超过 60s 的 run 会从 `agent:active-runs` 清理
- Pub/Sub 丢通知时 polling fallback 能拿到 terminal result
- run-wide LLM call budget 达到 80 时进入 `PAUSED`，并写 `RUN_WIDE_BUDGET` event
- 用户 interrupt 后当前 turn 主动结束，run 进入 `PAUSED`
- interrupt 后 WAITING 工具不再执行，RUNNING 工具收到 cancellation token
- MainAgent interrupt 会级联中断 active child runs
- late SubAgent result 不会污染 MainAgent transcript

## 12. V3 Agent Buyer Console 设计

### 12.1 V3 架构定位

V3 在现有 AgentLoop、V2 provider/context/skill/SubAgent/ToDo 能力之上增加一个前端 Console。它的定位是“agent lifecycle 可视化与本地 demo 调试台”，不是通用后台管理系统。

V3 不改变 AgentLoop 主链路：

```text
用户对话
  ↓
/api/agent/runs 或 /api/agent/runs/{runId}/messages
  ↓
AgentRunApplicationService
  ↓
AgentLoop / ToolRuntime / TrajectoryStore
  ↓
SSE 返回
```

Console 只做两件事：

1. 复用现有 `/api/agent/*` 进行真实 chat、continuation、trajectory、abort、interrupt。
2. 新增最小 `/api/admin/console/*` 查询接口，用安全投影展示 run list 和当前 run runtime state。

整体结构：

```text
┌─────────────────────────────────────────────────────────────┐
│                 admin-web (React Console)                    │
│                                                             │
│  ┌──────────────┬─────────────────────┬──────────────────┐  │
│  │  Run List    │    Run Timeline      │  Chat / Controls │  │
│  │              │                     │                  │  │
│  │  status      │  messages           │  user input      │  │
│  │  userId      │  attempts           │  SSE stream      │  │
│  │  provider    │  tool calls/results │  HITL actions    │  │
│  │  model       │  events/compactions │  abort/interrupt │  │
│  └──────────────┴─────────────────────┴──────────────────┘  │
│                                                             │
│  Debug Drawer: current-run runtime state + SSE event log     │
└─────────────────────────────────────────────────────────────┘
                              │
                              │ REST + POST SSE
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                 agent-buyer Spring Boot                      │
│                                                             │
│  Existing API                                                │
│    /api/agent/runs                                           │
│    /api/agent/runs/{runId}/messages                          │
│    /api/agent/runs/{runId}                                   │
│    /api/agent/runs/{runId}/abort                             │
│    /api/agent/runs/{runId}/interrupt                         │
│                                                             │
│  V3 Admin Console API                                        │
│    /api/admin/console/runs                                   │
│    /api/admin/console/runs/{runId}/runtime-state             │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
        ┌─────────────────┬─────────────────────────────┐
        │     MySQL       │            Redis            │
        │  trajectory     │ current run runtime state   │
        └─────────────────┴─────────────────────────────┘
```

V3 关键取舍：

- 不做 `/api/admin/chat`。否则 console 看到的行为可能和真实用户路径不一致。
- 不做通用 MySQL/Redis browser。Console 只展示 agent 相关安全投影。
- Runtime state 只读 `RedisKeys` 派生出的固定 key。
- Trajectory 复用已有安全 DTO，避免暴露 persistence entity 或 provider raw payload。

### 12.2 前端架构

前端工程目录：

```text
admin-web/
  package.json
  vite.config.ts
  tailwind.config.ts
  postcss.config.js
  tsconfig.json
  index.html
  src/
    main.tsx
    App.tsx
    index.css
    api/
      agentApi.ts
      adminApi.ts
      sseParser.ts
    components/
      shell/
        ConsoleShell.tsx
        Toolbar.tsx
      runs/
        RunListPanel.tsx
        RunListItem.tsx
        RunFilters.tsx
      timeline/
        TimelinePanel.tsx
        TimelineItem.tsx
        MessageNode.tsx
        LlmAttemptNode.tsx
        ToolCallNode.tsx
        EventNode.tsx
        CompactionNode.tsx
      chat/
        ChatPanel.tsx
        ChatTranscript.tsx
        ChatComposer.tsx
        RunControls.tsx
        ConfirmationBar.tsx
      debug/
        DebugDrawer.tsx
        SseEventLog.tsx
        RuntimeStateView.tsx
      ui/
        Button.tsx
        IconButton.tsx
        Badge.tsx
        EmptyState.tsx
        ErrorBanner.tsx
        Spinner.tsx
    hooks/
      useRunList.ts
      useRunDetail.ts
      useRuntimeState.ts
      useChatStream.ts
    types/
      agent.ts
      admin.ts
      sse.ts
```

布局：

```text
desktop:
  header 48px
  left   Run List         280px min, 22vw max
  middle Run Timeline     minmax(420px, 1fr)
  right  Chat / Controls  380px min, 32vw max

mobile:
  tabs: Runs | Timeline | Chat
```

`App` 持有全局状态：

```text
selectedRunId
selectedUserId default "demo-user"
adminToken from localStorage
debugOpen
```

`RunListPanel` 展示：

```text
status badge
短 runId
userId
primaryProvider / fallbackProvider / model
turnNo
updatedAt
parentRunId
parentLinkStatus
```

`TimelinePanel` 合并这些 trajectory 数组：

```text
messages       -> MESSAGE
llmAttempts    -> LLM_ATTEMPT
toolCalls      -> TOOL_CALL
toolResults    -> TOOL_RESULT
toolProgress   -> TOOL_PROGRESS
events         -> EVENT
compactions    -> COMPACTION
```

排序规则：

```text
优先按 timestamp 排序
时间相同或缺失时稳定排序：
message -> tool call -> tool progress -> tool result -> event -> compaction
```

`ChatPanel` 支持：

- 创建 run
- continuation
- `WAITING_USER_CONFIRMATION` 的确认/拒绝
- `PAUSED + user_input` 的补充输入
- interrupt
- abort
- SSE event debug log

### 12.3 后端 Admin Console API

新增 package：

```text
com.ai.agent.web.admin
  controller
    AdminConsoleController
  dto
    AdminPageResponse
    AdminRunListResponse
    AdminRunSummaryDto
    AdminRuntimeStateDto
    AdminRedisEntryDto
  service
    AdminAccessGuard
    AdminRunListService
    AdminRuntimeStateService
```

Admin access：

```yaml
agent:
  admin:
    enabled: ${AGENT_ADMIN_ENABLED:true}
    token: ${AGENT_ADMIN_TOKEN:}
```

规则：

- `enabled=false` 返回 503。
- `token` 为空时只允许 local/demo profile 访问。
- 非 local/demo profile 下，必须配置 token 或禁用 admin endpoint，否则启动或请求阶段 fail closed。
- `token` 非空时必须校验 `X-Admin-Token`。

Run list endpoint：

```text
GET /api/admin/console/runs?page=1&pageSize=20&status=RUNNING&userId=demo-user
```

DTO：

```java
public record AdminRunSummaryDto(
        String runId,
        String userId,
        String status,
        Integer turnNo,
        String agentType,
        String parentRunId,
        String parentLinkStatus,
        String primaryProvider,
        String fallbackProvider,
        String model,
        Integer maxTurns,
        LocalDateTime startedAt,
        LocalDateTime updatedAt,
        LocalDateTime completedAt,
        String lastError
) {
}
```

查询规则：

- 从 `agent_run` 读取 run 基础信息。
- 左连接 `agent_run_context` 读取 provider/model/maxTurns。
- 可选过滤只有 `status` 和 `userId`。
- 固定排序 `ORDER BY r.updated_at DESC`。
- 不支持用户传入 sort 字段、表名或自由 SQL。

Runtime state endpoint：

```text
GET /api/admin/console/runs/{runId}/runtime-state
```

DTO：

```java
public record AdminRuntimeStateDto(
        String runId,
        boolean activeRun,
        List<AdminRedisEntryDto> entries
) {
}
```

### 12.4 Chat / SSE 集成

前端 Chat 不使用 `EventSource`，因为创建 run 和 continuation 都是 POST 请求。必须用 `fetch + ReadableStream` 解析 SSE。

SSE event：

```text
text_delta
tool_use
tool_progress
tool_result
final
error
ping
```

字段约束：

```text
tool_use payload 使用 toolName，不使用 name
历史实现如果曾返回 name，前端可以兼容读取，但后端 V3 规范输出必须统一为 toolName
```

事件处理：

| Event | 前端效果 |
|---|---|
| `text_delta` | append 到 assistant draft |
| `tool_use` | 增加 tool card |
| `tool_progress` | 更新 progress card |
| `tool_result` | 增加 result preview |
| `final` | 设置 runId/status/nextActionRequired，关闭 draft |
| `error` | 设置 error，停止 streaming |
| `ping` | 只进入 debug log，不进入 chat transcript |

创建 run 默认请求：

```json
{
  "messages": [{"role": "user", "content": "<input>"}],
  "allowedToolNames": [
    "query_order",
    "cancel_order",
    "skill_list",
    "skill_view",
    "agent_tool",
    "todo_create",
    "todo_write"
  ],
  "llmParams": {
    "model": "deepseek-reasoner",
    "temperature": 0,
    "maxTokens": 4096,
    "maxTurns": 10
  }
}
```

Continuation：

```text
POST /api/agent/runs/{runId}/messages
X-User-Id: <selected userId>
body: {"message":{"role":"user","content":"..."}}
```

`WAITING_USER_CONFIRMATION`：

```text
Confirm button -> "确认继续执行"
Reject button  -> "放弃本次操作"
```

`PAUSED + user_input`：

```text
Composer placeholder -> "补充订单号、说明或下一步指令..."
```

### 12.5 Runtime State 安全模型

Runtime state 只允许读取以下 Redis key：

```text
agent:{run:<runId>}:meta
agent:{run:<runId>}:queue
agent:{run:<runId>}:tools
agent:{run:<runId>}:tool-use-ids
agent:{run:<runId>}:leases
agent:{run:<runId>}:continuation-lock
agent:{run:<runId>}:control
agent:{run:<runId>}:llm-call-budget
agent:{run:<runId>}:children
agent:{run:<runId>}:todos
agent:{run:<runId>}:todo-reminder
```

`agent:active-runs` 是例外：后端可以读取它来计算当前 run 是否活跃，但只能把结果返回为 `activeRun: true/false`，不能把完整 active run set 放进 `entries`。

明确禁止：

- 读取 `confirm-tokens`
- 返回完整 `agent:active-runs` set
- wildcard scan
- 用户输入任意 Redis key
- 将 Redis key 放入 path variable
- 返回 provider raw diagnostic payload
- 返回原始 `confirmToken`

`confirmToken` 脱敏策略：

- 后端 Console DTO 层统一移除或 mask `confirmToken`
- 覆盖 tool call args、tool result、event payload、runtime state value
- 前端渲染层做兜底 redaction，防止历史数据或异常 payload 泄露 token
- 测试 fixture 必须包含 `confirmToken`，验证页面不展示原值

展示分组：

```text
Control: meta, continuation-lock, control, llm-call-budget
Tool Runtime: queue, tools, tool-use-ids, leases
Planning: todos, todo-reminder
SubAgent: children
Active: activeRun boolean
```

### 12.6 V3 配置

后端：

```yaml
agent:
  admin:
    enabled: ${AGENT_ADMIN_ENABLED:true}
    token: ${AGENT_ADMIN_TOKEN:}
```

前端：

```text
admin-web dev server: http://127.0.0.1:5173
Vite proxy /api -> http://127.0.0.1:8080
```

本地启动：

```bash
MYSQL_PASSWORD='Qaz1234!' mvn spring-boot:run
cd admin-web && npm run dev
```

### 12.7 V3 测试计划

后端测试：

- `AdminAccessGuardTest`
- `AdminRunListServiceTest`
- `AdminRuntimeStateServiceTest`
- `AdminConsoleControllerTest`

后端测试必须覆盖：

- local/demo profile 允许空 admin token
- 非 local/demo profile 下空 admin token fail closed 或 admin endpoint disabled
- runtime-state 不返回 `confirm-tokens`
- runtime-state 不返回完整 `agent:active-runs` set，只返回 `activeRun` 布尔值
- tool call args、tool result、event payload 中的 `confirmToken` 被移除或 mask

前端测试：

- `sseParser.test.ts`
- `RunListPanel.test.tsx`
- `TimelinePanel.test.tsx`
- `RuntimeStateView.test.tsx`
- `useChatStream.test.tsx`
- `ChatPanel.test.tsx`
- `App.integration.test.tsx`

前端测试必须覆盖：

- `tool_use` 使用 `toolName` 字段渲染
- 历史 payload 只有 `name` 时可兼容显示，但新请求状态使用 `toolName`
- runtime state / timeline / SSE log 中出现 `confirmToken` 时不展示原始 token

最终验证：

```bash
MYSQL_PASSWORD='Qaz1234!' mvn test
cd admin-web && npm test && npm run build
```

浏览器 smoke：

```text
desktop 1440x900
mobile 390x844
无面板重叠
按钮文字不溢出
chat 可发送 prompt 并收到 SSE final
```

## 13. 后续演进

V2/V3 之后再考虑：

- Redis key 级 `generation` 隔离
- 完整幂等恢复 / outbox
- MQ worker 解耦
- 大结果对象存储 spill
- V3 后续可以独立演进 admin-inspector，但必须和 Agent Console 分离，避免把 demo console 做成高风险通用运维后台

## 14. 关键结论

V1a 不是一个大而全的 agent framework，而是一个业务系统 AI 赋能层。它的价值在于把真实业务能力包装成 LLM 可调用的 tool，并把“模型输出、工具执行、状态一致性、轨迹追溯、安全防护、SSE 用户体验”串成一个可演示、可解释、可扩展的闭环。

V2 的定位也不应该变成“什么都做”。它是在 V1a 稳定闭环上补齐 agent 工程中最常见的六个真实痛点：provider 可替换、长上下文可控、技能按需加载、复杂任务可委派、长期 turn 有计划、多实例不会卡死。

```text
V1a:
  DeepSeek tool calling
  + Redis tool scheduling
  + MySQL trajectory
  + business tool abstraction
  + SSE UX
  + safety and observability

V2:
  + DeepSeek/Qwen provider boundary
  + Context compact view layer
  + Anthropic-style skill loading
  + AgentTool child run
  + Redis ToDo short-term memory
  + Active Runs Set multi-instance sweeper

V3:
  + Agent Buyer Console
  + run list / trajectory timeline
  + chat + POST SSE debug
  + current-run Redis runtime state
  + HITL / PAUSED / interrupt / abort 可视化
```

这个路线比做一个“看起来什么都有，但每块都很薄”的 harness 更适合作为 Java 业务系统 AI 改造 demo，也更适合作为求职作品集展示。
