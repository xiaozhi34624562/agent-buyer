# agent-buyer

`agent-buyer` is a Java Spring Boot AI layer for an order system: it lets a buyer use natural language to query orders and cancel an order through a safe dry-run / confirmation workflow.

V1a is intentionally small and demoable: one business domain, one provider, real SSE streaming, Redis tool scheduling, MySQL trajectory, and enough safety/observability to explain the engineering tradeoffs clearly.

## Architecture

```text
HTTP REST + SSE
  -> AgentController
  -> AgentLoop
  -> PromptAssembler + DeepSeekProviderAdapter
  -> ToolRegistry
  -> Redis ToolRuntime
       - ingest / schedule / complete CAS
       - concurrent query tools
       - exclusive write tools
       - lease reaper
  -> Order tools
       - query_order
       - cancel_order dry-run / confirm
  -> MySQL trajectory
       - run / message / llm attempt
       - tool call / tool result
       - async event / tool progress
```

## V1a Scope

Included:

- DeepSeek OpenAI-compatible streaming adapter
- HTTP REST + SSE events: `text_delta`, `tool_use`, `tool_progress`, `tool_result`, `final`, `error`, `ping`
- Order tools: `query_order`, `cancel_order`
- Redis safe/non-safe tool scheduler with Lua CAS
- dry-run / confirmToken for write operations
- MyBatis Plus persistence and Flyway migrations
- Full trajectory query by `runId`
- Rate limiting, prompt-injection boundary text, PII masking, secret-from-env config
- Lease reaper, startup repair, graceful shutdown
- Micrometer metrics and `/actuator/prometheus`

Not included until V1b/V1c:

- provider fallback or Qwen/GLM
- context compact
- skill loading
- subAgents
- RAG/vector store
- multi-instance deployment verification

## Local Services

The default local ports match the development setup:

```text
MySQL 8: 127.0.0.1:3307, database agent_buyer
Redis 7: 127.0.0.1:6380, no password
```

Start existing local containers:

```bash
docker start agent-buyer-mysql8 agent-buyer-redis7
docker ps --filter name=agent-buyer
```

Or run the full demo stack:

```bash
export DEEPSEEK_API_KEY='<your deepseek api key>'
docker compose up --build
```

## Run Tests

```bash
MYSQL_PASSWORD='<local mysql password>' mvn test
```

## Run App Locally

```bash
export MYSQL_PASSWORD='<local mysql password>'
export DEEPSEEK_API_KEY='<your deepseek api key>'
mvn spring-boot:run
```

Health and metrics:

```bash
curl http://127.0.0.1:8080/actuator/health
curl http://127.0.0.1:8080/actuator/prometheus
```

## 5-Step Demo

Reset the seed order:

```bash
MYSQL_PASSWORD='<local mysql password>' ./scripts/reset-demo-order.sh
```

Run the scripted SSE demo:

```bash
./scripts/demo-cancel-order.sh
```

Manual flow:

1. User says: `取消我昨天的那个订单`
2. Agent calls `query_order`
3. Agent calls `cancel_order` without `confirmToken`, producing `PENDING_CONFIRM`
4. User confirms through `POST /api/agent/runs/{runId}/messages`
5. Agent calls `cancel_order` with `confirmToken`, then returns final answer

Initial request:

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

Continuation:

```bash
curl -N \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -H 'X-User-Id: demo-user' \
  -d '{"message":{"role":"user","content":"确认取消这个订单"}}' \
  http://127.0.0.1:8080/api/agent/runs/<runId>/messages
```

Trajectory:

```bash
curl http://127.0.0.1:8080/api/agent/runs/<runId>
```

## Design Tradeoffs

- Single provider: V1a keeps provider complexity low and makes failure behavior easy to explain.
- Tool calls commit after provider attempt completion: partial stream tool calls never enter Redis.
- Redis scheduler, MySQL trajectory: Redis owns active runtime state; MySQL owns replay/audit history.
- Polling tool result waiter: simple and reliable for one JVM; Redis Pub/Sub is a V1b improvement.
- dry-run / confirmToken: write tools cannot mutate business state until the user confirms.

## Token Budget

Estimate formula:

```text
run_tokens = turns * (average_input_tokens + average_output_tokens)
```

Heavy demo estimate:

```text
10 turns * (4k input + 1k output) ~= 50k tokens / run
```

Default budget target:

```text
100k tokens / user / day ~= 2 heavy demo runs
```

Provider pricing changes over time, so this project keeps the README to token formulas rather than hard-coded cost.

## Known Limits

- V1a is designed for a single Docker app instance.
- No provider fallback; DeepSeek failure fails the run after retry.
- No context compaction; long conversations fail fast at the hard cap.
- No full external order system integration; the demo uses an in-process order module with seed data.
