# agent-buyer

`agent-buyer` 是一个基于 Java Spring Boot 的订单系统 AI 赋能层。它让买家可以通过自然语言查询订单，并通过安全的 dry-run / 用户确认流程取消订单。

V1a 版本刻意保持小而完整：只做一个业务域、真实 SSE 流式响应、Redis 工具调度、MySQL 全链路轨迹，以及足够支撑演示和工程讲解的安全性与可观测性。V2.0 在这个闭环上补齐 DeepSeek + Qwen provider fallback、LLM 调用预算和 context compact。V2.1 增加 Anthropic-style skill 渐进式加载和同步 SubAgent 委派。

## 架构

```text
HTTP REST + SSE
  -> AgentController
  -> AgentRunApplicationService
  -> RunAccessManager + AgentRequestPolicy
  -> DefaultAgentLoop
  -> AgentTurnOrchestrator
  -> LlmAttemptService + ProviderRegistry
       - DeepSeek primary
       - Qwen fallback
       - pre-stream retry/fallback boundary
  -> ContextViewBuilder
       - /skillName transient skill injection
       - large tool result spill
       - 50K micro compact
       - summary compact
  -> ToolCallCoordinator + ToolResultCloser
  -> Redis ToolRuntime
       - ingest / schedule / complete CAS
       - abort_requested control signal
       - concurrent query tools
       - exclusive write tools
       - lease reaper
  -> ToolRegistry
  -> Order tools
       - query_order
       - cancel_order dry-run / confirm
       - skill_list / skill_view
       - agent_tool -> ExploreAgent child run
  -> MySQL trajectory
       - run / message / llm attempt
       - tool call / tool result
       - context compaction
       - child run parent link
       - async event / tool progress
```

## 代码导航

详细 package 说明见 [spec/package-architecture.md](spec/package-architecture.md)。常用入口：

```text
web.controller        HTTP/SSE 入口
application           run 生命周期、确认、中断、修复、限流
loop                  agent loop、LLM attempt、tool call 协调
llm.provider          DeepSeek / Qwen provider adapter
llm.context           provider view 组装、slash skill / ToDo transient injection
llm.compact           large result spill、micro compact、summary compact
tool.core             Tool 接口、schema、执行上下文
tool.model            ToolCall / ToolTerminal / ToolStatus 等状态模型
tool.runtime          RedisToolRuntime、执行器、waiter、closer、sweeper、pubsub
tool.runtime.redis    LuaRedisToolStore 与 Redis key
tool.builtin.order    query_order / cancel_order
skill                 Skill registry、slash command、skill_list / skill_view
subagent              SubAgent profile/runtime/AgentTool
todo                  ToDo store/tools/reminder
trajectory            trajectory port/store/query/dto
business              订单与用户业务 client/model
persistence           MyBatis entity/mapper
```

## 当前范围

已实现：

- DeepSeek OpenAI-compatible streaming adapter
- Qwen OpenAI-compatible streaming adapter，默认模型 `qwen-plus`
- provider registry / compatibility profile / fallback policy
- RunContext 持久化 provider 选型，continuation 不会被当前请求或配置默认值静默覆盖
- fallback 只发生在建连前、429、5xx、网络错误等 pre-stream retryable failure；stream 已产生 tool delta 后不 fallback
- MainAgent 单 user turn 30 次 LLM call、run-wide 80 次 LLM call，超限进入 `PAUSED`
- context compact 三阶段：
  - tool result > 2000 token 时保留头 200 token、尾 200 token，中间写 `<resultPath>`
  - provider view 达到 50000 token 时将旧 tool result 替换为 `<oldToolResult>`
  - summary compact 输出 `summaryText`、`businessFacts`、`toolFacts`、`openQuestions`、`compactedMessageIds`
