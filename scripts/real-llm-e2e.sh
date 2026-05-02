#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

RUN_STAMP="$(date +%Y%m%d-%H%M%S)"
APP_PORT="${APP_PORT:-18080}"
BASE_URL="${BASE_URL:-http://127.0.0.1:${APP_PORT}}"
START_APP="${START_APP:-true}"
USER_ID="${USER_ID:-demo-user}"
OUT_DIR="${OUT_DIR:-/tmp/agent-buyer-real-llm-e2e/${RUN_STAMP}}"
CURL_MAX_TIME="${CURL_MAX_TIME:-360}"

MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3307}"
MYSQL_USERNAME="${MYSQL_USERNAME:-root}"
MYSQL_PASSWORD="${MYSQL_PASSWORD:-}"
REDIS_HOST="${REDIS_HOST:-127.0.0.1}"
REDIS_PORT="${REDIS_PORT:-6380}"
DEEPSEEK_MODEL="${DEEPSEEK_MODEL:-deepseek-reasoner}"
QWEN_MODEL="${QWEN_MODEL:-qwen-plus}"

APP_PID=""
ACTIVE_BASE_URL="${BASE_URL}"
ACTIVE_APP_LOG=""

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
require_env QWEN_API_KEY
require_command curl
require_command python3
require_command mysql
require_command mvn

cleanup() {
  stop_app
}
trap cleanup EXIT

stop_app() {
  if [[ -n "${APP_PID}" ]] && kill -0 "${APP_PID}" >/dev/null 2>&1; then
    kill "${APP_PID}" >/dev/null 2>&1 || true
    wait "${APP_PID}" >/dev/null 2>&1 || true
  fi
  APP_PID=""
}

wait_for_health() {
  local base_url="$1"
  local log_path="$2"
  local health_file="${OUT_DIR}/$(basename "${log_path}" .log)-health.json"
  for _ in $(seq 1 90); do
    if curl -fsS "${base_url}/actuator/health" -o "${health_file}" >/dev/null 2>&1 \
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
  tail -160 "${log_path}" >&2 || true
  exit 1
}

