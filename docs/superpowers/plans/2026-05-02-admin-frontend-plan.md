# Agent Buyer Console Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a local Agent Buyer Console that demonstrates and debugs the agent-buyer harness through chat, run timeline, SSE event stream, and current Redis runtime state.

**Architecture:** V1 is an agent console, not a generic database browser. The frontend calls existing `/api/agent/*` endpoints for chat/SSE/trajectory and adds only two backend admin capabilities: paged run list and sanitized current-run runtime state. MySQL raw tables and arbitrary Redis key browsing are deferred to a later admin-inspector version.

**Tech Stack:** Spring Boot 3.3 + MyBatis Plus + Redis for backend additions; React 18 + TypeScript + Vite + Tailwind CSS + lucide-react + Vitest/React Testing Library for frontend.

---

## 0. Scope Reset

### V1 Product Shape

The first frontend should help a reviewer understand the agent lifecycle:

```text
Run List         Run Timeline                         Chat / Controls
最近 runs         messages / attempts / tools / events   用户输入 / SSE 流 / HITL
状态筛选          compactions / child links              abort / interrupt / continue
```

The console should answer these questions quickly:

- 这个 run 是谁发起的，当前处于什么状态？
- 模型说了什么，什么时候调用了哪些 tool？
- tool 参数、进度、结果、synthetic result、compact、fallback 有没有发生？
- 当前 run 在 Redis 里还有哪些 active state？
- human-in-the-loop 确认、缺槽追问、interrupt、SubAgent、ToDo 是否按预期工作？

### V1 Non-Goals

- Do not build a generic MySQL table browser.
- Do not expose arbitrary Redis key lookup.
- Do not duplicate `/api/agent/runs` chat proxy as `/api/admin/chat`.
- Do not expose `confirmToken`, provider raw diagnostic payload, API keys, or full unredacted tool result JSON.
- Do not add Spring Security unless a later security-focused phase is created.

### V1 Backend Contract

Use existing endpoints for chat and trajectory:

```text
POST /api/agent/runs
POST /api/agent/runs/{runId}/messages
GET  /api/agent/runs/{runId}
POST /api/agent/runs/{runId}/abort
POST /api/agent/runs/{runId}/interrupt
```

Add minimal admin endpoints:

```text
GET /api/admin/console/runs
GET /api/admin/console/runs/{runId}/runtime-state
```

Admin endpoints are local/demo-only in V1 and guarded by `X-Admin-Token` when `agent.admin.token` is configured.

---

## 1. File Structure

### Backend Additions

```text
src/main/java/com/ai/agent/config/AgentProperties.java

src/main/java/com/ai/agent/web/admin/
  controller/
    AdminConsoleController.java
  dto/
    AdminPageResponse.java
    AdminRunListResponse.java
    AdminRunSummaryDto.java
    AdminRuntimeStateDto.java
    AdminRedisEntryDto.java
  service/
    AdminAccessGuard.java
    AdminRunListService.java
    AdminRuntimeStateService.java

src/test/java/com/ai/agent/web/admin/
  controller/
    AdminConsoleControllerTest.java
  service/
    AdminAccessGuardTest.java
    AdminRunListServiceTest.java
    AdminRuntimeStateServiceTest.java
```

### Frontend Additions

The frontend lives inside the existing Git repo, not as a sibling directory:

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

---

## 2. Milestones

| Milestone | Outcome | Gate |
|---|---|---|
| M1 Backend Console API | Run list and runtime-state endpoints work with tests | `mvn test` |
| M2 Frontend Shell | Three-panel console renders with mock data and responsive constraints | `npm test`, `npm run build` |
| M3 Data Integration | Run list, timeline, runtime-state use real backend data | backend + frontend tests |
| M4 Chat + SSE | Create/continue run through existing `/api/agent/*`; HITL, abort, interrupt work | manual local smoke + tests |
| M5 Hardening | README, scripts, Playwright smoke, final verification | `mvn test`, `npm run build`, local browser smoke |

Do not start M2 before M1 is merged. Do not start M4 before M3 can show a real run trajectory.

---

## 3. M1 Backend Console API

### Task 1: Add Admin Properties and Access Guard

**Files:**

- Modify: `src/main/java/com/ai/agent/config/AgentProperties.java`
- Modify: `src/main/resources/application.yml`
- Create: `src/main/java/com/ai/agent/web/admin/service/AdminAccessGuard.java`
- Test: `src/test/java/com/ai/agent/web/admin/service/AdminAccessGuardTest.java`

- [ ] **Step 1: Add admin config**