- `agent_context_compaction` 记录 compact strategy、token 前后变化、message ids、turn/attempt attribution
- Anthropic-style skill 扫描，preview 只包含 `name + description`
- 内置 3 个业务 skill：`purchase-guide`、`return-exchange-guide`、`order-issue-support`
- `skill_list` / `skill_view` 渐进式加载，`skill_view` 禁止 `..`、绝对路径和符号链接逃逸
- 用户消息中的 `/skillName` 会在本轮 provider view transient 注入完整 `SKILL.md`，不会持久化为对外 user message
- slash skill budget fail closed：单条消息最多 3 个 skill，总计最多 8000 token，超限返回 `SKILL_BUDGET_EXCEEDED`
- `agent_tool` 可创建同步 SubAgent；当前提供 `ExploreAgent`
- SubAgent 继承 parent 的 tool/skill 能力集合，但不继承 parent history、ToDo 或 compact view
- SubAgent 单 run 累计最多 2 个 child，同一时刻最多 1 个 in-flight child，Redis Lua 原子 reserve/release
- child run trajectory 可独立查询，并通过 `parentRunId`、`parentToolCallId`、`agentType`、`parentLinkStatus` 解释归属
- 每个 SubAgent user turn 最多 30 次 LLM call，超限进入 `PAUSED` 并写 `SUB_TURN_BUDGET`
- HTTP REST + SSE 事件：`text_delta`、`tool_use`、`tool_progress`、`tool_result`、`final`、`error`、`ping`
- 订单工具：`query_order`、`cancel_order`
- Redis safe / non-safe 工具调度器，使用 Lua CAS 保证状态原子性
- run 归属校验、run context 工具权限固化、请求预算校验
- abort / timeout 通过 Redis `abort_requested` 传播到 scheduler、runtime、complete CAS 与工具 cancellation token
- interrupt 通过独立 `interrupt_requested` 控制位让当前 turn 进入 `PAUSED`，并级联中断 active child run
- 多实例 active run sweeper、Redis Pub/Sub tool result notification、500ms polling fallback
- ToDo 工具：`todo_create`、`todo_write`，每 3 turn 向 provider view 注入 transient reminder
- `ToolResultCloser` 统一闭合 precheck failure、timeout、abort、executor reject 等 synthetic tool result
- `HumanIntentResolver` 先用规则判断高置信确认/拒绝；规则不命中时调用 LLM 做结构化语义判断，低置信或失败时 fail closed 为追问
- 写操作 dry-run / confirmToken 二段式确认
- confirmToken 绑定 `runId + userId + toolName + argsHash`
- 工具缺少可补充参数时返回 recoverable precheck result，run 进入 `PAUSED` 并返回 `nextActionRequired=user_input`
- MyBatis Plus 持久化与 Flyway 数据库迁移
- 基于 `runId` 的完整 trajectory 查询
- 限流、prompt injection 边界提示、PII 脱敏、密钥从环境变量读取
- lease reaper、startup repair、graceful shutdown
- Micrometer metrics 与 `/actuator/prometheus`

## V2 phased roadmap

V2 不作为一次性大包并行交付，而是分阶段推进，每个里程碑独立做 hardening review，沿用 V1a 的主 agent 分发、sub agent 并行开发、`java-alibaba-review` gate 流程。

| 里程碑 | 内容 | 周期估算 | 验收信号 |
|---|---|---:|---|
| V2.0 | Multi-provider（DeepSeek + Qwen）+ Context compact（spill / micro / summary） | 已完成 | DeepSeek 故障可 fallback Qwen；50K token 上下文不 fail closed；compact summary 保留 `businessFacts/toolFacts/openQuestions` |
| V2.1 | Skill 渐进式加载 + SubAgent / AgentTool | 已完成 | `/skillName` slash 注入工作；SubAgent 继承 tool/skill 但不继承 history；child run trajectory 可独立查询 |
| V2.2 | 多实例部署 + Pub/Sub + ToDo + Interrupt | 已完成 | 双实例并发 schedule 不重复执行；interrupt 写入 `PAUSED`；ToDo reminder 每 3 turn 注入但不污染对外 conversation |

V2 明确不做：GLM、RAG / embedding / vector store、Kafka / RabbitMQ / workflow engine、完整 outbox、大结果对象存储。

## V3 Console

V3 提供 React 前端 Console，用于观察和操作 Agent Buyer 的 run 执行过程。

### 启动 Console

```bash
# 1. 启动后端（如果尚未运行）
export MYSQL_PASSWORD='<local mysql password>'
export DEEPSEEK_API_KEY='<your deepseek api key>'
export QWEN_API_KEY='<your qwen api key>'
mvn spring-boot:run

# 2. 启动前端
cd admin-web && npm run dev

# 3. 访问
# 浏览器打开 http://localhost:5173
```

一键启动脚本：

```bash
./scripts/start-console-dev.sh
```

### Console 功能