start_app() {
  local name="$1"
  local port="$2"
  local mode="${3:-default}"
  local deepseek_base_url="${4:-https://api.deepseek.com/v1}"
  stop_app
  ACTIVE_BASE_URL="http://127.0.0.1:${port}"
  ACTIVE_APP_LOG="${OUT_DIR}/${name}-app.log"
  if [[ "${START_APP}" != "true" ]]; then
    wait_for_health "${ACTIVE_BASE_URL}" "${ACTIVE_APP_LOG}"
    return
  fi
  if command -v lsof >/dev/null 2>&1 && lsof -PiTCP:"${port}" -sTCP:LISTEN -t >/dev/null 2>&1; then
    echo "Port ${port} is already in use. Set APP_PORT or START_APP=false." >&2
    exit 1
  fi

  local large_threshold="${AGENT_CONTEXT_LARGE_RESULT_THRESHOLD_TOKENS:-2000}"
  local large_head="${AGENT_CONTEXT_LARGE_RESULT_HEAD_TOKENS:-200}"
  local large_tail="${AGENT_CONTEXT_LARGE_RESULT_TAIL_TOKENS:-200}"
  local micro_threshold="${AGENT_CONTEXT_MICRO_COMPACT_THRESHOLD_TOKENS:-50000}"
  local summary_threshold="${AGENT_CONTEXT_SUMMARY_COMPACT_THRESHOLD_TOKENS:-30000}"
  local recent_budget="${AGENT_CONTEXT_RECENT_MESSAGE_BUDGET_TOKENS:-2000}"
  local summary_max="${AGENT_CONTEXT_SUMMARY_MAX_TOKENS:-1200}"
  local hard_cap="${AGENT_AGENT_LOOP_HARD_TOKEN_CAP:-30000}"
  if [[ "${mode}" == "compact" ]]; then
    large_threshold="${E2E_LARGE_RESULT_THRESHOLD_TOKENS:-80}"
    large_head="${E2E_LARGE_RESULT_HEAD_TOKENS:-20}"
    large_tail="${E2E_LARGE_RESULT_TAIL_TOKENS:-20}"
    micro_threshold="${E2E_MICRO_COMPACT_THRESHOLD_TOKENS:-260}"
    summary_threshold="${E2E_SUMMARY_COMPACT_THRESHOLD_TOKENS:-180}"
    recent_budget="${E2E_RECENT_MESSAGE_BUDGET_TOKENS:-80}"
    summary_max="${E2E_SUMMARY_MAX_TOKENS:-4096}"
    hard_cap="${E2E_HARD_TOKEN_CAP:-12000}"
  fi

  echo "Starting ${name} app on ${ACTIVE_BASE_URL} (${mode})"
  SERVER_PORT="${port}" \
  MYSQL_URL="jdbc:mysql://${MYSQL_HOST}:${MYSQL_PORT}/agent_buyer?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC" \
  MYSQL_USERNAME="${MYSQL_USERNAME}" \
  MYSQL_PASSWORD="${MYSQL_PASSWORD}" \
  REDIS_HOST="${REDIS_HOST}" \
  REDIS_PORT="${REDIS_PORT}" \
  DEEPSEEK_BASE_URL="${deepseek_base_url}" \
  DEEPSEEK_API_KEY="${DEEPSEEK_API_KEY}" \
  DEEPSEEK_MODEL="${DEEPSEEK_MODEL}" \
  QWEN_API_KEY="${QWEN_API_KEY}" \
  QWEN_MODEL="${QWEN_MODEL}" \
  AGENT_RATE_LIMIT_RUNS_PER_USER_PER_MINUTE="${AGENT_RATE_LIMIT_RUNS_PER_USER_PER_MINUTE:-100}" \
  AGENT_RATE_LIMIT_TOKENS_PER_USER_PER_DAY="${AGENT_RATE_LIMIT_TOKENS_PER_USER_PER_DAY:-2000000}" \
  AGENT_CONTEXT_LARGE_RESULT_THRESHOLD_TOKENS="${large_threshold}" \
  AGENT_CONTEXT_LARGE_RESULT_HEAD_TOKENS="${large_head}" \
  AGENT_CONTEXT_LARGE_RESULT_TAIL_TOKENS="${large_tail}" \
  AGENT_CONTEXT_MICRO_COMPACT_THRESHOLD_TOKENS="${micro_threshold}" \
  AGENT_CONTEXT_SUMMARY_COMPACT_THRESHOLD_TOKENS="${summary_threshold}" \
  AGENT_CONTEXT_RECENT_MESSAGE_BUDGET_TOKENS="${recent_budget}" \
  AGENT_CONTEXT_SUMMARY_MAX_TOKENS="${summary_max}" \
  AGENT_AGENT_LOOP_HARD_TOKEN_CAP="${hard_cap}" \
  mvn -q spring-boot:run >"${ACTIVE_APP_LOG}" 2>&1 &
  APP_PID="$!"
  wait_for_health "${ACTIVE_BASE_URL}" "${ACTIVE_APP_LOG}"
}