Add an `Admin` nested property to `AgentProperties`:

```java
private Admin admin = new Admin();

public Admin getAdmin() {
    return admin;
}

public void setAdmin(Admin admin) {
    this.admin = admin;
}

public static class Admin {
    private boolean enabled = true;
    private String token = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }
}
```

Add defaults to `application.yml`:

```yaml
agent:
  admin:
    enabled: ${AGENT_ADMIN_ENABLED:true}
    token: ${AGENT_ADMIN_TOKEN:}
```

- [ ] **Step 2: Add guard tests**

Create tests for these cases:

```text
enabled=true, token blank       -> allowed
enabled=true, token matches     -> allowed
enabled=true, token mismatch    -> throws AdminAccessDeniedException
enabled=false                   -> throws AdminDisabledException
```

Run:

```bash
mvn -q -Dtest=com.ai.agent.web.admin.service.AdminAccessGuardTest test
```

Expected before implementation: compile failure because `AdminAccessGuard` does not exist.

- [ ] **Step 3: Implement guard**

`AdminAccessGuard` must be a small service:

```java
@Service
public final class AdminAccessGuard {
    private final AgentProperties properties;

    public AdminAccessGuard(AgentProperties properties) {
        this.properties = properties;
    }

    public void assertAllowed(String providedToken) {
        AgentProperties.Admin admin = properties.getAdmin();
        if (!admin.isEnabled()) {
            throw new AdminDisabledException();
        }
        String configured = admin.getToken();
        if (configured == null || configured.isBlank()) {
            return;
        }
        if (!configured.equals(providedToken)) {
            throw new AdminAccessDeniedException();
        }
    }

    public static final class AdminDisabledException extends RuntimeException {
    }

    public static final class AdminAccessDeniedException extends RuntimeException {
    }
}
```

- [ ] **Step 4: Verify**

Run:

```bash
mvn -q -Dtest=com.ai.agent.web.admin.service.AdminAccessGuardTest test
mvn -q -DskipTests compile
```

Expected: pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/ai/agent/config/AgentProperties.java \
        src/main/resources/application.yml \
        src/main/java/com/ai/agent/web/admin/service/AdminAccessGuard.java \
        src/test/java/com/ai/agent/web/admin/service/AdminAccessGuardTest.java
git commit -m "feat(console): add admin access guard"
```

### Task 2: Add Run List Query Service

**Files:**

- Create: `src/main/java/com/ai/agent/web/admin/dto/AdminPageResponse.java`
- Create: `src/main/java/com/ai/agent/web/admin/dto/AdminRunSummaryDto.java`
- Create: `src/main/java/com/ai/agent/web/admin/dto/AdminRunListResponse.java`
- Create: `src/main/java/com/ai/agent/web/admin/service/AdminRunListService.java`
- Test: `src/test/java/com/ai/agent/web/admin/service/AdminRunListServiceTest.java`

- [ ] **Step 1: Define DTOs**

Use `LocalDateTime` to match existing persistence entities.

```java
public record AdminPageResponse<T>(
        List<T> rows,
        long total,
        int page,
        int pageSize,
        int totalPages
) {
    public static <T> AdminPageResponse<T> of(List<T> rows, long total, int page, int pageSize) {
        int totalPages = pageSize <= 0 ? 0 : (int) Math.ceil(total / (double) pageSize);
        return new AdminPageResponse<>(List.copyOf(rows), total, page, pageSize, totalPages);
    }
}
```

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

```java
public record AdminRunListResponse(AdminPageResponse<AdminRunSummaryDto> page) {
}
```

- [ ] **Step 2: Write service tests**

Test the service with a small in-memory `JdbcTemplate` mock or Mockito mock:

```text
listRuns normalizes page < 1 to 1
listRuns clamps pageSize > 100 to 100
listRuns filters by status when status is supplied
listRuns filters by userId when userId is supplied
listRuns always orders by agent_run.updated_at desc
```

Run:

```bash
mvn -q -Dtest=com.ai.agent.web.admin.service.AdminRunListServiceTest test
```

Expected before implementation: compile failure.

- [ ] **Step 3: Implement with JdbcTemplate**

Use `JdbcTemplate` for this read-only console query to avoid changing core MyBatis mappers for admin-only projections. Keep SQL safe:

- no dynamic table names
- no dynamic sort columns
- no `${}` style interpolation
- fixed `ORDER BY r.updated_at DESC`

The query must join `agent_run_context` because provider/model live there:

```sql
SELECT r.run_id,
       r.user_id,
       r.status,
       r.turn_no,
       r.agent_type,
       r.parent_run_id,
       r.parent_link_status,
       c.primary_provider,
       c.fallback_provider,
       c.model,
       c.max_turns,
       r.started_at,
       r.updated_at,
       r.completed_at,
       r.last_error
