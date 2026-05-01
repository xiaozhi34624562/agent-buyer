# agent-buyer

Java Spring Boot business agent demo for order workflows.

V1a focuses on one business domain: orders. It exposes an HTTP + SSE agent loop that can query orders and cancel an order through a dry-run / confirmation flow.

## Local Services

This repo expects local Docker services:

```text
MySQL 8: 127.0.0.1:3307, database agent_buyer
Redis 7: 127.0.0.1:6380, no password
```

Useful commands:

```bash
docker start agent-buyer-mysql8 agent-buyer-redis7
docker ps --filter name=agent-buyer
```

## Run Tests

```bash
MYSQL_PASSWORD='<local mysql password>' mvn test
```

## Run App

```bash
export MYSQL_PASSWORD='<local mysql password>'
export DEEPSEEK_API_KEY='<your deepseek api key>'
mvn spring-boot:run
```

## Demo Request

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

If the first response asks for confirmation, continue the same run:

```bash
curl -N \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -H 'X-User-Id: demo-user' \
  -d '{"message":{"role":"user","content":"确认取消"}}' \
  http://127.0.0.1:8080/api/agent/runs/<runId>/messages
```