- **Run List**: 分页浏览所有 run，支持按状态和 userId 过滤
- **Timeline**: 查看完整 trajectory 节点（MESSAGE、LLM_ATTEMPT、TOOL_CALL、TOOL_PROGRESS、TOOL_RESULT、EVENT、COMPACTION）
- **Runtime State**: Debug Drawer 显示 Redis runtime state（meta、queue、tools、leases、children、todos）
- **Chat**: 创建新 run、发送消息、处理 HITL 确认、PAUSED 输入
- **Run Controls**: New Chat、Refresh、Interrupt、Abort

### 推荐测试 Prompt

```
取消我昨天的订单
```

```
查询我最近三天的订单状态
```

Console 详细说明见 [admin-web/README.md](admin-web/README.md)。

## 本地服务

默认本地端口与当前开发环境一致：

```text
MySQL 8: 127.0.0.1:3307，database agent_buyer
Redis 7: 127.0.0.1:6380，无密码
```

启动已有本地容器：

```bash
docker start agent-buyer-mysql8 agent-buyer-redis7
docker ps --filter name=agent-buyer
```

也可以使用完整 Docker demo 栈：

```bash
export DEEPSEEK_API_KEY='<your deepseek api key>'
docker compose up --build
```

如果 Docker Hub 拉取基础镜像超时，可以直接使用本地 Java 启动方式，不影响 V1a 功能验证。

## 运行测试

```bash
MYSQL_PASSWORD='<local mysql password>' mvn test
```

## 关键配置

`src/main/resources/application.yml` 已显式放出 V2 相关配置：

```yaml
agent:
  agent-loop:
    llm-call-budget-per-user-turn: 30
    sub-agent-llm-call-budget-per-user-turn: 30
    run-wide-llm-call-budget: 80
  llm:
    provider: deepseek # summary compact provider 默认值；主 run V2.0 固定 DeepSeek primary + Qwen fallback
    deepseek:
      default-model: ${DEEPSEEK_MODEL:deepseek-reasoner}
    qwen:
      default-model: ${QWEN_MODEL:qwen-plus}
  context:
    large-result-threshold-tokens: 2000
    large-result-head-tokens: 200
    large-result-tail-tokens: 200
    micro-compact-threshold-tokens: 50000
    summary-compact-threshold-tokens: 30000
    recent-message-budget-tokens: 2000
  skills:
    root-path: ${AGENT_SKILLS_ROOT:classpath:skills}
    enabled-skill-names:
      - purchase-guide
      - return-exchange-guide
      - order-issue-support
    max-per-message: 3
    max-token-per-message: 8000
  sub-agent:
    max-spawn-per-run: 2
    max-concurrent-per-run: 1
    spawn-budget-per-user-turn: 2
    wait-timeout-ms: 180000
  runtime:
    tool-result-pubsub-enabled: true
    tool-result-poll-interval-ms: 500
    active-run-sweeper-enabled: true
    active-run-sweeper-interval-ms: 2000
    active-run-stale-cleanup-ms: 60000
    interrupt-enabled: true
  todo:
    reminder-turn-interval: 3
```

V2.0 主 run 路由固定为 DeepSeek primary + Qwen fallback。新建 run 的 provider 选型会落入 `agent_run_context`，continuation 始终复用该 RunContext；修改 `agent.llm.provider` 不会把主 run primary provider 切到 Qwen，它只影响 provider-backed summary compact 的默认 provider。

## 本地启动应用

```bash
export MYSQL_PASSWORD='<local mysql password>'
export DEEPSEEK_API_KEY='<your deepseek api key>'
export QWEN_API_KEY='<your qwen api key>'
mvn spring-boot:run
```

健康检查与指标：

```bash
curl http://127.0.0.1:8080/actuator/health
curl http://127.0.0.1:8080/actuator/prometheus
```

## 查看日志

应用启动后会在项目根目录写入本地日志文件：

```text
logs/agent-buyer.log
logs/agent-buyer-error.log
```

实时查看：

```bash
tail -f logs/agent-buyer.log
```

日志会带上 `requestId`、`userId`、`runId`、`toolCallId`、`attemptId`、HTTP method/path，方便把一次 Postman 请求、LLM attempt、tool call 和 MySQL trajectory 串起来看。

## Postman 测试

应用启动后，基础地址为：