FROM agent_run r
LEFT JOIN agent_run_context c ON c.run_id = r.run_id
WHERE (:status is absent or r.status = ?)
  AND (:userId is absent or r.user_id = ?)
ORDER BY r.updated_at DESC
LIMIT ? OFFSET ?
```

Build the SQL with conditional fragments only for status/userId and bind every value through parameters.

- [ ] **Step 4: Verify**

Run:

```bash
mvn -q -Dtest=com.ai.agent.web.admin.service.AdminRunListServiceTest test
mvn -q -DskipTests compile
```

Expected: pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/ai/agent/web/admin/dto/AdminPageResponse.java \
        src/main/java/com/ai/agent/web/admin/dto/AdminRunSummaryDto.java \
        src/main/java/com/ai/agent/web/admin/dto/AdminRunListResponse.java \
        src/main/java/com/ai/agent/web/admin/service/AdminRunListService.java \
        src/test/java/com/ai/agent/web/admin/service/AdminRunListServiceTest.java
git commit -m "feat(console): add run list query service"
```

### Task 3: Add Runtime State Service

**Files:**

- Create: `src/main/java/com/ai/agent/web/admin/dto/AdminRedisEntryDto.java`
- Create: `src/main/java/com/ai/agent/web/admin/dto/AdminRuntimeStateDto.java`
- Create: `src/main/java/com/ai/agent/web/admin/service/AdminRuntimeStateService.java`
- Test: `src/test/java/com/ai/agent/web/admin/service/AdminRuntimeStateServiceTest.java`

- [ ] **Step 1: Define runtime DTOs**

The DTO must be scoped to one run and must not support arbitrary Redis key reads:

```java
public record AdminRedisEntryDto(
        String key,
        String type,
        Object value,
        Long ttlSeconds
) {
}
```

```java
public record AdminRuntimeStateDto(
        String runId,
        boolean activeRun,
        List<AdminRedisEntryDto> entries
) {
    public AdminRuntimeStateDto {
        entries = entries == null ? List.of() : List.copyOf(entries);
    }
}
```

- [ ] **Step 2: Write service tests**

Cover these cases:

```text
active set contains runId -> activeRun true
HASH key is returned as map
ZSET key is returned as ordered entries with score
missing key returns value null and type none
service only reads keys derived from RedisKeys for the supplied runId
```

Run:

```bash
mvn -q -Dtest=com.ai.agent.web.admin.service.AdminRuntimeStateServiceTest test
```

Expected before implementation: compile failure.

- [ ] **Step 3: Implement runtime state reader**

Use existing `RedisKeys` and `StringRedisTemplate`.

Read these keys only:

```text
agent:active-runs
agent:{run:<runId>}:meta
agent:{run:<runId>}:queue
agent:{run:<runId>}:tools
agent:{run:<runId>}:tool-use-ids
agent:{run:<runId>}:leases
agent:{run:<runId>}:continuation-lock
agent:{run:<runId>}:llm-call-budget
agent:{run:<runId>}:children
agent:{run:<runId>}:todos
agent:{run:<runId>}:todo-reminder
```

Value rules:

| Redis type | DTO value |
|---|---|
| `hash` | `Map<Object,Object>` |
| `zset` | list of `{value, score}` maps |
| `set` | sorted list |
| `string` | string value |
| `none` | `null` |

Do not read `confirm-tokens`. Do not scan by wildcard.

- [ ] **Step 4: Verify**

Run:

```bash
mvn -q -Dtest=com.ai.agent.web.admin.service.AdminRuntimeStateServiceTest test
mvn -q -DskipTests compile
```

Expected: pass.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/ai/agent/web/admin/dto/AdminRedisEntryDto.java \
        src/main/java/com/ai/agent/web/admin/dto/AdminRuntimeStateDto.java \
        src/main/java/com/ai/agent/web/admin/service/AdminRuntimeStateService.java \
        src/test/java/com/ai/agent/web/admin/service/AdminRuntimeStateServiceTest.java