post_sse() {
  local path="$1"
  local body="$2"
  local url="$3"
  local user_id="${4:-${USER_ID}}"
  local http_code
  http_code="$(curl -sS -N --max-time "${CURL_MAX_TIME}" \
    -H "Content-Type: application/json" \
    -H "Accept: text/event-stream" \
    -H "X-User-Id: ${user_id}" \
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

post_json() {
  local path="$1"
  local url="$2"
  local user_id="${3:-${USER_ID}}"
  local http_code
  http_code="$(curl -sS -X POST --max-time 30 \
    -H "X-User-Id: ${user_id}" \
    -o "${path}" \
    -w "%{http_code}" \
    "${url}")"
  if [[ "${http_code}" != "200" ]]; then
    echo "Unexpected HTTP ${http_code} from ${url}. Body:" >&2
    cat "${path}" >&2 || true
    exit 1
  fi
}

get_json() {
  local path="$1"
  local url="$2"
  local user_id="${3:-${USER_ID}}"
  local http_code
  http_code="$(curl -sS --max-time 60 \
    -H "X-User-Id: ${user_id}" \
    -o "${path}" \
    -w "%{http_code}" \
    "${url}")"
  if [[ "${http_code}" != "200" ]]; then
    echo "Unexpected HTTP ${http_code} from ${url}. Body:" >&2
    cat "${path}" >&2 || true
    exit 1
  fi
}

summarize_sse() {
  local phase="$1"
  local path="$2"
  local summary_path="$3"
  local expected_status="$4"
  local required_tools_csv="${5:-}"
  local min_tool_results="${6:-0}"
  python3 - "${phase}" "${path}" "${expected_status}" "${required_tools_csv}" "${min_tool_results}" >"${summary_path}" <<'PY'
import json
import sys
from collections import Counter

phase, path, expected_status, required_tools_csv, min_tool_results = sys.argv[1:6]
min_tool_results = int(min_tool_results)

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

decoded = []
errors = []
for name, data in read_events(path):
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

final = finals[-1]
if expected_status and final.get("status") != expected_status:
    raise SystemExit(f"{phase} expected final status {expected_status}; final={final}")

required_tools = [item for item in required_tools_csv.split(",") if item]
missing = sorted(set(required_tools) - set(tool_names))
if missing:
    raise SystemExit(f"{phase} missing required tools {missing}; actual={tool_names}")
if counts.get("tool_result", 0) < min_tool_results:
    raise SystemExit(f"{phase} expected at least {min_tool_results} tool_result events; counts={dict(counts)}")

summary = {
    "phase": phase,
    "run_id": run_ids[-1],
    "event_counts": dict(counts),
    "tool_names": tool_names,
    "final": final,
}
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

mysql_scalar() {
  local sql="$1"
  MYSQL_PWD="${MYSQL_PASSWORD}" mysql -N -B \
    -u"${MYSQL_USERNAME}" -h"${MYSQL_HOST}" -P"${MYSQL_PORT}" -D agent_buyer \
    -e "${sql}"
}

assert_positive_count() {
  local label="$1"
  local sql="$2"
  local count
  count="$(mysql_scalar "${sql}")"
  if [[ -z "${count}" || "${count}" == "NULL" || "${count}" -le 0 ]]; then
    echo "Expected positive count for ${label}, got: ${count}" >&2
    exit 1
  fi
}

assert_json_contains() {
  local path="$1"
  shift
  python3 - "${path}" "$@" <<'PY'
import json
import sys
payload = json.load(open(sys.argv[1], "r", encoding="utf-8"))
raw = json.dumps(payload, ensure_ascii=False)
missing = [item for item in sys.argv[2:] if item not in raw]
if missing:
    raise SystemExit(f"{sys.argv[1]} missing expected content: {missing}")
PY
}

reset_demo_order() {
  MYSQL_PASSWORD="${MYSQL_PASSWORD}" \
  MYSQL_HOST="${MYSQL_HOST}" \
  MYSQL_PORT="${MYSQL_PORT}" \
  MYSQL_USER="${MYSQL_USERNAME}" \
  "${ROOT_DIR}/scripts/reset-demo-order.sh" >"${OUT_DIR}/reset-order.txt"
}

run_order_cancel_case() {
  echo "Case 1/6: order cancel dry-run + confirm"
  reset_demo_order
  cat >"${OUT_DIR}/order-create-request.json" <<JSON
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
  post_sse "${OUT_DIR}/order-create.sse" "${OUT_DIR}/order-create-request.json" "${ACTIVE_BASE_URL}/api/agent/runs"
  summarize_sse "order-create" "${OUT_DIR}/order-create.sse" "${OUT_DIR}/order-create-summary.json" "WAITING_USER_CONFIRMATION" "query_order,cancel_order" 2
  local run_id
  run_id="$(json_field "${OUT_DIR}/order-create-summary.json" "run_id")"
  cat >"${OUT_DIR}/order-confirm-request.json" <<'JSON'
{
  "message": {
    "role": "user",
    "content": "没问题，按刚才的取消方案继续处理"
  }
}
JSON
  post_sse "${OUT_DIR}/order-confirm.sse" "${OUT_DIR}/order-confirm-request.json" "${ACTIVE_BASE_URL}/api/agent/runs/${run_id}/messages"
  summarize_sse "order-confirm" "${OUT_DIR}/order-confirm.sse" "${OUT_DIR}/order-confirm-summary.json" "SUCCEEDED" "cancel_order" 1
  assert_positive_count "confirmation decision event" "SELECT COUNT(*) FROM agent_event WHERE run_id = '${run_id}' AND event_type IN ('confirmation_intent_decision', 'confirmation_intent_llm')"
  get_json "${OUT_DIR}/order-trajectory.json" "${ACTIVE_BASE_URL}/api/agent/runs/${run_id}"
  assert_json_contains "${OUT_DIR}/order-trajectory.json" "query_order" "cancel_order" "SUCCEEDED"
  local status
  status="$(mysql_scalar "SELECT status FROM business_order WHERE order_id = 'O-1001'")"
  if [[ "${status}" != "CANCELLED" ]]; then
    echo "Expected O-1001 to be CANCELLED, got: ${status}" >&2
    exit 1
  fi
  echo "  passed runId=${run_id}"
}

run_todo_case() {
  echo "Case 2/6: ToDo create/write + reminder path"
  cat >"${OUT_DIR}/todo-request.json" <<JSON
{
  "messages": [
    {
      "role": "user",
      "content": "这个任务比较复杂，你必须先调用 todo_create 创建两个步骤：查询最近订单、总结订单状态。创建后立即调用 todo_write 把第一步改为 IN_PROGRESS，然后调用 query_order 查询最近订单，最后调用 todo_write 把第一步改为 DONE，并给出简短总结。"
    }
  ],
  "allowedToolNames": ["todo_create", "todo_write", "query_order"],
  "llmParams": {
    "model": "${DEEPSEEK_MODEL}",
    "temperature": 0.0,
    "maxTokens": 4096,
    "maxTurns": 10
  }
}
JSON
  post_sse "${OUT_DIR}/todo.sse" "${OUT_DIR}/todo-request.json" "${ACTIVE_BASE_URL}/api/agent/runs"
  summarize_sse "todo" "${OUT_DIR}/todo.sse" "${OUT_DIR}/todo-summary.json" "SUCCEEDED" "todo_create,todo_write,query_order" 3
  local run_id
  run_id="$(json_field "${OUT_DIR}/todo-summary.json" "run_id")"
  assert_positive_count "todo_created event" "SELECT COUNT(*) FROM agent_event WHERE run_id = '${run_id}' AND event_type = 'todo_created'"
  assert_positive_count "todo_updated event" "SELECT COUNT(*) FROM agent_event WHERE run_id = '${run_id}' AND event_type = 'todo_updated'"
  get_json "${OUT_DIR}/todo-trajectory.json" "${ACTIVE_BASE_URL}/api/agent/runs/${run_id}"
  assert_json_contains "${OUT_DIR}/todo-trajectory.json" "todo_create" "todo_write" "query_order"
  echo "  passed runId=${run_id}"
}

run_subagent_case() {
  echo "Case 3/6: AgentTool -> ExploreAgent child run"
  reset_demo_order
  cat >"${OUT_DIR}/subagent-request.json" <<JSON
{
  "messages": [
    {
      "role": "user",
      "content": "你必须调用 agent_tool 创建 explore 类型的 ExploreAgent，让子 Agent 独立查询我最近7天订单并总结订单事实。主 Agent 只根据子 Agent 返回结果做简短回答。"
    }
  ],
  "allowedToolNames": ["agent_tool", "query_order", "skill_list", "skill_view"],
  "llmParams": {
    "model": "${DEEPSEEK_MODEL}",
    "temperature": 0.0,
    "maxTokens": 4096,
    "maxTurns": 8
  }
}
JSON
  post_sse "${OUT_DIR}/subagent.sse" "${OUT_DIR}/subagent-request.json" "${ACTIVE_BASE_URL}/api/agent/runs"
  summarize_sse "subagent" "${OUT_DIR}/subagent.sse" "${OUT_DIR}/subagent-summary.json" "SUCCEEDED" "agent_tool" 1
  local run_id
  run_id="$(json_field "${OUT_DIR}/subagent-summary.json" "run_id")"
  assert_positive_count "child run" "SELECT COUNT(*) FROM agent_run WHERE parent_run_id = '${run_id}' AND agent_type = 'explore'"
  assert_positive_count "agent_tool childRunId result" "SELECT COUNT(*) FROM agent_tool_call_trace c JOIN agent_tool_result_trace r ON r.tool_call_id = c.tool_call_id WHERE c.run_id = '${run_id}' AND c.tool_name = 'agent_tool' AND r.status = 'SUCCEEDED' AND JSON_UNQUOTE(JSON_EXTRACT(r.result_json, '$.childRunId')) <> ''"
  get_json "${OUT_DIR}/subagent-trajectory.json" "${ACTIVE_BASE_URL}/api/agent/runs/${run_id}"
  assert_json_contains "${OUT_DIR}/subagent-trajectory.json" "agent_tool"
  echo "  passed runId=${run_id}"
}

run_interrupt_case() {
  echo "Case 4/6: interrupt active turn -> PAUSED"
  local interrupt_user="${USER_ID}-interrupt-${RUN_STAMP}"
  cat >"${OUT_DIR}/interrupt-request.json" <<JSON
{
  "messages": [
    {
      "role": "user",
      "content": "请写一篇不少于6000字的中文长文，主题是订单系统中的 AI agent 设计。不要调用工具，只持续输出正文。"
    }
  ],
  "allowedToolNames": [],
  "llmParams": {
    "model": "${DEEPSEEK_MODEL}",
    "temperature": 0.0,
    "maxTokens": 8192,
    "maxTurns": 1
  }
}
JSON
  curl -sS -N --max-time "${CURL_MAX_TIME}" \
    -H "Content-Type: application/json" \
    -H "Accept: text/event-stream" \
    -H "X-User-Id: ${interrupt_user}" \
    --data-binary @"${OUT_DIR}/interrupt-request.json" \
    -o "${OUT_DIR}/interrupt.sse" \
    "${ACTIVE_BASE_URL}/api/agent/runs" &
  local curl_pid="$!"
  local run_id=""
  for _ in $(seq 1 60); do
    run_id="$(mysql_scalar "SELECT run_id FROM agent_run WHERE user_id = '${interrupt_user}' ORDER BY started_at DESC LIMIT 1" || true)"
    if [[ -n "${run_id}" && "${run_id}" != "NULL" ]]; then
      break
    fi
    sleep 1
  done
  if [[ -z "${run_id}" || "${run_id}" == "NULL" ]]; then
    kill "${curl_pid}" >/dev/null 2>&1 || true
    echo "Could not find interrupt run id" >&2
    exit 1
  fi
  post_json "${OUT_DIR}/interrupt-response.json" "${ACTIVE_BASE_URL}/api/agent/runs/${run_id}/interrupt" "${interrupt_user}"
  python3 - "${OUT_DIR}/interrupt-response.json" <<'PY'
import json
import sys
payload = json.load(open(sys.argv[1], "r", encoding="utf-8"))
if payload.get("status") != "PAUSED":
    raise SystemExit(f"interrupt expected PAUSED, got {payload}")
if payload.get("nextActionRequired") != "user_input":
    raise SystemExit(f"interrupt expected nextActionRequired=user_input, got {payload}")
PY
  kill "${curl_pid}" >/dev/null 2>&1 || true
  wait "${curl_pid}" >/dev/null 2>&1 || true
  local status
  status="$(mysql_scalar "SELECT status FROM agent_run WHERE run_id = '${run_id}'")"
  if [[ "${status}" != "PAUSED" ]]; then
    echo "Expected interrupted run to be PAUSED, got: ${status}" >&2
    exit 1
  fi
  echo "  passed runId=${run_id}"
}

run_skill_compact_case() {
  echo "Case 5/6: slash skill + skill tools + context compact"
  reset_demo_order
  cat >"${OUT_DIR}/skill-compact-request.json" <<JSON
{
  "messages": [
    {
      "role": "user",
      "content": "/purchase-guide 为了验证上下文压缩，请严格分多轮调用工具，且每一轮 assistant 最多只允许发起一个 tool_call，禁止在同一轮批量调用多个工具。固定顺序：第1轮只调用 skill_list；第2轮只调用 skill_view 读取 purchase-guide 的 SKILL.md；第3轮只调用 skill_view 读取 return-exchange-guide 的 SKILL.md；第4轮只调用 skill_view 读取 order-issue-support 的 SKILL.md；第5轮只调用 query_order 查询最近7天订单；最后结合 skill 内容和订单结果用中文总结。每个 skill_view 只允许调用一次，query_order 只允许调用一次；如果历史里出现压缩摘要或占位符，不要重新读取或重新查询，直接基于已获得的信息总结。"
    }
  ],
  "allowedToolNames": ["skill_list", "skill_view", "query_order"],
  "llmParams": {
    "model": "${DEEPSEEK_MODEL}",
    "temperature": 0.0,
    "maxTokens": 4096,
    "maxTurns": 10
  }
}
JSON
  post_sse "${OUT_DIR}/skill-compact.sse" "${OUT_DIR}/skill-compact-request.json" "${ACTIVE_BASE_URL}/api/agent/runs"
  summarize_sse "skill-compact" "${OUT_DIR}/skill-compact.sse" "${OUT_DIR}/skill-compact-summary.json" "SUCCEEDED" "skill_list,skill_view,query_order" 4
  local run_id
  run_id="$(json_field "${OUT_DIR}/skill-compact-summary.json" "run_id")"
  get_json "${OUT_DIR}/skill-compact-trajectory.json" "${ACTIVE_BASE_URL}/api/agent/runs/${run_id}"
  assert_json_contains "${OUT_DIR}/skill-compact-trajectory.json" "skill_slash_injected" "skill_list" "skill_view" "purchase-guide"
  assert_positive_count "skill slash injected event" "SELECT COUNT(*) FROM agent_event WHERE run_id = '${run_id}' AND event_type = 'skill_slash_injected'"
  assert_positive_count "three skill_view calls" "SELECT CASE WHEN COUNT(*) >= 3 THEN 1 ELSE 0 END FROM agent_tool_call_trace WHERE run_id = '${run_id}' AND tool_name = 'skill_view'"
  assert_positive_count "context compaction rows" "SELECT COUNT(*) FROM agent_context_compaction WHERE run_id = '${run_id}'"
  local strategies
  strategies="$(mysql_scalar "SELECT COALESCE(GROUP_CONCAT(DISTINCT strategy ORDER BY strategy SEPARATOR ','), '') FROM agent_context_compaction WHERE run_id = '${run_id}'")"
  python3 - "${strategies}" <<'PY'
import sys
strategies = set(filter(None, sys.argv[1].split(",")))
required = {"LARGE_RESULT_SPILL", "MICRO_COMPACT", "SUMMARY_COMPACT"}
missing = sorted(required - strategies)
if missing:
    raise SystemExit(f"missing compaction strategies {missing}; actual={sorted(strategies)}")
PY
  echo "${strategies}" >"${OUT_DIR}/skill-compact-strategies.txt"
  echo "  passed runId=${run_id} strategies=${strategies}"
}

run_fallback_case() {
  echo "Case 6/6: DeepSeek pre-stream failure -> Qwen fallback"
  local fallback_user="${USER_ID}-fallback-${RUN_STAMP}"
  cat >"${OUT_DIR}/fallback-request.json" <<JSON
{
  "messages": [
    {
      "role": "user",
      "content": "请只回答 fallback-ok"
    }
  ],
  "allowedToolNames": [],
  "llmParams": {
    "model": "${DEEPSEEK_MODEL}",
    "temperature": 0.0,
    "maxTokens": 128,
    "maxTurns": 1
  }
}
JSON
  post_sse "${OUT_DIR}/fallback.sse" "${OUT_DIR}/fallback-request.json" "${ACTIVE_BASE_URL}/api/agent/runs" "${fallback_user}"
  summarize_sse "fallback" "${OUT_DIR}/fallback.sse" "${OUT_DIR}/fallback-summary.json" "SUCCEEDED" "" 0
  local run_id
  run_id="$(json_field "${OUT_DIR}/fallback-summary.json" "run_id")"
  assert_positive_count "llm_fallback event" "SELECT COUNT(*) FROM agent_event WHERE run_id = '${run_id}' AND event_type = 'llm_fallback'"
  assert_positive_count "qwen attempt" "SELECT COUNT(*) FROM agent_llm_attempt WHERE run_id = '${run_id}' AND provider = 'qwen' AND status = 'SUCCEEDED'"
  assert_positive_count "deepseek failed attempt" "SELECT COUNT(*) FROM agent_llm_attempt WHERE run_id = '${run_id}' AND provider = 'deepseek' AND status = 'FAILED'"
  get_json "${OUT_DIR}/fallback-trajectory.json" "${ACTIVE_BASE_URL}/api/agent/runs/${run_id}" "${fallback_user}"
  assert_json_contains "${OUT_DIR}/fallback-trajectory.json" "llm_fallback" "qwen"
  echo "  passed runId=${run_id}"
}

run_full_suite() {
  echo "Output directory: ${OUT_DIR}"
  start_app "default" "${APP_PORT}" "default" "https://api.deepseek.com/v1"
  run_order_cancel_case
  run_todo_case
  run_subagent_case
  run_interrupt_case

  start_app "compact" "${APP_PORT}" "compact" "https://api.deepseek.com/v1"
  run_skill_compact_case

  start_app "fallback" "${APP_PORT}" "default" "http://127.0.0.1:9/v1"
  run_fallback_case

  stop_app
  echo "real-llm-e2e full suite passed"
  echo "artifacts=${OUT_DIR}"
}

run_full_suite
