# Agent Loop Requirement

## 目录

- 0. 业务背景
- 1. 目标
  - 1.1 审查后重构目标
- 2. 部署与技术约束
- 3. Message Role 模型
- 4. Prompt Assembly
- 5. Provider 要求
- 6. 业务 Tool 要求
- 7. Redis Key 设计
- 8. Tool 状态机
- 9. 调度规则
- 10. 核心接口
- 11. MySQL Trajectory
- 12. 安全边界
- 13. V1a 必须保证的不变量
- 14. Context 生命周期
  - 14.1 Large Tool Result Spill
  - 14.2 Micro Compact
  - 14.3 LLM Summary Compact
- 15. 分期范围
- 16. 测试场景
- 17. 成功标准
- 18. 对外接口契约
- 附录 A：V2 详细需求
  - A.0 V2 里程碑拆分
  - A.1 V2 Provider
  - A.2 V2 Skill 渐进式加载
  - A.3 V2 SubAgent
  - A.4 V2 ToDo
  - A.5 V2 多实例与结果通知
  - A.6 V2 中断与 LLM 调用预算
  - A.7 V2 测试

## 0. 业务背景

本系统是已有 Java 业务系统的 AI 赋能层。原有业务能力以 REST API 和 service bean 形式存在，用户必须通过页面操作完成多步流程；本系统把这些能力包装为 agent tool，让用户用自然语言完成等价操作。

典型场景：

```text
用户说：“取消我昨天的那个订单”
  ↓
agent 调用 query_orders(filter) 找到候选订单
  ↓
agent 调用 cancel_order(orderId) 完成取消
  ↓
agent 返回自然语言确认
```

V1a 的产品定位不是通用 agent harness，而是一个可演示、可解释、可扩展的 Java 业务系统 AI 层：

- 作为求职作品集，可以展示 agent loop、tool calling、trajectory、SSE、Redis 调度和安全边界
- 作为业务 demo，可以把已有业务流程包装成自然语言入口
- 作为架构样板，可以说明单容器 V1a 如何自然演进到 V2 的多实例、多 provider、context compact、skill、subAgent 和 ToDo 能力

部署形态：

- Agent 应用以 Docker 单容器部署
- MySQL 单实例、Redis 单实例作为外部依赖；本地 demo 可用 docker compose 拉起
- HTTP REST 接入，SSE 流式返回 assistant text 与 tool progress
- 用户认证与权限沿用现有系统，本系统从 JWT/header 取 `userId`
- 单 run 典型 10 turn，wallclock 不超过 5 分钟

V1a 的业务集成边界必须固定，避免“既像独立服务，又像业务系统内部模块”的摇摆：

```text
V1a = 独立 Agent Spring Boot 服务
     + in-process mock business module
     + BusinessClient / BusinessService adapter
```

也就是说，V1a demo 不依赖一个真实外部订单系统才能跑起来；项目内置最小订单/用户资料业务模块和 seed data，模拟已有业务系统能力。后续接真实业务时，只替换 `BusinessClient` / `BusinessService` adapter，不改 AgentLoop、ToolRuntime、trajectory 和 SSE 协议。

本地演示拓扑：

```text
docker compose up
  ├─ agent-app
  ├─ mysql
  └─ redis
```

`agent-app` 内置 mock business module；如果后续要演示外部系统集成，可以把 mock module 替换为 `mock-business-api` 容器或真实业务 REST API。

## 1. 目标

实现一个基于 Java Spring Boot 的业务型 Agent Loop，让现有 Java 业务能力可以被 LLM 以 tool calling 方式调用，并完成多步对话任务。

V1a 必须先把最小生产闭环跑稳：

```text
HTTP request
  ↓
AgentLoop 创建 run，写入 user message
  ↓
PromptAssembler 组装 system prompt + history + provider tools
  ↓
DeepSeek streaming
  ↓
AttemptBuffer 暂存 assistant text / tool_call delta
  ↓
provider attempt 成功后提交 assistant message / tool calls
  ↓
Redis ToolRuntime 调度并执行业务 tool
  ↓
tool_result 回填 transcript
  ↓
继续下一轮 LLM 或返回 final answer
```

核心目标：

- 提供最小 `AgentLoop`，负责 run/message 管理、LLM 调用、stream 解析、tool 调度、tool result 回填、final answer 生成
- V1a 只接入一个 provider：DeepSeek，使用 OpenAI-compatible streaming 协议
- 支持 HTTP REST + SSE，对外可演示自然语言驱动业务流程
- 支持 MySQL trajectory，追溯 run、message、llm attempt、tool call、tool result
- 支持 Redis ToolRuntime：ingest、schedule、complete CAS、abort、cancel synthetic result
- 支持 2-3 个真实业务 tool 示例，并预留 `BusinessTool` / `AbstractTool` 基类供后续扩展
- 支持 safe/non-safe 调度：`isConcurrent=true` 的工具可并发，`isConcurrent=false` 的工具作为顺序屏障
- 每次 provider 请求前执行 `TranscriptPairValidator`，防止 orphan tool call / orphan tool result
- 提供最小 lease reaper，避免工具因 JVM 崩溃永久卡在 `RUNNING`
- 安全边界聚焦于业务系统集成：复用现有认证权限，只实现 prompt injection 防护、PII 脱敏、限流和 secret 管理

V1a 明确不做 Claude Code 式 eager streaming tool execution。模型流式输出中的 text delta 可以实时展示给用户，但 tool_call delta 只进入当前 provider attempt 的内存 buffer；只有该 provider attempt 完整成功结束后，AgentLoop 才提交 assistant message 和 tool calls，并把 tool calls 写入 Redis 调度队列。失败 attempt 的 partial text / partial tool_call 只写 MySQL attempt 诊断，不进入 Redis，也不生成可执行工具。

### 1.1 审查后重构目标

代码审查后，V1a 需要先完成一轮 hardening。目标不是增加新能力，而是把安全边界、状态一致性和代码分层固化下来：

- run 访问必须有统一入口。所有按 `runId` 查询、继续、终止的操作，都必须先校验当前 `userId` 对该 run 的访问权限。
- `allowedToolNames` 必须升级为 run 级安全上下文。create run 时计算 `effectiveAllowedTools`，后续 continuation 只能复用，不能因为请求参数缺失而回退为全部工具。
- run context 初始化必须 fail closed。`agent_run` 创建成功但 `agent_run_context` 创建失败时，run 必须被补偿标记为 `FAILED`，不能留下可见但不可继续的裸 `CREATED` run。
- continuation 在写入用户确认消息前，必须先加载并校验 run context；如果 context 缺失、工具已下线、model 缺失或 maxTurns 非法，必须恢复到 `WAITING_USER_CONFIRMATION` 或显式失败，不能卡在 `RUNNING`。
- 历史 run context migration 不能默认授予写工具。无法证明原始授权的 active/waiting 历史 run 必须采用空工具集或终止重开等保守策略。
- abort / timeout 必须成为可传播的 run control 信号，而不是只更新 MySQL 状态。Redis scheduler、tool runtime、complete CAS 和业务 tool 执行副作用前都必须能感知。
- abort 不能伪造已经开始执行的非幂等写工具结果。`WAITING` 工具和幂等 `RUNNING` 工具可以 synthetic cancel；非幂等 `RUNNING` 工具必须保留真实 complete 或 lease timeout 审计结果，避免业务副作用与 trajectory 不一致。
- 每个 assistant tool call 写入 trajectory 后，必须在所有失败路径上生成 matching tool result；timeout、abort、executor reject、provider 后续失败都必须通过 synthetic result 闭合 transcript。
- 用户确认语义必须显式建模为确认、拒绝、模糊三类；模糊输入不能继续调用 provider，也不能误触发写操作。
- 用户 abort 只对 active run 生效，不能覆盖已经 `SUCCEEDED/FAILED/TIMEOUT/CANCELLED` 的终态 run。
- `DefaultAgentLoop` 不能继续承担过多职责。V1a 需要拆出 application service、run access、turn orchestration、LLM attempt、tool coordination、tool result close、confirmation intent 和 request policy 等边界。
- 对外 trajectory 查询必须返回 DTO，并执行权限校验、字段裁剪和必要脱敏；不能直接返回 persistence entity 或内部 raw diagnostic。

## 2. 部署与技术约束

术语定义：

| 术语 | 含义 |
|---|---|
| `session` | 用户与系统之间的长期会话容器，可以包含多个 run |
| `run` | 一次 AgentLoop 任务执行，从用户输入开始，到 final / failed / cancelled / max turns / timeout 结束 |
| `user turn` | 一次用户驱动的执行周期，对应一次创建 run 请求或 continuation SSE 请求；V2 的 30 次 LLM 调用预算按 user turn 计算 |
| `turn` | V1a 表结构中的模型推进轮次，通常是一轮 provider 请求，以及如果有 tool call，则包含随后的 tool result 回填 |
| `llm call` | 一次真实 provider 请求；primary、fallback、建连前失败 retry 都计入 V2 LLM 调用预算 |
| `attempt` | 同一个 turn 内对 provider 的一次请求；V1a 只有 DeepSeek，一个 turn 通常只有一个 attempt |
| `tool call` | assistant message 发起的具体工具调用，必须最终匹配一个 tool result |