git commit -m "feat(console): add runtime state service"
```

### Task 4: Add Admin Console Controller

**Files:**

- Create: `src/main/java/com/ai/agent/web/admin/controller/AdminConsoleController.java`
- Test: `src/test/java/com/ai/agent/web/admin/controller/AdminConsoleControllerTest.java`

- [ ] **Step 1: Write controller tests**

Use standalone MockMvc. Cover:

```text
GET /api/admin/console/runs returns page response
GET /api/admin/console/runs passes status/userId/page/pageSize to service
GET /api/admin/console/runs/{runId}/runtime-state returns runtime state
invalid admin token returns 403
admin disabled returns 503
```

Run:

```bash
mvn -q -Dtest=com.ai.agent.web.admin.controller.AdminConsoleControllerTest test
```

Expected before implementation: compile failure.

- [ ] **Step 2: Implement controller**

Controller contract:

```text
GET /api/admin/console/runs?page=1&pageSize=20&status=RUNNING&userId=demo-user
GET /api/admin/console/runs/{runId}/runtime-state
```

Every method receives optional header:

```text
X-Admin-Token
```

Every method calls `AdminAccessGuard.assertAllowed(token)` before service access.

Page bounds:

```text
page min = 1
pageSize default = 20
pageSize max = 100
```

Exception mapping:

| Exception | HTTP |
|---|---|
| `AdminAccessDeniedException` | 403 |
| `AdminDisabledException` | 503 |
| `IllegalArgumentException` | 400 |

- [ ] **Step 3: Verify backend gate**

Run:

```bash
mvn -q -Dtest=com.ai.agent.web.admin.controller.AdminConsoleControllerTest test
MYSQL_PASSWORD='Qaz1234!' mvn test
```

Expected: all tests pass.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/ai/agent/web/admin/controller/AdminConsoleController.java \
        src/test/java/com/ai/agent/web/admin/controller/AdminConsoleControllerTest.java
git commit -m "feat(console): add admin console endpoints"
```

---

## 4. M2 Frontend Shell

### Task 5: Initialize Frontend Project in `admin-web`

**Files:**

- Create: `admin-web/package.json`
- Create: `admin-web/vite.config.ts`
- Create: `admin-web/tailwind.config.ts`
- Create: `admin-web/postcss.config.js`
- Create: `admin-web/tsconfig.json`
- Create: `admin-web/index.html`
- Create: `admin-web/src/main.tsx`
- Create: `admin-web/src/App.tsx`
- Create: `admin-web/src/index.css`

- [ ] **Step 1: Create Vite project skeleton**

Use React + TypeScript. Dependencies:

```json
{
  "dependencies": {
    "@vitejs/plugin-react": "^4.2.1",
    "lucide-react": "^0.468.0",
    "react": "^18.2.0",
    "react-dom": "^18.2.0"
  },
  "devDependencies": {
    "@testing-library/jest-dom": "^6.4.2",
    "@testing-library/react": "^14.2.1",
    "@testing-library/user-event": "^14.5.2",
    "@types/react": "^18.2.66",
    "@types/react-dom": "^18.2.22",
    "autoprefixer": "^10.4.18",
    "jsdom": "^24.0.0",
    "postcss": "^8.4.35",
    "tailwindcss": "^3.4.1",
    "typescript": "^5.4.5",
    "vite": "^5.1.6",
    "vitest": "^1.4.0"
  }
}
```

Scripts:

```json
{
  "dev": "vite --host 127.0.0.1",
  "build": "tsc -b && vite build",
  "test": "vitest run",
  "test:watch": "vitest"
}
```

Vite proxy:

```ts
server: {
  port: 5173,
  proxy: {
    '/api': {
      target: 'http://127.0.0.1:8080',
      changeOrigin: true
    }
  }
}
```

- [ ] **Step 2: Add theme CSS**

Use a restrained operations-console palette:

```css
:root {
  color: #24231f;
  background: #f5f3ec;
  font-family: Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
}
```

Avoid decorative gradient orbs. Use panels, tables, timelines, and compact controls.

- [ ] **Step 3: Add smoke test**

Create `admin-web/src/App.test.tsx`:

```ts
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import App from './App';

describe('App', () => {
  it('renders the console title', () => {
    render(<App />);
    expect(screen.getByText('Agent Buyer Console')).toBeInTheDocument();
  });
});
```

- [ ] **Step 4: Verify**

Run:

```bash
cd admin-web
npm install
npm test
npm run build
```

Expected: all pass.

- [ ] **Step 5: Commit**

```bash
git add admin-web
git commit -m "feat(console): initialize React frontend"
```

### Task 6: Add Shared Types and API Clients

**Files:**

- Create: `admin-web/src/types/agent.ts`
- Create: `admin-web/src/types/admin.ts`
- Create: `admin-web/src/types/sse.ts`
- Create: `admin-web/src/api/adminApi.ts`
- Create: `admin-web/src/api/agentApi.ts`
- Create: `admin-web/src/api/sseParser.ts`
- Test: `admin-web/src/api/sseParser.test.ts`