```text
http://127.0.0.1:8080
```

### 1. 创建 run

```http
POST http://127.0.0.1:8080/api/agent/runs
Content-Type: application/json
Accept: text/event-stream
X-User-Id: demo-user
```

Body:

```json
{
  "messages": [
    {
      "role": "user",
      "content": "取消我昨天的那个订单。请先查询订单，如果需要确认，请给出确认摘要。"
    }
  ],
  "allowedToolNames": ["query_order", "cancel_order"],
  "llmParams": {
    "model": "deepseek-reasoner",
    "temperature": 0.2,
    "maxTokens": 4096
  }
}
```

返回是 SSE 流。第一段通常会执行：

```text
query_order
  -> cancel_order dry-run
  -> final: WAITING_USER_CONFIRMATION
```

从响应里的 `runId` 取值，继续下一步确认。

### 2. 确认取消订单

```http
POST http://127.0.0.1:8080/api/agent/runs/{runId}/messages
Content-Type: application/json
Accept: text/event-stream
X-User-Id: demo-user
```

Body:

```json
{
  "message": {
    "role": "user",
    "content": "确认取消这个订单"
  }
}
```

确认后，agent 会再次调用 `cancel_order`，带上服务端签发的 `confirmToken`，真正执行取消。

### 3. 查询 trajectory

```http
GET http://127.0.0.1:8080/api/agent/runs/{runId}
X-User-Id: demo-user
```

trajectory 会返回本次 run 的完整轨迹，包括：

- run 状态
- messages
- llm attempts
- tool calls
- tool results
- SSE events
- tool progress

## 5 步演示流程

重置种子订单：

```bash
MYSQL_PASSWORD='<local mysql password>' ./scripts/reset-demo-order.sh
```

运行脚本化 SSE demo：

```bash
./scripts/demo-cancel-order.sh
```

运行真实 LLM 端到端套件。该脚本默认用 18080 端口临时启动应用，真实调用 DeepSeek 与 Qwen，覆盖订单取消、ToDo、SubAgent、interrupt、skill 渐进式加载、三类 context compact，以及 DeepSeek 故障后 Qwen fallback。它不会进入默认 `mvn test`，避免本地单测依赖外部模型服务：

```bash
export MYSQL_PASSWORD='<local mysql password>'
export DEEPSEEK_API_KEY='<deepseek api key>'
export QWEN_API_KEY='<qwen api key>'
./scripts/real-llm-e2e.sh
```

产物默认写入 `/tmp/agent-buyer-real-llm-e2e/<timestamp>`，包括每个场景的应用日志、SSE 响应、请求体、summary 和 trajectory。脚本会依次验证：

- `query_order -> cancel_order dry-run -> WAITING_USER_CONFIRMATION -> confirm -> SUCCEEDED`
- `todo_create` / `todo_write` 及 ToDo 事件落库
- `agent_tool -> ExploreAgent` child run 创建、parent-child link 与 result summary
- `POST /api/agent/runs/{runId}/interrupt` 将 active turn 置为 `PAUSED`
- `/purchase-guide` slash 注入、`skill_list`、多次 `skill_view` 与 `query_order`
- `LARGE_RESULT_SPILL`、`MICRO_COMPACT`、`SUMMARY_COMPACT`
- DeepSeek pre-stream failure 后 fallback 到 Qwen 并完成最终回答

手动流程：

1. 用户说：`取消我昨天的那个订单`
2. Agent 调用 `query_order`
3. Agent 调用不带 `confirmToken` 的 `cancel_order`，返回 `PENDING_CONFIRM`
4. 用户通过 `POST /api/agent/runs/{runId}/messages` 确认
5. Agent 带 `confirmToken` 再次调用 `cancel_order`，最后返回完成答复

初始请求 curl：

```bash
curl -N \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -H 'X-User-Id: demo-user' \
  -d '{
    "messages":[{"role":"user","content":"取消我昨天的那个订单"}],
    "allowedToolNames":["query_order","cancel_order"],
    "llmParams":{"model":"deepseek-reasoner","temperature":0.2,"maxTokens":4096}
  }' \
  http://127.0.0.1:8080/api/agent/runs
```

确认请求 curl：

```bash
curl -N \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -H 'X-User-Id: demo-user' \
  -d '{"message":{"role":"user","content":"确认取消这个订单"}}' \
  http://127.0.0.1:8080/api/agent/runs/<runId>/messages
```

