#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

APP_PORT="${APP_PORT:-18080}"
BASE_URL="${BASE_URL:-http://127.0.0.1:${APP_PORT}}"
START_APP="${START_APP:-true}"
USER_ID="${USER_ID:-demo-user}"
OUT_DIR="${OUT_DIR:-/tmp/agent-buyer-real-llm-e2e/$(date +%Y%m%d-%H%M%S)}"
CURL_MAX_TIME="${CURL_MAX_TIME:-300}"

MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3307}"
MYSQL_USERNAME="${MYSQL_USERNAME:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-}"
REDIS_HOST="${REDIS_HOST:-127.0.0.1}"
REDIS_PORT="${REDIS_PORT:-6380}"
DEEPSEEK_MODEL="${DEEPSEEK_MODEL:-deepseek-reasoner}"
QWEN_MODEL="${QWEN_MODEL:-qwen-plus}"

mkdir -p "${OUT_DIR}"

require_env() {
  local name="$1"
  if [[ -z "${!name:-}" ]]; then
    echo "Missing required env: ${name}" >&2
    exit 1
  fi
}

require_command() {
  local name="$1"
  if ! command -v "${name}" >/dev/null 2>&1; then
    echo "Missing required command: ${name}" >&2
    exit 1
  fi
}

require_env MYSQL_PASSWORD
require_env DEEPSEEK_API_KEY
require_command curl
require_command python3
require_command mysql
require_command mvn