- [ ] **Step 1: Define backend-aligned types**

Important names must match current backend SSE payloads:

```ts
export type RunStatus =
  | 'CREATED'
  | 'RUNNING'
  | 'WAITING_USER_CONFIRMATION'
  | 'PAUSED'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'FAILED_RECOVERED'
  | 'CANCELLED'
  | 'TIMEOUT';
```

```ts
export type SseEventName =
  | 'text_delta'
  | 'tool_use'
  | 'tool_progress'
  | 'tool_result'
  | 'final'
  | 'error'
  | 'ping';
```

Use `toolName`, not `name`, for `tool_use`.

- [ ] **Step 2: Implement POST SSE parser**

Do not use `EventSource` for chat, because chat uses POST. Implement a parser for chunks shaped like:

```text
event: text_delta
data: {"runId":"...","delta":"..."}

event: final
data: {"runId":"...","status":"SUCCEEDED"}
```

Parser tests must cover:

```text
single event in one chunk
one event split across two chunks
multiple events in one chunk
ping event ignored by chat transcript but preserved in debug log
invalid JSON becomes parser error event
```

- [ ] **Step 3: Implement API clients**

`agentApi.ts`:

```text
createRun(userId, request, onEvent)
continueRun(userId, runId, message, onEvent)
getTrajectory(userId, runId)
abortRun(userId, runId)
interruptRun(userId, runId)
```

`adminApi.ts`:

```text
listRuns(params, adminToken)
getRuntimeState(runId, adminToken)
```

Every request must send `X-User-Id` for `/api/agent/*`. Admin requests send `X-Admin-Token` only when provided.

- [ ] **Step 4: Verify**

Run:

```bash
cd admin-web
npm test
npm run build
```

Expected: pass.

- [ ] **Step 5: Commit**

```bash
git add admin-web/src/types admin-web/src/api
git commit -m "feat(console): add API clients and SSE parser"
```

### Task 7: Build Console Shell

**Files:**

- Create: `admin-web/src/components/shell/ConsoleShell.tsx`
- Create: `admin-web/src/components/shell/Toolbar.tsx`
- Create: `admin-web/src/components/ui/Button.tsx`
- Create: `admin-web/src/components/ui/IconButton.tsx`
- Create: `admin-web/src/components/ui/Badge.tsx`
- Create: `admin-web/src/components/ui/EmptyState.tsx`
- Create: `admin-web/src/components/ui/ErrorBanner.tsx`
- Create: `admin-web/src/components/ui/Spinner.tsx`
- Modify: `admin-web/src/App.tsx`
- Test: `admin-web/src/components/shell/ConsoleShell.test.tsx`

- [ ] **Step 1: Layout**

Use a dense, console-style three-panel layout:

```text
header 48px
main grid:
  left  280px min, 22vw max
  middle minmax(420px, 1fr)
  right 380px min, 32vw max
```

Mobile fallback:

```text
tabs: Runs | Timeline | Chat
```

- [ ] **Step 2: UI components**

Buttons should use lucide icons where available:

```text
RefreshCw for refresh
Square for interrupt
Ban for abort
Plus for new chat
Bug for debug
Send for send
```

Use 8px radius. Avoid nested cards.

- [ ] **Step 3: Verify**

Run:

```bash
cd admin-web
npm test
npm run build
```

Expected: shell renders `Runs`, `Timeline`, `Chat`.

- [ ] **Step 4: Commit**

```bash
git add admin-web/src/components admin-web/src/App.tsx
git commit -m "feat(console): add console shell layout"
```

---

## 5. M3 Real Data Integration

### Task 8: Build Run List Panel

**Files:**

- Create: `admin-web/src/hooks/useRunList.ts`
- Create: `admin-web/src/components/runs/RunListPanel.tsx`
- Create: `admin-web/src/components/runs/RunListItem.tsx`
- Create: `admin-web/src/components/runs/RunFilters.tsx`
- Test: `admin-web/src/components/runs/RunListPanel.test.tsx`

- [ ] **Step 1: Hook behavior**

`useRunList` state:

```text
rows
page
pageSize
status filter
userId filter
selectedRunId
loading
error
refresh()
selectRun(run)
```

Refresh should not clear current selection unless selected run disappears from the returned page.

- [ ] **Step 2: Panel behavior**

Run item must show:

```text
status badge
runId shortened
userId
provider/model
turnNo
updatedAt
parent/child marker when parentRunId exists
```