查询轨迹 curl：

```bash
curl -H 'X-User-Id: demo-user' http://127.0.0.1:8080/api/agent/runs/<runId>
```

越权、非法请求、用户拒绝确认和 abort 等负向场景已放入 Postman collection：

```text
postman/agent-buyer-v1a.postman_collection.json
```

## V2.0 smoke

V2.0 的 provider/context smoke 不依赖真实 provider 故障注入，使用稳定的 fake provider 与 adapter 单测验证：

```bash
./scripts/v2-provider-context-smoke.sh
```

覆盖内容：

- Qwen provider profile 与 stream tool delta 解析
- RunContext provider/model 复用
- pre-stream fallback、stream-started 不 fallback、fallback disabled
- 50K context compact 的 spill / micro / summary 串联
- `PAUSED` 状态迁移与 budget exceeded

## V2.1 Skill 与 SubAgent 验证

Slash skill 示例：

```bash
curl -N \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -H 'X-User-Id: demo-user' \
  -d '{
    "messages":[{"role":"user","content":"/purchase-guide 帮我查询我最近的订单，并判断是否适合继续购买同类商品"}],
    "allowedToolNames":["query_order","skill_list","skill_view"],
    "llmParams":{"model":"deepseek-reasoner","temperature":0.2,"maxTokens":4096}
  }' \
  http://127.0.0.1:8080/api/agent/runs
```

SubAgent 示例：

```bash
curl -N \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -H 'X-User-Id: demo-user' \
  -d '{
    "messages":[{"role":"user","content":"如果需要独立梳理我的订单情况，可以使用 ExploreAgent 帮我调查最近订单，再总结结果"}],
    "allowedToolNames":["query_order","skill_list","skill_view","agent_tool"],
    "llmParams":{"model":"deepseek-reasoner","temperature":0.2,"maxTokens":4096}
  }' \
  http://127.0.0.1:8080/api/agent/runs
```

SubAgent 是同步等待模型：单 run 累计最多 2 个 child，同一时刻最多 1 个 in-flight child，单个 child 最多等待 3 分钟。超时后 parent 不继续等待，child run 的 `parentLinkStatus` 会变为 `DETACHED_BY_TIMEOUT`。

## V2.2 多实例、ToDo 与 Interrupt 验证

ToDo 示例：

```bash
curl -N \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -H 'X-User-Id: demo-user' \
  -d '{
    "messages":[{"role":"user","content":"这个任务比较复杂，请先拆成 ToDo，再查询我最近订单并逐步更新进度"}],
    "allowedToolNames":["query_order","todo_create","todo_write"],
    "llmParams":{"model":"deepseek-reasoner","temperature":0.2,"maxTokens":4096}
  }' \
  http://127.0.0.1:8080/api/agent/runs
```

Interrupt 示例：

```bash
curl -X POST \
  -H 'X-User-Id: demo-user' \
  http://127.0.0.1:8080/api/agent/runs/<runId>/interrupt
```

多实例本地 smoke 可以启动两个应用实例连接同一组 MySQL / Redis：

```bash
MYSQL_PASSWORD='<local mysql password>' SERVER_PORT=8080 mvn spring-boot:run
MYSQL_PASSWORD='<local mysql password>' SERVER_PORT=8081 mvn spring-boot:run
```

V2.2 的状态一致性验证重点：

- `agent:active-runs` 由每个实例的 sweeper 兜底扫描，terminal run 超过 60s 会主动清理。
- `schedule.lua` 仍是唯一 lease 真相源，两个实例同时 schedule 同一 run 不会重复启动同一个 tool。
- Tool result Pub/Sub 只做低延迟通知；真实 terminal state 仍从 Redis HASH 读取，丢通知时 500ms polling 兜底。
- `interrupt_requested` 与 `abort_requested` 共存；interrupt 进入 `PAUSED`，abort 进入 `CANCELLED`。

## 数据库查看

MySQL 连接信息：

```text
Host: 127.0.0.1
Port: 3307
Database: agent_buyer
Username: root
```

命令行连接：

```bash
mysql -h127.0.0.1 -P3307 -uroot -p agent_buyer
```

常用查询：