APP_PID=""
cleanup() {
  if [[ -n "${APP_PID}" ]] && kill -0 "${APP_PID}" >/dev/null 2>&1; then
    kill "${APP_PID}" >/dev/null 2>&1 || true
    wait "${APP_PID}" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

wait_for_health() {
  local health_file="${OUT_DIR}/health.json"
  for _ in $(seq 1 90); do
    if curl -fsS "${BASE_URL}/actuator/health" -o "${health_file}" >/dev/null 2>&1 \
      && python3 - "${health_file}" <<'PY' >/dev/null 2>&1
import json
import sys
with open(sys.argv[1], "r", encoding="utf-8") as handle:
    payload = json.load(handle)
raise SystemExit(0 if payload.get("status") == "UP" else 1)
PY
    then
      return 0
    fi
    sleep 2
  done
  echo "Application did not become healthy. Last app log:" >&2
  tail -120 "${OUT_DIR}/app.log" >&2 || true
  exit 1
}

start_app() {
  if [[ "${START_APP}" != "true" ]]; then
    wait_for_health
    return
  fi
  if command -v lsof >/dev/null 2>&1 && lsof -PiTCP:"${APP_PORT}" -sTCP:LISTEN -t >/dev/null 2>&1; then
    echo "Port ${APP_PORT} is already in use. Set APP_PORT or START_APP=false." >&2
    exit 1
  fi
  echo "Starting agent-buyer on ${BASE_URL}"
  SERVER_PORT="${APP_PORT}" \
  MYSQL_URL="jdbc:mysql://${MYSQL_HOST}:${MYSQL_PORT}/agent_buyer?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC" \
  MYSQL_USERNAME="${MYSQL_USERNAME}" \
  MYSQL_PASSWORD="${MYSQL_PASSWORD}" \
  REDIS_HOST="${REDIS_HOST}" \
  REDIS_PORT="${REDIS_PORT}" \
  DEEPSEEK_API_KEY="${DEEPSEEK_API_KEY}" \
  DEEPSEEK_MODEL="${DEEPSEEK_MODEL}" \
  QWEN_API_KEY="${QWEN_API_KEY:-}" \
  QWEN_MODEL="${QWEN_MODEL}" \
  mvn -q spring-boot:run >"${OUT_DIR}/app.log" 2>&1 &
  APP_PID="$!"
  wait_for_health
}

post_sse() {
  local path="$1"
  local body="$2"
  local url="$3"
  local http_code
  http_code="$(curl -sS -N --max-time "${CURL_MAX_TIME}" \
    -H "Content-Type: application/json" \
    -H "Accept: text/event-stream" \
    -H "X-User-Id: ${USER_ID}" \
    --data-binary @"${body}" \
    -o "${path}" \
    -w "%{http_code}" \
    "${url}")"
  if [[ "${http_code}" != "200" ]]; then
    echo "Unexpected HTTP ${http_code} from ${url}. Body:" >&2
    cat "${path}" >&2 || true
    exit 1
  fi
}

parse_sse() {
  local phase="$1"
  local path="$2"
  python3 - "${phase}" "${path}" <<'PY'
import json
import sys
from collections import Counter

phase = sys.argv[1]
path = sys.argv[2]

def read_events(file_path):
    events = []
    event_name = "message"
    data_lines = []
    with open(file_path, "r", encoding="utf-8") as handle:
        for raw_line in handle:
            line = raw_line.rstrip("\n")
            if line == "":
                if data_lines:
                    events.append((event_name, "\n".join(data_lines)))
                event_name = "message"
                data_lines = []
                continue
            if line.startswith("event:"):
                event_name = line[len("event:"):].strip()
            elif line.startswith("data:"):
                data_lines.append(line[len("data:"):].strip())
    if data_lines:
        events.append((event_name, "\n".join(data_lines)))
    return events

events = read_events(path)
decoded = []
errors = []
for name, data in events:
    if name == "ping":
        continue
    try:
        payload = json.loads(data)
    except json.JSONDecodeError:
        payload = {"raw": data}
    decoded.append({"event": name, "payload": payload})
    if name == "error":
        errors.append(payload)

counts = Counter(item["event"] for item in decoded)
tool_names = [
    item["payload"].get("toolName")
    for item in decoded
    if item["event"] == "tool_use" and isinstance(item["payload"], dict)
]
finals = [
    item["payload"]
    for item in decoded
    if item["event"] == "final" and isinstance(item["payload"], dict)
]
run_ids = [
    item["payload"].get("runId")
    for item in decoded
    if isinstance(item["payload"], dict) and item["payload"].get("runId")
]

if errors:
    raise SystemExit(f"{phase} SSE contained error event: {errors}")
if not finals:
    raise SystemExit(f"{phase} SSE did not contain final event")
if not run_ids:
    raise SystemExit(f"{phase} SSE did not contain runId")

summary = {
    "phase": phase,
    "run_id": run_ids[-1],
    "event_counts": dict(counts),
    "tool_names": tool_names,
    "final": finals[-1],
}

if phase == "create":
    required = {"query_order", "cancel_order"}
    missing = sorted(required - set(tool_names))
    if missing:
        raise SystemExit(f"create phase did not call required tools: {missing}; actual={tool_names}")
    if counts.get("tool_result", 0) < 2:
        raise SystemExit(f"create phase expected at least two tool_result events; counts={dict(counts)}")
    if finals[-1].get("status") != "WAITING_USER_CONFIRMATION":
        raise SystemExit(f"create phase expected WAITING_USER_CONFIRMATION; final={finals[-1]}")
    if finals[-1].get("nextActionRequired") != "user_confirmation":
        raise SystemExit(f"create phase expected nextActionRequired=user_confirmation; final={finals[-1]}")
elif phase == "confirm":
    if "cancel_order" not in tool_names:
        raise SystemExit(f"confirm phase did not call cancel_order; actual={tool_names}")
    if counts.get("tool_result", 0) < 1:
        raise SystemExit(f"confirm phase expected tool_result; counts={dict(counts)}")
    if finals[-1].get("status") != "SUCCEEDED":
        raise SystemExit(f"confirm phase expected SUCCEEDED; final={finals[-1]}")

print(json.dumps(summary, ensure_ascii=False, indent=2))
PY
}

json_field() {
  local path="$1"
  local expr="$2"
  python3 - "${path}" "${expr}" <<'PY'
import json
import sys
payload = json.load(open(sys.argv[1], "r", encoding="utf-8"))
value = payload
for part in sys.argv[2].split("."):
    value = value[part]
print(value)
PY
}

assert_order_cancelled() {
  local status
  status="$(MYSQL_PWD="${MYSQL_PASSWORD}" mysql -N -B \
    -u"${MYSQL_USERNAME}" -h"${MYSQL_HOST}" -P"${MYSQL_PORT}" -D agent_buyer \
    -e "SELECT status FROM business_order WHERE order_id = 'O-1001'")"
  if [[ "${status}" != "CANCELLED" ]]; then
    echo "Expected O-1001 to be CANCELLED, got: ${status}" >&2
    exit 1
  fi
  echo "${status}"
}

assert_trajectory() {
  local path="$1"
  python3 - "${path}" <<'PY'
import json
import sys

with open(sys.argv[1], "r", encoding="utf-8") as handle:
    payload = json.load(handle)

raw = json.dumps(payload, ensure_ascii=False)
required = ["query_order", "cancel_order", "SUCCEEDED"]
missing = [item for item in required if item not in raw]
if missing:
    raise SystemExit(f"trajectory missing expected content: {missing}")
print("trajectory contains query_order, cancel_order, and SUCCEEDED")
PY
}

echo "Output directory: ${OUT_DIR}"
start_app

echo "Resetting demo order O-1001"
MYSQL_PASSWORD="${MYSQL_PASSWORD}" \
MYSQL_HOST="${MYSQL_HOST}" \
MYSQL_PORT="${MYSQL_PORT}" \
MYSQL_USER="${MYSQL_USERNAME}" \
"${ROOT_DIR}/scripts/reset-demo-order.sh" >"${OUT_DIR}/reset-order.txt"

cat >"${OUT_DIR}/create-request.json" <<JSON
{
  "messages": [
    {
      "role": "user",
      "content": "取消我昨天的那个订单。你必须使用工具完成：先调用 query_order 查询昨天订单，再调用 cancel_order 做 dry-run。看到 PENDING_CONFIRM 后停止，并把确认摘要告诉我。"
    }
  ],
  "allowedToolNames": ["query_order", "cancel_order"],
  "llmParams": {
    "model": "${DEEPSEEK_MODEL}",
    "temperature": 0.0,
    "maxTokens": 4096,
    "maxTurns": 8
  }
}
JSON

echo "Phase 1/3: create run and require dry-run confirmation"
post_sse "${OUT_DIR}/create.sse" "${OUT_DIR}/create-request.json" "${BASE_URL}/api/agent/runs"
parse_sse "create" "${OUT_DIR}/create.sse" >"${OUT_DIR}/create-summary.json"
RUN_ID="$(json_field "${OUT_DIR}/create-summary.json" "run_id")"
echo "Created run: ${RUN_ID}"

cat >"${OUT_DIR}/confirm-request.json" <<'JSON'
{
  "message": {
    "role": "user",
    "content": "确认取消这个订单"
  }
}
JSON

echo "Phase 2/3: continue run and confirm cancellation"
post_sse "${OUT_DIR}/confirm.sse" "${OUT_DIR}/confirm-request.json" "${BASE_URL}/api/agent/runs/${RUN_ID}/messages"
parse_sse "confirm" "${OUT_DIR}/confirm.sse" >"${OUT_DIR}/confirm-summary.json"

echo "Phase 3/3: verify trajectory and database state"
curl -sS \
  -H "X-User-Id: ${USER_ID}" \
  "${BASE_URL}/api/agent/runs/${RUN_ID}" >"${OUT_DIR}/trajectory.json"
assert_trajectory "${OUT_DIR}/trajectory.json" >"${OUT_DIR}/trajectory-check.txt"
ORDER_STATUS="$(assert_order_cancelled)"

echo "real-llm-e2e passed"
echo "runId=${RUN_ID}"
echo "order O-1001 status=${ORDER_STATUS}"
echo "artifacts=${OUT_DIR}"