- [ ] **Step 3: Verify**

Run:

```bash
cd admin-web
npm test
npm run build
```

Expected: tests pass and panel can render empty/loading/error/data states.

- [ ] **Step 4: Commit**

```bash
git add admin-web/src/hooks/useRunList.ts admin-web/src/components/runs
git commit -m "feat(console): add run list panel"
```

### Task 9: Build Timeline Panel

**Files:**

- Create: `admin-web/src/hooks/useRunDetail.ts`
- Create: `admin-web/src/components/timeline/TimelinePanel.tsx`
- Create: `admin-web/src/components/timeline/TimelineItem.tsx`
- Create: `admin-web/src/components/timeline/MessageNode.tsx`
- Create: `admin-web/src/components/timeline/LlmAttemptNode.tsx`
- Create: `admin-web/src/components/timeline/ToolCallNode.tsx`
- Create: `admin-web/src/components/timeline/EventNode.tsx`
- Create: `admin-web/src/components/timeline/CompactionNode.tsx`
- Test: `admin-web/src/components/timeline/TimelinePanel.test.tsx`

- [ ] **Step 1: Build merged timeline model**

Merge trajectory DTO arrays into one sorted display list:

```text
messages       -> MESSAGE
llmAttempts    -> LLM_ATTEMPT
toolCalls      -> TOOL_CALL
toolResults    -> TOOL_RESULT
toolProgress   -> TOOL_PROGRESS
events         -> EVENT
compactions    -> COMPACTION
```

If exact timestamps tie or are missing, preserve stable grouping:

```text
message -> tool call -> tool progress -> tool result -> event
```

- [ ] **Step 2: Render nodes**

Required visible fields:

```text
MESSAGE: role, preview content, toolUseId when present
LLM_ATTEMPT: provider, model, status, finishReason, token usage
TOOL_CALL: toolName, concurrent/idempotent, precheckFailed
TOOL_RESULT: status, synthetic, cancelReason, result preview
EVENT: eventType, payload preview
COMPACTION: strategy, beforeTokens, afterTokens, compacted count
```

- [ ] **Step 3: Verify**

Run:

```bash
cd admin-web
npm test
npm run build
```

Expected: timeline renders a fixture containing all item types.

- [ ] **Step 4: Commit**

```bash
git add admin-web/src/hooks/useRunDetail.ts admin-web/src/components/timeline
git commit -m "feat(console): add run timeline panel"
```

### Task 10: Build Runtime State Debug View

**Files:**

- Create: `admin-web/src/hooks/useRuntimeState.ts`
- Create: `admin-web/src/components/debug/DebugDrawer.tsx`
- Create: `admin-web/src/components/debug/RuntimeStateView.tsx`
- Test: `admin-web/src/components/debug/RuntimeStateView.test.tsx`

- [ ] **Step 1: Hook behavior**

`useRuntimeState(runId)` fetches:

```text
GET /api/admin/console/runs/{runId}/runtime-state
```

It should refresh when:

```text
selected run changes
user clicks refresh
chat receives tool_use / tool_result / final event
```

- [ ] **Step 2: Runtime display**

Group entries by purpose:

```text
Control: meta, continuation-lock, llm-call-budget
Tool Runtime: queue, tools, tool-use-ids, leases
Planning: todos, todo-reminder
SubAgent: children
```

Do not render `confirm-tokens`.

- [ ] **Step 3: Verify**

Run:

```bash
cd admin-web
npm test
npm run build
```

Expected: runtime state renders missing keys gracefully.

- [ ] **Step 4: Commit**

```bash
git add admin-web/src/hooks/useRuntimeState.ts admin-web/src/components/debug
git commit -m "feat(console): add runtime state debug view"
```

---

## 6. M4 Chat and SSE

### Task 11: Build Chat Stream Hook

**Files:**

- Create: `admin-web/src/hooks/useChatStream.ts`
- Create: `admin-web/src/components/debug/SseEventLog.tsx`
- Test: `admin-web/src/hooks/useChatStream.test.tsx`

- [ ] **Step 1: State model**

`useChatStream` owns:

```text
currentRunId
runStatus
assistantDraft
chatMessages
sseEvents
isStreaming
nextActionRequired
error
```

It must not store or display `confirmToken`.

- [ ] **Step 2: Event handling**

Event effects:

| Event | State effect |
|---|---|
| `text_delta` | append to current assistant draft |
| `tool_use` | add tool card |
| `tool_progress` | update progress card |
| `tool_result` | add result preview |
| `final` | set runId/status/nextActionRequired, close draft |
| `error` | set error, stop streaming |
| `ping` | append only to debug log |