Run 状态机：

```text
CREATED
RUNNING
WAITING_USER_CONFIRMATION
PAUSED
SUCCEEDED
FAILED
FAILED_RECOVERED
CANCELLED
TIMEOUT
```

`WAITING_USER_CONFIRMATION` 是写操作 dry-run 后的产品状态，不是 provider role，也不是 tool status。run 进入该状态后，SSE 当前连接可以结束；用户确认时通过继续对话接口把确认消息追加到同一个 run。

`PAUSED` 是 V2 引入的非终态状态，用于表达当前 turn 被主动暂停，但 run 仍可继续。典型原因：

```text
USER_INTERRUPT
LLM_CALL_BUDGET_EXCEEDED
SUBAGENT_WAIT_TIMEOUT
```

`PAUSED` 不等于 `CANCELLED`。`CANCELLED` 是终态；`PAUSED` 表示当前 turn 停止推进，后续可以通过 continuation 继续。

技术约束：

- 语言：Java
- 框架：Spring Boot
- Agent 应用部署：Docker 单容器
- 活跃状态：Redis 单实例
- 追溯存储：MySQL 单实例
- 对外接入：HTTP REST + SSE
- 用户身份：从 JWT/header 取 `userId`，认证与 RBAC 由外部已有系统完成
- LLM provider：V1a 只接入 DeepSeek
- LLM API：OpenAI-compatible Chat Completions streaming
- 工具执行：V1a 由本 Spring Boot JVM 的 bounded executor 执行
- 多实例方案：文档中保留演进设计，V1a 不实现
- 不引入 Kafka、RabbitMQ、workflow engine、对象存储
- hardening 优先级：先修 run 访问、工具权限、abort/timeout、transcript close 和输入预算，再整理包结构

非功能性目标（V1a demo 级别）：

- 单 Docker 容器部署 agent 应用
- 并发活跃 run 上限：50
- 单 turn p95 延迟 < 8 秒（含 LLM 调用）
- 单 run 最长 wallclock：5 分钟，超时 fail
- 单 run 默认最大 turn：10
- 单用户限流：每分钟 5 个 run
- 单用户 token 预算：每天 100k token
- confirm token 默认 10 分钟过期
- MySQL 单实例，Redis 单实例
- `agent_event` 与 `agent_tool_progress` 可异步批量写入，不能拖慢主链路
- 本地 demo 必须提供 seed data 和一条可复现脚本化场景：用户取消昨天的订单
- README 必须包含项目定位、架构图、docker compose 启动步骤、5 步演示流程、关键设计取舍、演进规划和已知限制
- README 应给出 token 成本估算公式；默认预算 100k token/day/user，典型 run 约 50k token

## 3. Message Role 模型

V1a 内部 message role 使用 4 类：

| Role | 存储内容 | 是否发送给 provider | 说明 |
|---|---|---:|---|
| `SYSTEM` | materialized system prompt | 是 | 本次 run 真正使用的 system prompt |
| `USER` | 用户输入 | 是 | 来自 HTTP request |
| `ASSISTANT` | 模型文本、tool calls、final answer | 是 | assistant 是 tool call 发起方，不能省略 |
| `TOOL` | tool result | 是 | 必须通过 `toolUseId` 配对 assistant tool call |

V1a 不引入 `SUMMARY` role。V2 做 summary compact 时，summary message 直接使用 `ASSISTANT` role，summary 元数据放入 `LlmMessage.extras`，避免 provider replay 时增加额外 role mapping 复杂度。

约束：

- `TOOL` role 只存 tool result，不存 tool schema
- tool schema 来自每个工具自己的 `Tool.schema()`
- assistant tool call 必须有 matching tool result
- tool result 不能脱离前置 assistant tool call 单独出现
- cancel、timeout、abort、executor reject 都必须生成 synthetic tool result，闭合 transcript

## 4. Prompt Assembly

`default system prompt` 是全局固定模板，所有用户一致；它不是 message 表里最终存储的完整 system prompt。真正写入 `agent_message(role=SYSTEM)` 并发送给 provider 的，是针对某个 user/run 渲染后的 `materialized system prompt`。

V1a system prompt 组成：

```text
materialized system prompt
  = default system prompt
  + user info prompt
  + tool-use guidance
  + business tool schema text snapshot
```

V1a prompt 组装步骤：

```text
从 HTTP header / JWT 获取 userId
  ↓
UserProfileStore 查询用户身份、租户、角色、基础偏好
  ↓
ToolRegistry 根据 allowedToolNames 过滤可用工具
  ↓
每个 Tool 自己提供 Tool.schema()
  ↓
PromptAssembler 渲染 materialized system prompt
  ↓
provider tools 字段携带结构化 tool schema
```

快照要求：

- `agent_message(role=SYSTEM)` 必须保存本次 run 的 materialized system prompt
- materialized system prompt 必须记录 `systemPromptHash`
- provider tools payload 必须记录 `toolSchemaVersion` 与 `toolSchemaHash`
- 同一个 run 内，除非显式创建新 run，不允许工具 schema 在 turn 之间静默变化
- system prompt 中的 tool schema text 是给模型看的审计快照；provider `tools` 字段是 provider 执行 tool calling 的结构化契约；二者都来自 `Tool.schema()`

V2 prompt 在 V1a 基础上增加 skill preview：

```text
materialized system prompt
  = default system prompt
  + user info prompt
  + skill preview(name + description)
  + tool-use guidance
  + business tool schema text snapshot
```

V2 初始 prompt 不放完整 `SKILL.md`，只放当前用户可访问 skill 的 `name + description`。模型需要完整 skill 内容时，必须通过 `skill_view` 渐进式加载。

## 5. Provider 要求

V1a 只接入 DeepSeek：

- 使用 OpenAI-compatible Chat Completions streaming
- 支持 text delta
- 支持 tool call delta
- 支持 usage 统计
- 支持 request timeout
- 支持 provider request / response 关键字段脱敏后写入 attempt 诊断

V1a 不做 provider fallback。失败策略：

- 网络错误、HTTP 429、HTTP 5xx、stream 建连前失败：最多 2 次指数退避重试，等待 200ms、800ms；仍失败后当前 attempt failed
- stream 已经开始后中断：不重试同一请求，当前 attempt failed，partial text/tool call 只写诊断
- HTTP 400 schema 错误：run failed，并记录 provider error
- HTTP 401/403：启动或请求前 fail fast，提示配置/鉴权错误
- 失败 attempt 的 partial text / partial tool call 只写 `agent_llm_attempt`，不提交到 Redis

Qwen 与 provider fallback 放到 V2。V2 不接入 GLM。

V2 provider 要求：

- `LlmProviderAdapter` 扩展为 registry 模式，至少包含 `DeepSeekProviderAdapter` 与 `QwenProviderAdapter`
- DeepSeek 与 Qwen 均按 OpenAI-compatible Chat Completions streaming 接入，但不能假设二者完全等价
- provider 方言差异必须放在 `ProviderCompatibilityProfile` 中，包括 tools payload、message replay、finish reason、tool call id、usage 字段和错误码映射
- run 创建时确定 `primaryProvider`、`fallbackProvider`、`model`，写入 run context；continuation 不能静默换 provider
- provider fallback 只允许发生在请求尚未产生 committed assistant message / committed tool call 之前
- stream 已经开始并产生 tool call delta 后，如果发生中断，默认不 fallback，避免旧 provider 的 partial toolUseId 泄漏到新 provider 请求
- 如果 fallback 发生，旧 attempt 的 partial 输出只写 `agent_llm_attempt` 诊断，不进入 transcript，不触发工具执行
- fallback 后仍必须重新运行 `TranscriptPairValidator`

## 6. 业务 Tool 要求

V1a 不内置文件工具 `Read/Write/Edit/Zip/Unzip`。本项目场景是“把已有业务流程包装成 tool”，因此 V1a 内置 2-3 个业务 tool 示例：

| Tool | 并发 | 作用 | 风险级别 |
|---|---:|---|---|
| `query_order` | true | 查询当前用户可见订单，支持时间、状态、关键词过滤 | 低 |
| `cancel_order` | false | 取消指定订单，必须走 dry-run / confirm 两段式 | 高 |
| `update_profile` | false | 更新用户资料，必须走 dry-run / confirm 两段式 | 高 |

V1a 内置最小业务域模型：

