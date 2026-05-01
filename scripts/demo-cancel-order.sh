#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:8080}"
USER_ID="${USER_ID:-demo-user}"
OUT_DIR="${OUT_DIR:-/tmp/agent-buyer-demo}"
mkdir -p "${OUT_DIR}"

first="${OUT_DIR}/cancel-order-1.sse"
second="${OUT_DIR}/cancel-order-2.sse"

echo "1/3 request: ask agent to cancel yesterday's order"
curl -N -sS \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -H "X-User-Id: ${USER_ID}" \
  -d '{
    "messages":[{"role":"user","content":"取消我昨天的那个订单。请先查询订单，如果需要确认，请给出确认摘要。"}],
    "allowedToolNames":["query_order","cancel_order"],
    "llmParams":{"model":"deepseek-reasoner","temperature":0.2,"maxTokens":4096}
  }' \
  "${BASE_URL}/api/agent/runs" | tee "${first}"

run_id="$(sed -n 's/.*"runId":"\([^"]*\)".*/\1/p' "${first}" | tail -1)"
if [[ -z "${run_id}" ]]; then
  echo "Could not extract runId from ${first}" >&2
  exit 1
fi

echo
echo "2/3 continuation: confirm run ${run_id}"
curl -N -sS \
  -H 'Content-Type: application/json' \
  -H 'Accept: text/event-stream' \
  -H "X-User-Id: ${USER_ID}" \
  -d '{"message":{"role":"user","content":"确认取消这个订单"}}' \
  "${BASE_URL}/api/agent/runs/${run_id}/messages" | tee "${second}"

echo
echo "3/3 trajectory:"
curl -sS "${BASE_URL}/api/agent/runs/${run_id}"
echo