```sql
SHOW TABLES;

SELECT * FROM agent_run ORDER BY started_at DESC LIMIT 5;
SELECT run_id, parent_run_id, parent_tool_call_id, agent_type, parent_link_status, status
FROM agent_run
WHERE parent_run_id IS NOT NULL
ORDER BY started_at DESC
LIMIT 10;
SELECT * FROM agent_run_context ORDER BY created_at DESC LIMIT 5;
SELECT * FROM agent_message ORDER BY created_at DESC LIMIT 20;
SELECT * FROM agent_llm_attempt ORDER BY started_at DESC LIMIT 20;
SELECT * FROM agent_context_compaction ORDER BY created_at DESC LIMIT 20;
SELECT * FROM agent_tool_call_trace ORDER BY created_at DESC LIMIT 20;
SELECT * FROM agent_tool_result_trace ORDER BY created_at DESC LIMIT 20;
SELECT * FROM agent_event ORDER BY created_at DESC LIMIT 20;
SELECT * FROM agent_tool_progress ORDER BY created_at DESC LIMIT 20;
SELECT * FROM business_order;
```

Redis 连接信息：

```text
Host: 127.0.0.1
Port: 6380
Password: 无
Database: 0
```

命令行连接：

```bash
redis-cli -h 127.0.0.1 -p 6380
```

常用 Redis 命令：

```bash
KEYS agent:*
SMEMBERS agent:active-runs
```

## 设计取舍

- provider fallback 边界：V2.0 只在 pre-stream retryable failure fallback；一旦 provider stream 已经产生 text/tool delta，就由当前 attempt 失败收口，避免旧 tool_use_id 或 partial transcript 泄漏到新 provider 请求。
- 工具调用在 provider attempt 完整结束后提交：模型流中未完成的 partial tool call 不进入 Redis。
- context compact 只改 provider view：MySQL 原始 trajectory 不删除、不覆盖；compact 记录进入 `agent_context_compaction`，用于解释为什么 provider 请求和原始审计轨迹不同。
- Redis 调度，MySQL 追溯：Redis 负责活跃运行态；MySQL 负责 replay / audit / trajectory。
- Skill 渐进式加载：system prompt 只放 skill preview，完整 `SKILL.md` 只有通过 `/skillName` 或 `skill_view` 才进入 provider view，避免长期污染上下文。
- SubAgent 同步等待：V2.1 选择简单可演示的同步模型，并用 Redis 预算限制最坏资源占用；大规模并发委派推迟到异步唤醒模型。
- ToolResultWaiter 优先使用 Redis Pub/Sub 唤醒，但不信任通知 payload；最终仍读取 Redis terminal state，polling 是一致性兜底。
- ActiveRunSweeper 只推进 Redis 中已 ingest 的 WAITING tool，不调用 AgentLoop/provider，不产生新的 assistant message。
- dry-run / confirmToken：写操作必须经过用户确认，模型不能单轮直接修改业务状态。
- abort 不覆盖已终态 run：用户 abort 只对 active run 生效；已 `SUCCEEDED/FAILED/TIMEOUT/CANCELLED` 的 run 保持原状态。
- synthetic tool result 是协议一致性手段：只要 assistant tool call 已落库，就必须有匹配的 tool result，避免 provider replay 出现 orphan。

## Token 预算

估算公式：

```text
run_tokens = turns * (average_input_tokens + average_output_tokens)
```

较重 demo 估算：

```text
10 turns * (4k input + 1k output) ~= 50k tokens / run
```

默认预算目标：

```text
100k tokens / user / day ~= 2 个较重 demo run
```

Provider 价格会变化，所以 README 只保留 token 公式，不硬编码费用。

## 已知限制

- 多实例当前采用 active run sweeper + Redis CAS + Pub/Sub/polling fallback；尚未引入 MQ worker 或完整 outbox。
- V2.0 fallback 只覆盖 pre-stream retryable failure，不做 stream 中途 provider 切换。
- context compact 使用 MySQL trajectory logical path，不引入对象存储；超大二进制结果不是本版本目标。
- 没有完整外部订单系统集成；demo 使用内置订单模块和种子数据。
- 当前日志是滚动文本日志；结构化 JSON 日志可以作为后续增强。
- SubAgent 采用同步等待模型，单 run 累计最多 2 个 child，同一时刻最多 1 个 in-flight child；如需大规模 SubAgent 并发，需要演进到 Pub/Sub 唤醒或异步任务模型。