```text
Order:
  orderId: string
  userId: string
  status: CREATED | PAID | SHIPPED | CANCELLED
  createdAt: datetime
  amount: decimal
  itemName: string

Profile:
  userId: string
  displayName: string
  phone: string
  email: string
  address: string
```

订单取消规则：

- `CREATED / PAID` 可以取消
- `SHIPPED / CANCELLED` 不可取消
- 只能操作当前 `userId` 可见订单
- `cancel_order` 第一次调用只能 dry-run，不能直接产生业务副作用

Mock seed data 至少包含：

- 当前用户昨天有一个 `PAID` 订单，可取消
- 当前用户有一个 `SHIPPED` 订单，不可取消
- 其他用户有订单，用于验证越权不可见

业务 adapter 边界：

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
```

V1a 使用 `@Profile("demo")` 的 in-process mock 实现；后续接真实业务系统时增加 `@Profile("prod")` 的 REST client 实现。

工具要求：

- 每个业务 tool 是一个 Spring Bean
- 每个业务 tool 实现统一 `Tool` 接口
- `Tool.schema()` 是 LLM-facing schema 与 runtime metadata 的唯一来源
- `Tool.validate()` 负责参数校验、参数规范化、权限前置检查
- `Tool.schema().idempotent` 默认 false；只读工具如 `query_order` 显式声明 true
- `Tool.schema().sensitiveFields` 声明结构化输出中的敏感字段名，供统一脱敏使用
- 写操作类工具必须支持 dry-run / confirm 两段式
- 输出必须经过统一 PII 脱敏和大小限制
- 工具不能自己写 LLM transcript，只能返回 `ToolTerminal`

写操作两段式：

```text
第一次调用 cancel_order({orderId, dryRun:true})
  ↓
返回 PENDING_CONFIRM + 操作摘要 + confirmToken
  ↓
用户在对话中确认
  ↓
模型再次调用 cancel_order({orderId, confirmToken})
  ↓
工具执行真实取消，返回 SUCCEEDED / FAILED
```

`PENDING_CONFIRM` 不是 ToolStatus。dry-run tool call 本身以 `SUCCEEDED` 结束，tool result 里带业务动作状态：

```json
{
  "actionStatus": "PENDING_CONFIRM",
  "confirmToken": "ct_123",
  "summary": "将取消订单 O-1001，金额 199.00 元"
}
```

AgentLoop 收到带 `PENDING_CONFIRM` 的 tool result 后，run 状态进入 `WAITING_USER_CONFIRMATION`，并通过 SSE 返回需要用户确认的 final event。用户确认后，通过 `POST /api/agent/runs/{runId}/messages` 继续同一个 run。

`confirmToken` 必须服务端生成并存储，不能只信任模型回传：

```text
confirmToken -> {runId, userId, toolName, argsHash, expiresAt}
```

真实写操作执行前必须校验 token 未过期、属于当前 user/run/tool，且参数 hash 与 dry-run 一致。
同一业务动作重复 dry-run 时，必须生成新的 confirmToken，并删除旧 token。

`argsHash` 计算规则：

```text
argsHash = SHA-256(canonicalJson(args minus {"confirmToken", "dryRun"}))
```

`canonicalJson` 必须按 key 排序、去掉无意义空白，并使用稳定 JSON 表示，避免 dry-run 与 confirm 阶段因为字段顺序不同导致 hash 不一致。

## 7. Redis Key 设计

V1a Redis 使用单实例，但 key 仍按 hash tag 设计，方便后续 Redis Cluster 演进：

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

含义：

- `meta`：run 控制状态，例如 `max_parallel`、`abort_requested`、`wallclock_deadline_ms`
- `queue`：ZSET，score 为 `seq`，value 为 `toolCallId`
- `tools`：HASH，field 为 `toolCallId`，value 为 `ToolCallRuntimeState` JSON
- `tool-use-ids`：HASH，用于 `toolUseId` 去重
- `leases`：ZSET，score 为 `leaseUntilMs`，value 为 `toolCallId`
- `confirm-tokens`：HASH，保存 dry-run 生成的 confirm token，value 绑定 userId、toolName、argsHash、expiresAt
- `continuation-lock`：STRING，用于同一 run 的 continuation 互斥，TTL 5 分钟
- `rate-limit:user:<userId>:runs-per-min`：STRING/计数器，用于每分钟 run 限流
- `rate-limit:user:<userId>:tokens-per-day`：STRING/计数器，用于每日 token 预算

V1a 不引入 `generation`。Provider fallback 和跨 provider replay 进入 V2 后再评估。

## 8. Tool 状态机

V1a 状态：

```text
WAITING
RUNNING
SUCCEEDED
FAILED
CANCELLED
```

不单独引入 `TOMBSTONED` 状态。取消、超时、abort 统一进入 `CANCELLED`，并通过 `cancelReason` 区分原因：

```text
USER_ABORT
RUN_ABORTED
TOOL_TIMEOUT
PRECHECK_FAILED
EXECUTOR_REJECTED
LEASE_EXPIRED
```

synthetic 标志不属于 Redis 状态机，而是 tool result trace 的属性：

```text
agent_tool_result_trace.synthetic = true
```

V1a 模型分三层，避免把稳定调用身份、运行态和终态结果混在一起：

```text
ToolCall:
  稳定调用身份和参数，进入 MySQL tool_call_trace

ToolCallRuntimeState:
  Redis 中的运行态，包含 status、lease、cancelReason

ToolTerminal:
  终态结果，进入 Redis terminal state 与 MySQL tool_result_trace
```

`ToolCallRuntimeState` 至少包含：

```json
{
  "runId": "run-id",
  "toolCallId": "internal-id",
  "seq": 1,
  "toolUseId": "provider-tool-call-id",
  "rawToolName": "cancelOrder",
  "toolName": "cancel_order",
  "argsJson": "{}",
  "isConcurrent": false,
  "precheckFailed": false,
  "precheckErrorJson": null,
  "attempt": 0,
  "leaseToken": null,
  "status": "WAITING",
  "cancelReason": null,
  "resultJson": null,
  "errorJson": null
}
```

Lease reaper 判断是否可重试时只看 `ToolSchema.idempotent`：

- 默认 `idempotent=false`
- `query_order` 这类只读工具可以声明 `idempotent=true`
- `cancel_order / update_profile` 不自动重试，过期后生成 `CANCELLED + LEASE_EXPIRED + synthetic result`

## 9. 调度规则

Redis Lua 脚本按 `seq` 从小到大扫描 queue：

- 如果当前存在 `RUNNING isConcurrent=false`，不启动任何新工具
- `SUCCEEDED / FAILED / CANCELLED`：跳过
- `RUNNING isConcurrent=true`：跳过，继续扫描
- `WAITING isConcurrent=true`：如果并发容量未满，则启动
- `WAITING isConcurrent=false`：只有当前没有任何 RUNNING，且本轮未启动工具时，才能启动；启动后停止扫描
- `isConcurrent=false` 后面的工具不能越过它

示例：

```text
A query_order(concurrent), B query_order(concurrent), C cancel_order(exclusive), D query_order(concurrent)
```

执行批次：

```text
第一批：A、B 并发
第二批：C 独占
第三批：D 执行
```

## 10. 核心接口

```java
public interface AgentLoop {
    AgentRunResult run(AgentRunRequest request, AgentEventSink sink);

    AgentRunResult continueRun(String runId, UserMessage message, AgentEventSink sink);
}

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

public interface Tool {
    ToolSchema schema();

    ToolValidation validate(ToolUseContext ctx, ToolUse use);