`WAITING_USER_CONFIRMATION` and `PAUSED + user_input` both require user continuation UI.

- [ ] **Step 3: Verify**

Run:

```bash
cd admin-web
npm test
npm run build
```

Expected: tests cover happy path, HITL confirmation, recoverable user input, and error event.

- [ ] **Step 4: Commit**

```bash
git add admin-web/src/hooks/useChatStream.ts admin-web/src/components/debug/SseEventLog.tsx
git commit -m "feat(console): add chat stream state"
```

### Task 12: Build Chat Panel

**Files:**

- Create: `admin-web/src/components/chat/ChatPanel.tsx`
- Create: `admin-web/src/components/chat/ChatTranscript.tsx`
- Create: `admin-web/src/components/chat/ChatComposer.tsx`
- Create: `admin-web/src/components/chat/RunControls.tsx`
- Create: `admin-web/src/components/chat/ConfirmationBar.tsx`
- Test: `admin-web/src/components/chat/ChatPanel.test.tsx`

- [ ] **Step 1: Chat create**

Default request:

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

Send to:

```text
POST /api/agent/runs
X-User-Id: <selected userId>
```

- [ ] **Step 2: Chat continuation**

For `WAITING_USER_CONFIRMATION`:

```text
Confirm button -> content "确认继续执行"
Reject button  -> content "放弃本次操作"
```

For `PAUSED + user_input`:

```text
Composer placeholder changes to "补充订单号、说明或下一步指令..."
```

Send to:

```text
POST /api/agent/runs/{runId}/messages
X-User-Id: <selected userId>
body: {"message":{"role":"user","content":"..."}}
```

- [ ] **Step 3: Run controls**

Controls:

```text
New Chat
Refresh Run
Interrupt
Abort
Toggle Debug
```

Disable `Interrupt` and `Abort` when `runStatus` is terminal.

- [ ] **Step 4: Verify**

Run:

```bash
cd admin-web
npm test
npm run build
```

Expected: tests cover create, continue, confirm, reject, interrupt, abort button states.

- [ ] **Step 5: Commit**

```bash
git add admin-web/src/components/chat
git commit -m "feat(console): add chat panel and controls"
```

### Task 13: Wire App State Across Panels

**Files:**

- Modify: `admin-web/src/App.tsx`
- Modify: `admin-web/src/components/shell/Toolbar.tsx`
- Test: `admin-web/src/App.test.tsx`

- [ ] **Step 1: Shared selected run**

App owns:

```text
selectedRunId
selectedUserId default "demo-user"
adminToken from localStorage
debugOpen
```

When chat creates a run, set `selectedRunId` to the runId from first SSE event that contains runId.

When run list selection changes, load trajectory and runtime state for that run.

- [ ] **Step 2: Toolbar**

Toolbar fields:

```text
User ID input
Admin token input
Refresh runs
Debug toggle
```

Store `userId` and `adminToken` in localStorage. Never log admin token.

- [ ] **Step 3: Verify**

Run:

```bash
cd admin-web
npm test
npm run build
```

Expected: selecting a run updates timeline and runtime debug view.

- [ ] **Step 4: Commit**

```bash
git add admin-web/src/App.tsx admin-web/src/components/shell/Toolbar.tsx
git commit -m "feat(console): wire run selection across panels"
```

---

## 7. M5 Hardening and Demo

### Task 14: Add Local Demo Script

**Files:**

- Create: `scripts/start-console-dev.sh`
- Modify: `README.md`

- [ ] **Step 1: Script behavior**

The script should:

```text
verify MySQL/Redis are reachable
start Spring Boot on 8080 if not already listening
start Vite admin-web on 5173
print both URLs
```

Do not embed provider API keys in the script.

- [ ] **Step 2: README section**

Add Chinese section:

```text
Agent Buyer Console
- 启动后端
- 启动前端
- 打开 http://127.0.0.1:5173
- 示例用户 demo-user
- 推荐演示 prompt
```

Recommended prompts:

```text
取消我昨天的那个订单，先查订单再 dry-run
没问题，按刚才的取消方案继续处理
这个任务比较复杂，先创建 ToDo，再查最近订单并总结
/purchase-guide 查询最近订单并结合 skill 给我建议
```

- [ ] **Step 3: Verify**

Run:

```bash
bash -n scripts/start-console-dev.sh
```

Expected: no syntax errors.

- [ ] **Step 4: Commit**

```bash
git add scripts/start-console-dev.sh README.md
git commit -m "docs(console): add local console demo instructions"
```

