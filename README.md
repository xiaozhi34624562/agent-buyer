# agent-buyer

`agent-buyer` 是一个基于 Java Spring Boot 的订单系统 AI 赋能层。它让买家可以通过自然语言查询订单，并通过安全的 dry-run / 用户确认流程取消订单。

V1a 版本刻意保持小而完整：只做一个业务域、一个大模型 provider、真实 SSE 流式响应、Redis 工具调度、MySQL 全链路轨迹，以及足够支撑演示和工程讲解的安全性与可观测性。

## 架构

```text
HTTP REST + SSE
  -> AgentController
  -> AgentRunApplicationService
  -> RunAccessManager + AgentRequestPolicy
  -> DefaultAgentLoop
  -> AgentTurnOrchestrator
  -> LlmAttemptService + DeepSeekProviderAdapter
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
  -> MySQL trajectory
       - run / message / llm attempt
       - tool call / tool result
       - async event / tool progress
```

## V1a 范围

已实现：

- DeepSeek OpenAI-compatible streaming adapter
- HTTP REST + SSE 事件：`text_delta`、`tool_use`、`tool_progress`、`tool_result`、`final`、`error`、`ping`
- 订单工具：`query_order`、`cancel_order`
- Redis safe / non-safe 工具调度器，使用 Lua CAS 保证状态原子性
- run 归属校验、run context 工具权限固化、请求预算校验
- abort / timeout 通过 Redis `abort_requested` 传播到 scheduler、runtime、complete CAS 与工具 cancellation token
- `ToolResultCloser` 统一闭合 precheck failure、timeout、abort、executor reject 等 synthetic tool result
- `ConfirmationIntentService` 区分确认、拒绝和模糊输入，采用拒绝优先、疑问优先、确认收窄的写操作确认边界
- 写操作 dry-run / confirmToken 二段式确认
- MyBatis Plus 持久化与 Flyway 数据库迁移
- 基于 `runId` 的完整 trajectory 查询
- 限流、prompt injection 边界提示、PII 脱敏、密钥从环境变量读取
- lease reaper、startup repair、graceful shutdown
- Micrometer metrics 与 `/actuator/prometheus`

## V2 phased roadmap

V2 不作为一次性大包并行交付，而是分阶段推进，每个里程碑独立做 hardening review，沿用 V1a 的主 agent 分发、sub agent 并行开发、`java-alibaba-review` gate 流程。

| 里程碑 | 内容 | 周期估算 | 验收信号 |
|---|---|---:|---|
| V2.0 | Multi-provider（DeepSeek + Qwen）+ Context compact（spill / micro / summary） | 1-2 周 | DeepSeek 故障可 fallback Qwen；50K token 上下文不 fail closed；compact summary 保留 `businessFacts/toolFacts/openQuestions` |
| V2.1 | Skill 渐进式加载 + SubAgent / AgentTool | 2-3 周 | `/skillName` slash 注入工作；SubAgent 继承 tool/skill 但不继承 history；child run trajectory 可独立查询 |
| V2.2 | 多实例部署 + Pub/Sub + ToDo + Interrupt | 2 周 | 双实例并发 schedule 不重复执行；interrupt 写入 `PAUSED`；ToDo reminder 每 3 turn 注入但不污染对外 conversation |

V2 明确不做：GLM、RAG / embedding / vector store、Kafka / RabbitMQ / workflow engine、完整 outbox、大结果对象存储。

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

## 本地启动应用

```bash
export MYSQL_PASSWORD='<local mysql password>'
export DEEPSEEK_API_KEY='<your deepseek api key>'
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
SELECT * FROM agent_message ORDER BY created_at DESC LIMIT 20;
SELECT * FROM agent_llm_attempt ORDER BY started_at DESC LIMIT 20;
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

- 单 provider：V1a 只接 DeepSeek，降低 provider 差异复杂度，便于把 agent loop 和工具调度讲清楚。
- 工具调用在 provider attempt 完整结束后提交：模型流中未完成的 partial tool call 不进入 Redis。
- Redis 调度，MySQL 追溯：Redis 负责活跃运行态；MySQL 负责 replay / audit / trajectory。
- ToolResultWaiter 使用短轮询：单 JVM demo 下足够简单可靠；Redis Pub/Sub 是 V2.2 的演进方向。
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

- V1a 面向单个 Spring Boot 应用实例设计。
- 没有 provider fallback；DeepSeek 失败后，run 会在重试失败后失败。
- 没有 context compact；超长对话触发 hard cap 后快速失败。
- 没有完整外部订单系统集成；demo 使用内置订单模块和种子数据。
- 当前日志是滚动文本日志；结构化 JSON 日志可以作为后续增强。
- V2 SubAgent 采用同步等待模型，单 run 累计最多 2 个 child，同一时刻最多 1 个 in-flight child；如需大规模 SubAgent 并发，需要演进到 Pub/Sub 唤醒或异步任务模型，当前未实现。