    ToolTerminal run(ToolExecutionContext ctx, StartedTool running, CancellationToken token) throws Exception;
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

`run(...)` 只用于创建新 run；`continueRun(...)` 只用于 `WAITING_USER_CONFIRMATION` 或需要用户补充输入的既有 run。`POST /api/agent/runs/{runId}/messages` 必须调用 `continueRun`，不能伪装成一个新的 run。

这些支撑类型是接口契约，不是数据库模型。`userId` 由 Controller 从 JWT/header 注入，不能信任请求体传入的 userId。

`ToolUse` 只表示 provider parser 刚解析出的临时输入，不进入 Redis/MySQL，也不作为 runtime 状态模型。validate 之后系统生成 `ToolCall`；后续 WAITING / RUNNING 状态由 `ToolCallRuntimeState` 承载，terminal 结果由 `ToolTerminal` 承载。

`StartedTool` 是从 Redis schedule 拿到 lease 后的执行凭证：

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

只有携带 `StartedTool` 的执行者才能 complete；CAS 使用 `attempt + leaseToken + status` 做 fencing。

V1a `workerId` 生成规则：

```java
"jvm-" + ManagementFactory.getRuntimeMXBean().getName()
```

该字段主要为 V2 多实例调度预留；V1a 单 JVM 也写入，便于日志和 trajectory 对齐。

职责：

- `AgentLoop`：调用 LLM、解析 tool call、调度工具、收集工具结果、推进多轮对话
- `PromptAssembler`：渲染 materialized system prompt，并提供 provider tools
- `UserProfileStore`：根据 `userId` 查询用户身份、租户、角色、偏好
- `LlmProviderAdapter`：V1a 只封装 DeepSeek 连接、鉴权、stream 解析
- `ToolCallAssembler`：组装 provider streaming tool call delta
- `AttemptBuffer`：暂存 attempt text/tool_call delta
- `AttemptCommitter`：成功 attempt 才提交 assistant message、tool call trace、Redis WAITING
- `TrajectoryStore`：同步写关键 trajectory
- `TranscriptPairValidator`：校验 tool call / tool result 配对
- `ProviderCompatibilityProfile`：描述 DeepSeek 的 schema、message、stream、tool id 方言
- `ToolRuntime`：ingest、schedule、submit、complete
- `RedisToolStore`：封装 Redis key、Lua 脚本、状态读写
- `ToolLeaseReaper`：处理过期 RUNNING
- `ToolRegistry`：按工具名查找 `Tool`，不生成、不修改、不推断 schema
- `ExecutorService`：本机 bounded 工具执行线程池

## 11. MySQL Trajectory

V1a 必须写入完整可追溯主链路：

| 表 | 作用 | 写入策略 |
|---|---|---|
| `agent_run` | run 元数据、状态、用户、模型、hash | 同步关键路径 |
| `agent_message` | system/user/assistant/tool message | 同步关键路径 |
| `agent_llm_attempt` | provider attempt、错误、usage、partial 诊断 | 同步关键路径 |
| `agent_tool_call_trace` | tool call 参数、seq、schema hash | 同步关键路径 |
| `agent_tool_result_trace` | tool result、synthetic、cancelReason | 同步关键路径 |
| `agent_event` | run 事件、provider 诊断、abort、限流等 | 异步批量 |
| `agent_tool_progress` | 长工具阶段进度 | 异步批量 |

`agent_llm_attempt.finish_reason` 取值固定为：

```text
STOP
TOOL_CALLS
LENGTH
CONTENT_FILTER
ERROR
```

MySQL message 表设计要求：

- `agent_message.message_id` 直接作为主键
- 不再额外使用 `id bigint auto_increment`
- 按 `(run_id, turn, created_at)` 建索引支持 trajectory 查询
- `agent_message.tool_calls` 只存 provider replay 所需的最小信息：`toolUseId + toolName`
- canonical args、raw tool name、precheck 信息、`isConcurrent`、seq 等以 `agent_tool_call_trace` 为准

关键路径同步写失败时，V1a fail closed：

- 停止当前 run
- abort Redis 中未完成工具
- 对已提交 assistant tool call 生成 synthetic tool result
- 返回 SSE `error`

AttemptCommitter 的提交顺序必须明确：

```text
1. 对成功 attempt 的 raw tool calls 做 ToolRegistry.resolve + Tool.validate
2. 生成 ToolCall，记录 canonical toolName、isConcurrent、precheck 信息
3. 在 MySQL 事务中写 assistant message + agent_tool_call_trace
4. 对 precheck failed 的 tool call 同事务写 FAILED tool result，用于闭合 transcript
5. 事务提交成功后，仅对 precheck passed 的 tool call 逐个 Redis ingest WAITING
6. 如果 Redis ingest 失败：
     写 synthetic FAILED tool result
     标记 run FAILED 或让 LLM 下一轮看到错误
7. 如果 MySQL 事务失败：
     不写 Redis，不执行工具，run fail closed
```

V1a 不引入 outbox，但必须有最小 repair：

- 应用启动时扫描非 terminal run；`WAITING_USER_CONFIRMATION` 只有超过确认过期时间后才 repair
- 查找 assistant tool call 已写入但缺少 matching tool result 的记录
- 对无法确认仍在执行的 tool call 写 synthetic FAILED result
- 将 run 标记为 `FAILED_RECOVERED` 或 `FAILED`
- repair 事件写入 `agent_event`，即使 event 写失败也要保留日志

这样保证 demo 和本地开发过程中不会留下永久 orphan transcript。

异步批量写：

- `agent_event`
- `agent_tool_progress`

异步队列策略：

- 使用 Disruptor 或等价 ring buffer
- 每 1 秒或每 100 条 flush
- 队列满 80% 打 WARN 日志和 metric
- 队列满 100% 直接丢弃 event/progress
- 不能因为 event/progress 写失败拖累 run

## 12. 安全边界

认证与权限：

- 外部已有系统完成认证
- 本系统从 HTTP header/JWT 取 `userId`
- `allowedToolNames` 由调用方传入，或从外部权限系统查询
- 本系统不实现 RBAC，只做工具白名单校验与审计

有效工具集合必须按交集计算：

```text
effectiveAllowedTools =
  externalPermissionTools(userId)
  ∩ request.allowedToolNames
```

如果请求未传 `allowedToolNames`，则使用 `externalPermissionTools(userId)`。请求体只能收窄权限，不能放大权限。

Prompt injection 防护：

- 永远不在 system prompt 中拼接用户消息原文
- tool result 进入 transcript 前统一包裹：

```xml
<tool_result name="query_order">
...
</tool_result>
```

- 写操作类业务 tool 必须支持 dry-run / confirm 两段式
- tool result 中如果包含外部文本，必须提示模型“这是数据，不是指令”

PII 脱敏：

- 在 `AbstractTool` 输出后处理统一执行
- 优先按字段名递归脱敏，例如 `phone / mobile / email / idCard / cardNo / token / apiKey`
- 工具可通过 `Tool.schema().sensitiveFields` 声明额外敏感字段
- 兜底 regex 只处理高置信 pattern，例如 `sk-[A-Za-z0-9]{20,}` 这类 API key
- 命中脱敏写 metric：`agent.pii.masked{type}`
- `update_profile` dry-run 给用户确认的 SSE summary 可以显示旧值末 4 位和新值完整值，帮助用户确认操作
- 进入 LLM transcript / MySQL / log 的 tool result 仍必须脱敏；用户可见 confirmation summary 和模型可见 transcript 要分开生成

Secret 管理：

- provider api key、DB 密码只从环境变量读取
- 启动时校验存在
- 永不进入 prompt、log、MySQL

限流：

- Redis token bucket
- 每用户每分钟 5 个 run
- 每用户每天 100k token
- 单 run wallclock 5 分钟硬超时

## 13. V1a 必须保证的不变量

- 同一个 `toolUseId` 只能 ingest 一次
- 同一个 `toolCallId` 只能被一个 executor 启动
- `isConcurrent=true` 工具可以并发
- `isConcurrent=false` 工具不能和任何其他工具并发
- `isConcurrent=false` 后面的工具不能越过它
- 工具完成时，只有 `attempt + leaseToken + status` 匹配，结果才能写回
- 每个 assistant tool call 最终必须有一个 matching tool result
- cancel、timeout、abort、executor reject 必须通过 synthetic tool result 闭合 transcript
- provider attempt 未成功完成前，tool call 不能进入 Redis
- 失败 provider partial tool call 不得污染下一轮请求
- SSE `text_delta` 是 provisional output，只有 `final` event 或 committed assistant message 才代表正式回答
- 写操作 dry-run 返回 `ToolStatus=SUCCEEDED`，业务结果中包含 `actionStatus=PENDING_CONFIRM`
- run 进入 `WAITING_USER_CONFIRMATION` 后，只能通过继续对话接口追加用户确认/取消消息
- `systemPromptHash / toolSchemaHash` 必须在 run 内稳定
- 关键 trajectory 写失败时必须 fail closed，不能静默执行工具
- event/progress 写失败不能拖累 run
- 应用启动 repair 必须关闭非 terminal run 中的 orphan assistant tool call
- `GET /api/agent/runs/{runId}`、continue、abort 都必须校验 run 归属
- continuation 不能重新计算或放大 allowed tools，只能读取 create run 时持久化的 `effectiveAllowedTools`
- abort / timeout 后，RUNNING tool 的正常 complete 不能绕过 run control 继续写入正常结果
- 对外 trajectory response 不能直接暴露 MyBatis entity、provider raw diagnostic、未脱敏 PII 或不必要的 confirmToken

## 14. Context 生命周期

V1a 不做 context compact，只做硬上限保护：

- 请求 provider 前估算 token
- 超过模型上下文预算时，run fail closed
- 返回清晰错误：上下文过长，需要新建 run 或等待 V2 compact

V2 实现 context compact。压缩只作用于“发送给 provider 的 message view”，不能删除 MySQL 原始 trajectory。压缩前后都必须通过 `TranscriptPairValidator`，保证 assistant tool call 与 tool result 仍然配对。

V2 支持三种压缩方式，按顺序执行：

### 14.1 Large Tool Result Spill

当单个 tool result 估算超过 2000 token 时，provider view 中只保留头部 200 token 与尾部 200 token，中间用逻辑占位符替换：

```xml
<resultPath>trajectory://runs/{runId}/tool-results/{toolCallId}/full</resultPath>
```

要求：

- MySQL 中的完整 tool result 不删除
- `resultPath` 是逻辑引用，不要求 V2 引入对象存储
- 用户查询 trajectory 时仍能看到完整结果，必要时由 API 分页返回
- tool result role、`toolUseId`、tool name、status 不变，只改变发给 provider 的 content view

### 14.2 Micro Compact

当待发送上下文达到 50000 token 时，系统可以把较旧、低价值、已被后续回答消费过的指定 tool result 替换为：

```xml
<oldToolResult>Tool result is deleted due to long context</oldToolResult>
```

要求：

- 只能压缩旧 tool result，不能压缩当前 turn 需要模型继续推理的结果
- 不能删除 tool result message 本身，否则 provider 会认为 assistant tool call 缺少结果
- 压缩选择需要写入 `agent_context_compaction`，记录被压缩 message id、原因、token 前后变化

### 14.3 LLM Summary Compact

当 large spill 与 micro compact 后仍然超过预算时，使用 LLM 对较早历史做 summary，并用 summary message 替换一段历史 view。

保留规则：

- `SYSTEM` message 永远保留
- 前三条非 system message 保留
- 最后三条 message 保留
- 如果最后三条 message 总长度小于 2000 token，可以继续向前多保留；直到再多一条会超过 2000 token 为止
- assistant tool call 与 matching tool result 必须一起保留或一起进入 summary，不能拆散

summary 存储：

- summary message 使用内部 `ASSISTANT` role
- `LlmMessage.extras` 记录 `compactSummary=true`
- `extras.compactedMessageIds` 记录被 summary 覆盖的 message id
- summary 内容是 JSON，至少包含 `summaryText`、`openQuestions`、`businessFacts`、`toolFacts`、`compactedMessageIds`
- `businessFacts` 记录稳定业务事实，例如订单号、用户意图、候选商品、退换货状态；避免 summary 只有自然语言叙述，后续模型难以可靠引用
- `toolFacts` 记录工具已经查到或执行过的事实，例如调用了哪个工具、返回了什么状态、哪些操作已经 dry-run 或 confirmed；避免重复调用或误以为操作已完成
- `openQuestions` 记录仍未解决的问题、需要用户确认的信息或被暂停的任务；让 compact 后的下一轮知道“还缺什么”，而不是只知道“发生过什么”

如果 V2 compact 后仍然超过 provider 上下文预算，run fail closed，返回“上下文仍过长，需要新建 run 或缩小任务范围”。

## 15. 分期范围

V1a 必须交付：

- AgentLoop 基础闭环：LLM -> assistant tool_call -> tool_result -> LLM final
- 单 provider DeepSeek adapter，OpenAI-compatible streaming
- MySQL trajectory：run / message / llm attempt / tool call / tool result
- 关键路径同步写，`agent_event` 与 `agent_tool_progress` 异步批量写
- Redis ToolRuntime：ingest / schedule / complete CAS / abort / cancel synthetic result
- 2-3 个业务 tool 作为示例，并预留 `BusinessTool` 抽象基类供后续扩展
- `TranscriptPairValidator`：每次 provider 请求前校验配对
- 最小 lease reaper
- HTTP REST + SSE
- 限流、PII 脱敏、prompt injection 基础防护
- run continuation：`POST /api/agent/runs/{runId}/messages`
- startup repair：关闭崩溃遗留的 orphan transcript

V1a 暂不做：

- Provider fallback / 多 provider
- Skill 渐进式加载
- AgentTool / SubAgent
- ToDo / Reminder
- Context Compact
- 内置文件工具 `Read/Write/Edit/Zip/Unzip`
- Redis key 级 `generation`
- Kafka / RabbitMQ / workflow engine
- 对象存储
- 完整幂等恢复 / outbox

V2 必须交付：

- 多 provider：在 DeepSeek 基础上接入 Qwen，不接入 GLM
- Provider adapter registry：按 run context 选择 provider，并把 provider 方言封装在 adapter/profile 中
- Provider fallback：DeepSeek 与 Qwen 之间可配置主备顺序；fallback 只发生在安全阶段
- Context compact：large tool result spill、micro compact、LLM summary compact
- Skill 渐进式加载：使用 Anthropic Agent Skills 格式，初始 prompt 只放 `name + description` preview
- `skill_list` / `skill_view` 两个工具
- `AgentTool` / SubAgent：通过普通 tool 创建 child run；SubAgent 继承 MainAgent 的 tool/skill 能力集合，但不继承 MainAgent 的 message history 或完整 context
- `ToDoCreate` / `ToDoWrite`：复杂任务拆解与短期任务状态提醒
- ToolResultWaiter 从 polling 演进为 Redis Pub/Sub，同时保留 polling fallback
- 多实例部署：Active Runs Set + per-instance sweeper
- 每个用户 turn 内，MainAgent 与每个 SubAgent 各自最多调用 LLM 30 次；超过后进入 `PAUSED`
- 用户中断：当前 turn 主动结束，未启动工具不再执行，运行中工具收到 cancellation token，SubAgent 级联中断
- AgentTool 同步等待 SubAgent 返回，默认最多等待 3 分钟；超时后给 SubAgent 发送中断，并向 MainAgent 返回超时/部分结果
- V2 的新增能力必须继续复用 V1a 的 MySQL trajectory、Redis CAS、TranscriptPairValidator、安全确认、PII 脱敏和 run access 管理
- V2 不作为一次性大包并行交付，必须拆为 V2.0 / V2.1 / V2.2 三个里程碑顺序交付；每个里程碑独立执行 hardening review，沿用 V1a 的主 agent + sub agent + `java-alibaba-review` gate 流程

V2 明确不做：

- GLM provider
- Kafka / RabbitMQ / workflow engine
- 大结果对象存储，V2 只使用 trajectory logical path 指向 MySQL 原始结果
- 完整 outbox 幂等恢复
- Redis key 级 `generation` 隔离，除非 provider fallback 实现中发现 replay 污染风险必须处理

更远演进：

- generation 隔离
- 完整幂等恢复 / outbox
- MQ worker 解耦
- 大结果对象存储 spill

## 16. 测试场景

V1a 单元测试：

- `ToolCallAssembler` 能把 streaming tool call delta 组装成完整 `ToolCall`
- 失败 attempt 的 partial tool call 不进入 Redis
- `AttemptCommitter` 成功路径会写 MySQL assistant/tool_call，再 ingest Redis WAITING
- `TranscriptPairValidator` 能识别 orphan tool call / orphan tool result
- `ToolRegistry` 工具名规范化与冲突检测正确
- `Tool.schema()` 中的 `isConcurrent / timeout / maxResultBytes` 能进入 runtime metadata
- `AbstractTool` 会调用 validate，并执行输出大小限制与 PII 脱敏
- `cancel_order` dry-run / confirm 两段式正确执行
- dry-run tool terminal status 为 SUCCEEDED，业务 result.actionStatus 为 PENDING_CONFIRM
- confirmToken 必须校验 runId、userId、toolName、argsHash 和过期时间
- PENDING_CONFIRM 会把 run 状态推进为 WAITING_USER_CONFIRMATION
- 用户拒绝确认时，confirmToken 清理，run 以 SUCCEEDED 结束并说明未执行写操作
- WAITING_USER_CONFIRMATION 超过 confirm token TTL 未确认时，run 进入 TIMEOUT；不复用 tool 的 cancelReason
- confirm 阶段调用不同 toolName 或不同 orderId 导致 argsHash 不匹配时，返回 FAILED tool result
- 同一订单重复 dry-run 时，可以生成新的 confirmToken，并使旧 token 失效
- `update_profile` dry-run / confirm 两段式正确执行
- 限流命中后返回 429
- PII 脱敏触发后 metric +1，输出已脱敏
- 异步 event/progress 队列满后正确降级
- 单 run wallclock 超时后 abort，并生成 synthetic close
- startup repair 能关闭 assistant tool_call 已写但缺少 tool result 的 orphan run
- DeepSeek 建连前 429/5xx/网络错误会按 200ms、800ms 退避重试，stream 开始后中断不重试

Redis Lua 集成测试：

- 重复 `toolUseId` 只 ingest 一次
- 连续 `isConcurrent=true` 工具同批启动
- `concurrent + exclusive + concurrent` 不越过屏障
- `exclusive + concurrent` 中 exclusive 独占
- 并发 schedule 只会有一个调用拿到同一个 `toolCallId`
- 错误 `leaseToken` complete 返回 false
- 错误 `attempt` complete 返回 false
- abort 后 WAITING 工具变 `CANCELLED` 并生成 synthetic result
- cancel 后晚到 complete CAS 返回 false
- lease reaper 能处理过期 RUNNING

端到端测试：

- HTTP SSE 能流式返回 `text_delta / tool_use / tool_progress / tool_result / final`
- `POST /api/agent/runs/{runId}/messages` 能继续 WAITING_USER_CONFIRMATION run
- 用户说“取消我昨天的订单”，agent 能 query -> dry-run cancel -> 用户确认 -> confirm cancel -> final
- DeepSeek 返回 tool call 后，AgentLoop 完成 `LLM -> tool_result -> LLM final`
- 工具失败后状态为 FAILED，并继续把错误返回给 LLM
- executor 满载时不丢工具，任务回到 WAITING 或生成受控 synthetic failure
- MySQL 能按 `runId` 查询完整 trajectory
- 关键 MySQL 写入失败时 run fail closed
- event/progress 写失败不影响 final answer
- provider stream 中断时 partial tool call 不执行
- docker compose 启动后 seed data 可支撑固定演示场景
- SSE 会发送 ping，final 带 nextActionRequired
- graceful shutdown 会拒绝新请求、等待已有 run、超时 abort 并 synthetic close
- SSE 指标会记录连接数、事件发送数和客户端断开原因

V2 测试放入 V2 附录，不阻塞 V1a。

## 17. 成功标准

V1a 成功标准：

- 可以通过 HTTP SSE 演示一个真实业务流程，不只是文件读写 demo
- DeepSeek tool calling 闭环稳定运行
- Redis 能表达一个 run 的活跃 tool queue、lease、terminal 状态
- safe/non-safe 调度语义正确
- MySQL 可以追溯完整 run、messages、llm attempts、tool calls、tool results
- 每个 tool call 都有 matching tool result，不出现 orphan
- 写操作业务 tool 具备 dry-run / confirm 防护
- PII 脱敏、限流、secret 管理具备最小可用实现
- 单 run 超时、abort、executor reject、provider failure 都能受控结束
- 代码结构清晰，可自然扩展到 V2

## 18. 对外接口契约

### 18.1 创建 run 并接收 SSE

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

`userId` 从 header / JWT 获取，不允许由请求体覆盖。

SSE event：

```text
event: text_delta
data: {"attemptId":"att_1","delta":"我先帮你查询昨天的订单..."}

event: tool_use
data: {"toolUseId":"call_1","name":"query_order","args":{"date":"yesterday"}}

event: tool_progress
data: {"toolCallId":"tc_1","stage":"querying","message":"正在查询订单","percent":40}

event: tool_result
data: {"toolUseId":"call_1","status":"SUCCEEDED","result":{"count":1}}

event: final
data: {"runId":"run_123","finalText":"已为你取消订单 O-1001。","status":"SUCCEEDED","nextActionRequired":null}

event: error
data: {"message":"run timeout"}
```

说明：

- `text_delta` 是 provisional output，用于改善交互体验
- `text_delta` 必须携带 `attemptId`
- 服务端每 15 秒发送一次 `event: ping`
- `final` event 必须携带 `nextActionRequired`，取值 `null / user_confirmation / user_input`
- 只有 `final` event 表示本轮正式完成
- 如果 provider stream 中断，已经发出的 `text_delta` 不写入 `agent_message`
- 客户端应以 `final / error` 作为 UI 状态收敛点

### 18.2 继续同一个 run

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
- run 处于 `WAITING_USER_CONFIRMATION` 时继续推进同一个 run

行为：

- 校验 run 归属和状态
- 获取 Redis continuation lock，TTL 5 分钟
- 如果同一个 run 已有 continuation 在执行，返回 HTTP 409
- 追加 user message
- 重新进入 AgentLoop
- SSE 返回新的 `text_delta / tool_use / tool_result / final`
- 本轮结束或失败时释放 lock；TTL 用于异常兜底

当 tool result 包含 `actionStatus=PENDING_CONFIRM` 时，当前 SSE 应返回：

```text
event: final
data: {"runId":"run_123","status":"WAITING_USER_CONFIRMATION","finalText":"将取消订单 O-1001，请确认是否继续。","nextActionRequired":"user_confirmation"}
```

### 18.3 Abort run

```http
POST /api/agent/runs/{runId}/abort
Authorization: Bearer <jwt>
```

行为：

- 标记 run abort
- Redis 中 WAITING 工具进入 `CANCELLED`
- RUNNING 工具通过 cancellation token 协作取消
- 对需要闭合的 tool call 生成 synthetic tool result
- SSE 返回 `error` 或 `final(status=CANCELLED)`

### 18.4 Interrupt current turn

```http
POST /api/agent/runs/{runId}/interrupt
Authorization: Bearer <jwt>
```

V2 行为：

- 校验 run 归属和状态
- 设置 Redis `interrupt_requested`
- 当前 turn 不再发起新的 LLM 请求
- 未启动工具不再执行
- RUNNING 工具收到 cancellation token
- 如果存在 SubAgent child run，级联发送 interrupt
- 当前 run 进入 `PAUSED`，返回 `nextActionRequired=user_input`
- 已经产生的 assistant tool call 必须用 synthetic 或真实 terminal result 闭合，不能留下 orphan

### 18.5 查询 trajectory

```http
GET /api/agent/runs/{runId}
Authorization: Bearer <jwt>
```

返回：

- run metadata
- messages
- llm attempts
- tool calls
- tool results
- events
- progress

只能查询当前用户有权限访问的 run。

## 附录 A：V2 详细需求

V2 是 V1a 的自然增强版，把原先分散在后续阶段里的候选能力合并成一个版本。V2 的目标不是变成通用大而全 agent framework，而是在业务型 AgentLoop 上补齐“可换 provider、长上下文、渐进式技能、子任务代理、复杂任务跟踪、多实例推进”这六类能力。

### A.0 V2 里程碑拆分

V2 分三段顺序交付，不并行交付：

| 里程碑 | 内容 | 周期估算 | 验收信号 |
|---|---|---:|---|
| V2.0 | Multi-provider（DeepSeek + Qwen）+ Context compact（spill / micro / summary） | 1-2 周 | DeepSeek 故障可 fallback Qwen；50K token 上下文不 fail closed；compact summary 保留 `businessFacts/toolFacts/openQuestions` |
| V2.1 | Skill 渐进式加载 + SubAgent / AgentTool | 2-3 周 | `/skillName` slash 注入工作；SubAgent 继承 tool/skill 但不继承 history；child run trajectory 可独立查询 |
| V2.2 | 多实例部署 + Pub/Sub + ToDo + Interrupt | 2 周 | 双实例并发 schedule 不重复执行；interrupt 写入 `PAUSED`；ToDo reminder 每 3 turn 注入但不污染对外 conversation |

每个里程碑必须：

- 独立更新 task / progress
- 独立跑测试与回归
- 独立执行 `java-alibaba-review` hardening gate
- README 公开标注 phased roadmap，避免把尚未实现的 V2.1/V2.2 误描述成已完成

### A.1 V2 Provider

V2 provider 矩阵：

| Provider | 作用 | 协议 | 状态 |
|---|---|---|---|
| DeepSeek | 默认主 provider | OpenAI-compatible streaming | V1a 已有，V2 保留 |
| Qwen | 备用或可选主 provider | OpenAI-compatible streaming | V2 新增 |

要求：

- 不接入 GLM
- 不让业务代码直接依赖 provider SDK
- `LlmProviderAdapterRegistry` 按 provider type 找 adapter
- `ProviderFallbackPolicy` 决定哪些错误可以 fallback
- fallback 前必须确认当前 attempt 没有 committed transcript，也没有 Redis tool ingest
- fallback 事件必须写入 `agent_llm_attempt` 和 `agent_event`
- Qwen profile 必须独立测试 tool schema、stream tool delta、finish reason、usage mapping 和错误码 mapping

### A.2 V2 Skill 渐进式加载

Skill 使用 Anthropic Agent Skills 风格：

```text
skills/
  java-alibaba-review/
    SKILL.md
    references/
    scripts/
```

`SKILL.md` 必须包含 frontmatter：

```yaml
---
name: java-alibaba-review
description: 按阿里巴巴 Java 开发规范审查 Java/Spring Boot 代码
---
```

preview 只包含：

```json
{
  "name": "java-alibaba-review",
  "description": "按阿里巴巴 Java 开发规范审查 Java/Spring Boot 代码"
}
```

工具：

```text
skill_list()
skill_view(skillName, skillPath?)
```

要求：

- `skill_list` 不接收 `userId` 参数；`userId` 由 `ToolUseContext` 注入
- 初始 prompt 只放用户可访问 skill 的 preview，不放完整 `SKILL.md`
- `skill_view(skillName)` 返回该 skill 的完整 `SKILL.md`
- `skill_view(skillName, skillPath)` 返回 skill 根目录内指定文件
- `skillPath` 禁止 `..`、绝对路径、符号链接逃逸
- skill 内容作为 tool result 回填 transcript，仍受 PII/secret mask、maxResultBytes 和 context compact 约束
- skill 访问要写 trajectory，便于解释模型为什么使用了某个规则
- 用户消息中出现 `/skillName` 格式时，`SkillCommandResolver` 必须校验该 skill 存在且当前用户可访问
- `/skillName` 命中后，本轮 provider message view 必须加入该 skill 对应的完整 `SKILL.md`，用 `<skill name="skillName">...</skill>` 包裹
- `/skillName` 加载的 skill message 是 transient provider view，不改写 materialized system prompt，也不作为普通用户消息持久化；访问事件写入 `agent_event`
- 如果同一条用户消息中出现多个 `/skillName`，按出现顺序加载，并受单轮 skill 加载数量与 token budget 限制
- 单条用户消息中 `/skillName` 命中数量超过 `agent.skills.max-per-message`（默认 3），或加载后的 skill token 总和超过 `agent.skills.max-token-per-message`（默认 8000）时，整条 SSE 请求 fail closed
- skill budget 超限时返回 HTTP 400 或 SSE `error`，错误码 `SKILL_BUDGET_EXCEEDED`
- `SKILL_BUDGET_EXCEEDED` 错误体必须包含命中的 skill 列表、预算值、实际 token 估算和超出量
- 禁止静默截断 skill；模型少看一个被用户显式点名的 skill 是高隐蔽故障
- V2 项目内置 3 个业务 skill：`purchase-guide`、`return-exchange-guide`、`order-issue-support`

配置约束：

```yaml
agent:
  skills:
    max-per-message: 3
    max-token-per-message: 8000
```

### A.3 V2 SubAgent

`AgentTool` 是一个普通 tool，暴露给 LLM。模型调用 `AgentTool` 后，由系统创建 child run。

核心语义：

```text
parent run
  ↓
assistant calls AgentTool({
  agentType:"explore",
  task:"...",
  systemPrompt:"你是订单排查子代理，只负责查询与总结..."
})
  ↓
AgentTool 创建 child run
  ↓
child AgentLoop 使用 MainAgent 传入的 system prompt、任务输入、工具白名单和预算执行
  ↓
child result summary 作为 AgentTool result 回到 parent transcript
```

要求：

- `AgentTool` 本身实现 `Tool`
- 具体子 Agent 通过子类或 registry 定义，例如 `ExploreAgent`
- MainAgent 单 run 生命周期内最多创建 2 个 SubAgent，计数真相源为 Redis `agent:{run:<runId>}:children`
- MainAgent 同一时刻最多允许 1 个 in-flight SubAgent，避免单个 HTTP worker 被多个同步等待 child run 叠加占用
- MainAgent 单个 user turn 内最多尝试 spawn 2 个 SubAgent
- 超出 `max-spawn-per-run` 或 `max-concurrent-per-run` 时，`AgentTool` 必须立即返回 `SUBAGENT_BUDGET_EXCEEDED` 的 FAILED tool result，由 MainAgent 模型决定降级或自己处理
- SubAgent slot reserve 必须通过 Redis Lua 原子脚本完成：`reserve_child.lua` 在同一原子操作内检查 `max-spawn-per-run`、`max-concurrent-per-run` 与 `spawn-budget-per-user-turn`，并写入 `agent:{run:<runId>}:children` HASH；reserve 失败立即返回 `SUBAGENT_BUDGET_EXCEEDED`
- child terminal、timeout、interrupt 或 parent detached 时通过 `release_child.lua` 释放 in-flight 计数；spawn lifetime 计数不释放，因为它是整个 run 的累计上限
- `AgentTool.schema().description` 必须提示模型这是高成本工具，请谨慎使用
- MainAgent default system prompt 必须加入反滥用提示：仅在任务确实需要独立上下文时使用 AgentTool，单 run 累计最多 2 次，超出后直接处理

配置约束：

```yaml
agent:
  sub-agent:
    max-spawn-per-run: 2
    max-concurrent-per-run: 1
    spawn-budget-per-user-turn: 2
    wait-timeout-ms: 180000
```

这组限制把单 run worker 占用控制在可解释范围内：整个 run 最多 2 个 child，且串行等待时单个 HTTP worker 最多被一个 child 卡住 3 分钟。

`spawn-budget-per-user-turn` 用于防止单个 user turn 内突发 spawn；累计上限以 `max-spawn-per-run` 为准。当前两者默认值相同时，per-turn 限制不会先触发；未来如果调大 `max-spawn-per-run`，per-turn 限制才有独立意义。
- SubAgent 继承 MainAgent 的 tool 能力集合，默认可用工具为 parent run `effectiveAllowedTools`；MainAgent 可以按任务收窄，但不能放大
- SubAgent 继承 MainAgent 的 skill 能力集合，默认可访问 MainAgent 当前用户可访问的 skill preview、`skill_list` 和 `skill_view`
- 如果 MainAgent 通过 `/skillName` 为当前任务显式 pin 了某个 skill，委派给 SubAgent 时可以把该 skill 作为 task package 的一部分传入
- SubAgent 不继承 MainAgent 的 message history、ToDo 状态、压缩 view 或完整 trajectory context
- MainAgent 必须根据当前任务显式传入 SubAgent system prompt、task payload、工具/skill 收窄约束、maxTurns、token budget 和 LLM call budget
- SubAgent 的 system prompt 可以由 `SubAgentProfile` 提供默认模板，再由 MainAgent 传入 task-specific instructions 做受控渲染
- child run 必须写入 `parentRunId`、`parentToolCallId`、`agentType`
- child run 必须写入 `parentLinkStatus`，用于解释 child result 是否被 parent 使用
- parent transcript 不展开 child 全量消息，只保存 child result summary、状态、关键引用和 childRunId
- child trajectory 可通过查询 API 追溯
- 子 Agent 不能提升权限，只能使用 parent run effectiveAllowedTools 的子集
- 子 Agent 失败时，`AgentTool` 返回 FAILED tool result，让 parent 模型决定继续、降级或结束
- `AgentTool` 同步等待 SubAgent 返回，默认等待上限 3 分钟
- 如果 3 分钟仍未收到 SubAgent 返回，MainAgent 不再等待，`AgentTool` 向 SubAgent 发送 interrupt，并向 MainAgent 返回 `SUBAGENT_WAIT_TIMEOUT` 结果
- 如果 SubAgent 达到本 user turn LLM 调用上限 30 次仍未完成任务，SubAgent 必须总结当前已获得结果，作为 partial result 返回 MainAgent，然后自身进入 `PAUSED`
- SubAgent 返回给 MainAgent 的只能是结果总结、关键证据引用和状态，不能把全部 context 原样传回 MainAgent

`parentLinkStatus` 取值：

```text
LIVE
DETACHED_BY_TIMEOUT
DETACHED_BY_INTERRUPT
DETACHED_BY_PARENT_FAILED
```

规则：

- child 创建时为 `LIVE`
- `AgentTool` 等待超过 3 分钟后写 `DETACHED_BY_TIMEOUT`，并给 child run 发送 interrupt
- MainAgent 被 interrupt 级联 child 时写 `DETACHED_BY_INTERRUPT`
- MainAgent run 失败或取消时 child 仍未结束，写 `DETACHED_BY_PARENT_FAILED`
- Trajectory query DTO 必须暴露 `parentLinkStatus`，让用户理解这个 child 的结果有没有被 parent 用上

### A.4 V2 ToDo

V2 增加两个工具：

```text
ToDoCreate
ToDoWrite
```

用途：

- 当模型判断任务复杂、跨多个步骤、可能被中断时，调用 `ToDoCreate` 创建短期计划
- 每完成一个步骤，调用 `ToDoWrite` 更新状态
- 每 3 个 turn，AgentLoop 检查 Redis 中是否存在未完成 ToDo；如果有，向 provider view 注入 transient reminder message

ToDo 状态真相源是 Redis：

```text
agent:{run:<runId>}:todos
agent:{run:<runId>}:todo-reminder
```

ToDo step 状态：

```text
PENDING
IN_PROGRESS
DONE
BLOCKED
CANCELLED
```

要求：

- ToDo 是 agent 短期工作记忆，不是业务数据源
- ToDo 不替代 MySQL trajectory；关键变更仍写 `agent_event`
- reminder message 不伪装成用户输入；内部标记 `extras.todoReminder=true`
- reminder 不持久化为业务消息，避免污染对外 conversation；需要审计时写 agent_event
- run 结束时清理 Redis ToDo 或设置短 TTL

### A.5 V2 多实例与结果通知

V2 按多 Spring Boot 实例部署实现。Redis 仍是活跃状态真相源，MySQL 是 trajectory 真相源；任何请求都不能假设同一个 run 会继续落到同一台服务器。

新增 Redis key：

```text
agent:active-runs
agent:{run:<runId>}:pubsub:result
agent:{run:<runId>}:todos
agent:{run:<runId>}:todo-reminder
agent:{run:<runId>}:control
agent:{run:<runId>}:children
```

Active Runs Set：

- run 进入 active 状态时加入 `agent:active-runs`
- run 进入 terminal 状态时移除
- 每个实例都有 sweeper，每 2 秒扫描 active runs 并调用 schedule
- 真正的去重仍由 Redis Lua lease CAS 保证，不依赖 JVM 内存锁

Tool result 通知：

- V2 默认使用 Redis Pub/Sub 通知 tool terminal result
- `ToolResultWaiter` 订阅结果 channel，减少 polling 延迟
- Pub/Sub 丢消息时，保留短间隔 polling fallback，默认 polling interval 500ms

Sweeper 职责边界：

- `ActiveRunSweeper` 只允许调用 `RedisToolStore.schedule(runId)`
- `ActiveRunSweeper` 不得调用 `AgentLoop.run`、`AgentLoop.continueRun` 或任何 provider adapter
- `ActiveRunSweeper` 只负责推动 Redis 中已经 ingest 的 WAITING tool 进入 RUNNING
- 任何会产生新 LLM 调用、新 assistant message 或新 tool call ingest 的动作，必须由持有 continuation lock 的实例发起
- 实现侧要求 sweeper 路径不依赖 `LlmProviderAdapter`，通过编译期依赖边界降低误调用风险
- sweeper 每轮顺带检查 `agent:active-runs`，发现 MySQL 已 terminal 且超过 `active-run-stale-cleanup-ms`（默认 60000ms）的 run，主动 `SREM`

配置约束：

```yaml
agent:
  runtime:
    tool-result-pubsub-enabled: true
    tool-result-poll-interval-ms: 500
    active-run-sweeper-interval-ms: 2000
    active-run-stale-cleanup-ms: 60000
```

### A.6 V2 中断与 LLM 调用预算

V2 增加用户中断能力。中断不是 abort：

| 操作 | 语义 | Run 结果 |
|---|---|---|
| `abort` | 用户决定终止整个 run | `CANCELLED` |
| `interrupt` | 用户打断当前 turn，要求停止本轮剩余工作 | `PAUSED` |

中断入口：

```http
POST /api/agent/runs/{runId}/interrupt
Authorization: Bearer <jwt>
```

中断流程：

```text
用户发送 interrupt
  ↓
RunAccessManager 校验 run 归属
  ↓
Redis control 写 interrupt_requested=true
  ↓
当前 turn 的 AgentTurnOrchestrator 感知 interrupt
  ↓
不再发起新的 LLM 请求
  ↓
WAITING 工具转 CANCELLED synthetic
  ↓
RUNNING 工具收到 cancellation token
  ↓
如果存在 SubAgent child run，级联发送 interrupt
  ↓
当前 turn 结束，run -> PAUSED，nextActionRequired=user_input
```

工具中断要求：

- interrupt 后未启动的 tool 不得再执行
- scheduler 必须检查 `interrupt_requested`，中断后不再启动新的 WAITING tool
- RUNNING tool 必须通过 cancellation token 协作取消
- 写操作 tool 必须在真实业务副作用前检查 cancellation token
- 对已经写入 assistant tool call 但未产生结果的工具，必须生成 matching synthetic tool result，避免 orphan
- 对已经越过副作用边界的非幂等工具，不能伪造未执行；应记录真实 terminal 或 late terminal，并在 trajectory 中标记 interrupt 发生时间

SubAgent 中断要求：

- MainAgent 中断时，所有 child run 必须收到 interrupt
- SubAgent 收到 interrupt 后不再发起新的 LLM 请求，不再启动新工具
- SubAgent 尝试取消 RUNNING 工具，并把当前已获得结果总结为 partial result
- `AgentTool` 收到 child partial result 后，向 MainAgent 返回 `INTERRUPTED_PARTIAL` tool result

LLM 调用预算：

- 一个用户 turn 指一次 create run SSE 或 continuation SSE 的执行周期
- MainAgent 在单个用户 turn 内最多调用 LLM 30 次
- 每个 SubAgent 在自己的单个 child turn 内最多调用 LLM 30 次
- 这里的“调用 LLM”包括 primary provider 调用和 fallback provider 调用；建连前失败的 retry 也计入
- 整个 run 生命周期累计最多调用 LLM 80 次，防止多轮 continuation + SubAgent 叠加导致成本失控
- 任意一个预算先达到上限，都不再发起新的 provider 请求，当前 run 进入 `PAUSED`
- 触发原因必须写入 `agent_event`：`MAIN_TURN_BUDGET`、`SUB_TURN_BUDGET` 或 `RUN_WIDE_BUDGET`
- SubAgent 达到 30 次仍未完成时，必须先生成 partial summary 返回 MainAgent，再进入 `PAUSED`

配置约束：

```yaml
agent:
  agent-loop:
    llm-call-budget-per-user-turn: 30
    sub-agent-llm-call-budget-per-user-turn: 30
    run-wide-llm-call-budget: 80
```

AgentTool 等待超时：

- `AgentTool` 同步等待 SubAgent 返回
- 默认等待时间 3 分钟
- 超时后 MainAgent 不再等待，发送 interrupt 给 SubAgent
- `AgentTool` 向 MainAgent 返回 `SUBAGENT_WAIT_TIMEOUT` tool result，包含 childRunId、已知状态和可用 partial summary
- 如果 SubAgent 后续才完成，late result 不得自动注入 MainAgent transcript，只能保留在 child trajectory 中

### A.7 V2 测试

V2 必须新增测试：

- DeepSeek 与 Qwen 的 provider profile 分别能转换 tools/messages
- fallback 只在未提交 transcript 前发生
- stream 中断后不会把旧 provider partial toolUseId 带入新请求
- Qwen tool call delta 能被 `ToolCallAssembler` 正确组装
- large result spill 保留头 200 token、尾 200 token 和 `resultPath`
- micro compact 不破坏 tool call / tool result 配对
- summary compact 生成 JSON summary，并记录 compactedMessageIds
- compact 后 `TranscriptPairValidator` 通过
- `skill_list` 只返回 name + description
- `skill_view` 能读取 `SKILL.md` 和相对路径文件，并阻止路径逃逸
- 用户输入 `/purchase-guide` 时，本轮 provider message view 包含 `purchase-guide/SKILL.md`
- `AgentTool` 能创建 child run，并把 child summary 作为 parent tool result
- 子 Agent 不能使用 parent 未授权工具
- MainAgent 单 run 创建第 3 个 SubAgent 时，`AgentTool` 返回 `SUBAGENT_BUDGET_EXCEEDED`
- 同一 run 已有 in-flight SubAgent 时，再次调用 `AgentTool` 返回 `SUBAGENT_BUDGET_EXCEEDED`
- 两个线程或两个实例同时调用 `AgentTool` 时，`reserve_child.lua` 只能让一个 child 成功 reserve slot
- child terminal / timeout / interrupt 后，`release_child.lua` 释放 in-flight 计数但不减少 lifetime spawn 计数
- child run trajectory 暴露 `parentLinkStatus`
- timeout / interrupt / parent failed 三种场景下 `parentLinkStatus` 正确从 `LIVE` 迁移到对应 detached 状态
- SubAgent 继承 MainAgent tool/skill 能力集合，但不继承 MainAgent message history、ToDo 状态或完整 context
- AgentTool 等待 SubAgent 超过 3 分钟后发送 interrupt，并向 MainAgent 返回 timeout result
- SubAgent 达到 30 次 LLM 调用后返回 partial summary 并进入 PAUSED
- `ToDoCreate` 创建 Redis plan，`ToDoWrite` 更新 step
- 每 3 turn 注入 ToDo reminder，且不持久化为用户消息
- 多实例并发 schedule 不重复执行同一 tool
- ActiveRunSweeper 只调用 `RedisToolStore.schedule(runId)`，不能依赖 provider 或 AgentLoop
- Active Runs sweeper 能推进因事件丢失而卡住的 run
- MySQL 已 terminal 超过 60s 的 run 会从 `agent:active-runs` 清理
- Redis Pub/Sub 丢消息时 polling fallback 能拿到 terminal result
- run-wide LLM call budget 达到 80 时进入 `PAUSED`，并写 `RUN_WIDE_BUDGET` event
- 用户 interrupt 后当前 turn 结束，WAITING 工具不再执行，RUNNING 工具收到 cancellation token
- MainAgent interrupt 会级联中断所有 child run
- late SubAgent result 不会污染 MainAgent transcript