### Task 15: Add Integration and Browser Smoke

**Files:**

- Create: `admin-web/src/test/fixtures/trajectory.ts`
- Create: `admin-web/src/test/fixtures/sseEvents.ts`
- Create: `admin-web/src/App.integration.test.tsx`
- Optional create: `scripts/console-smoke.sh`

- [ ] **Step 1: Frontend integration fixture**

Fixture must contain:

```text
one run summary
one USER message
one ASSISTANT message with tool call
one LLM attempt
one tool call
one tool progress
one tool result
one confirmation final SSE event
one compaction
one runtime-state response
```

- [ ] **Step 2: Integration test**

Test renders `App` with mocked fetch and verifies:

```text
run list appears
timeline item count includes message/tool/attempt/event
chat text_delta updates transcript
WAITING_USER_CONFIRMATION shows confirm/reject controls
runtime state view hides confirm token
```

- [ ] **Step 3: Browser smoke**

Use Playwright or in-app browser after implementation:

```text
desktop 1440x900 screenshot
mobile 390x844 screenshot
verify no overlapping panels
verify text fits buttons
verify chat can send prompt and receive SSE final
```

- [ ] **Step 4: Final verification**

Run:

```bash
MYSQL_PASSWORD='Qaz1234!' mvn test
cd admin-web && npm test && npm run build
```

Then run a local smoke:

```bash
MYSQL_PASSWORD='Qaz1234!' \
DEEPSEEK_API_KEY='<local env only>' \
QWEN_API_KEY='<local env only>' \
./scripts/real-llm-e2e.sh
```

Expected:

```text
mvn test: BUILD SUCCESS
npm test: all tests pass
npm run build: success
real-llm-e2e: full suite passed
```

- [ ] **Step 5: Commit**

```bash
git add admin-web/src/test admin-web/src/App.integration.test.tsx scripts/console-smoke.sh
git commit -m "test(console): add frontend integration smoke"
```

---

## 8. Acceptance Criteria

### Product Acceptance

- A user can create a run from the console and watch SSE text/tool events stream live.
- A user can continue `WAITING_USER_CONFIRMATION` and `PAUSED + user_input` runs from the console.
- A user can interrupt or abort an active run from the console.
- Selecting a run shows trajectory timeline: messages, attempts, tools, events, progress, compactions.
- Debug drawer shows current-run Redis runtime state without arbitrary key lookup.
- Console uses real `/api/agent/*` chat endpoints rather than an admin chat proxy.

### Engineering Acceptance

- New Java package path is `com/ai/agent/web/admin`, not `web.admin`.
- Backend admin endpoints are covered by controller and service tests.
- Frontend POST SSE uses `fetch + ReadableStream`, not `EventSource`.
- Frontend and backend type names match current code: `toolName`, `finalText`, `nextActionRequired`, `RunStatus`.
- No raw `confirmToken` appears in frontend state, timeline, runtime state, logs, or screenshots.
- No generic `SELECT * FROM <user input>` or arbitrary Redis key endpoint exists.
- `mvn test`, `npm test`, and `npm run build` pass before final handoff.

---

## 9. Review Notes for Implementers

Before starting each task:

1. Check `git status --short`.
2. Do not change unrelated backend V2 code.
3. Write or update the test first.
4. Keep commits small and named with `feat(console): ...`, `test(console): ...`, or `docs(console): ...`.
5. If a task reveals a backend mismatch, update this plan or create a short progress note before coding around it.

Known traps this plan avoids:

- `AgentRunEntity` has no provider/model fields; use `agent_run_context`.
- `UserMessage` constructor is `(role, content)`, not `(content, metadata)`.
- `LlmParams` has four fields: `model`, `temperature`, `maxTokens`, `maxTurns`.
- Spring SSE class is `SseAgentEventSink`, but frontend chat should call existing controller, not construct backend sinks.
- Browser `EventSource` cannot POST; use `fetch` streaming for chat.
- Redis keys contain braces and colons; do not put full Redis keys in path variables.
- `confirm-tokens` is intentionally excluded from runtime-state output.

---

## 10. Execution Options

Plan complete and saved to `docs/superpowers/plans/2026-05-02-admin-frontend-plan.md`.

Recommended execution:

1. **Subagent-Driven:** one worker for M1 backend, one worker for M2/M3 frontend shell after M1 gate, one review pass after each milestone.
2. **Inline Execution:** implement milestone by milestone in this session, with `mvn test` / `npm test` gates before proceeding.

Do not implement M4 chat before the M3 timeline can show a real run.
