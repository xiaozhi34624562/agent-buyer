# Agent Buyer Console 实现计划

> **给 agentic worker 的要求：** 实现本计划时必须使用 `superpowers:subagent-driven-development`，推荐按任务派发 sub agent；也可以使用 `superpowers:executing-plans` 在当前会话逐项执行。所有步骤使用 checkbox 语法跟踪。

**目标：** 构建一个本地可演示、可调试的 Agent Buyer Console，用来观察 `agent-buyer` 的对话、SSE 流、run 轨迹、工具调用、human-in-the-loop 状态和当前 Redis runtime state。

**架构：** V1 是 agent console，不是通用数据库管理后台。前端复用现有 `/api/agent/*` 接口完成 chat、SSE、trajectory 查询，只新增两个最小 admin 能力：分页 run list 和经过脱敏、限定范围的当前 run runtime-state。原始 MySQL 表浏览、任意 Redis key 浏览推迟到独立 admin-inspector 版本，不进入本阶段。

**技术栈：** 后端使用 Spring Boot 3.3、MyBatis Plus、Redis；前端使用 React 18、TypeScript、Vite、Tailwind CSS、lucide-react、Vitest、React Testing Library。

---

## 0. 范围重新定义

### 0.1 V1 产品形态

第一版前端要帮助 reviewer 快速理解 agent 生命周期，而不是替代 IDEA Database、RedisInsight 或运维后台。

```text
Run List         Run Timeline                         Chat / Controls
最近 runs         messages / attempts / tools / events   用户输入 / SSE 流 / HITL
状态筛选          compactions / child links              abort / interrupt / continue
```

这个 console 要能快速回答这些问题：

- 这个 run 是谁发起的，现在是什么状态？
- 模型输出了什么，什么时候调用了哪些 tool？
- tool 参数、进度、结果、synthetic result、compact、fallback 有没有发生？
- 当前 run 在 Redis 中还有哪些活跃 runtime state？
- human-in-the-loop 确认、缺槽追问、interrupt、SubAgent、ToDo 是否按预期工作？

### 0.2 V1 不做什么

- 不做通用 MySQL table browser。
- 不暴露任意 Redis key lookup。
- 不把 `/api/agent/runs` 再包装成 `/api/admin/chat`。
- 不暴露 `confirmToken`、provider 原始诊断 payload、API key、未脱敏完整 tool result JSON。
- 不引入 Spring Security。本阶段是本地 demo console，认证只做最小 `X-Admin-Token` guard。

### 0.3 V1 后端接口契约

Chat、SSE、trajectory 直接使用现有接口：

```text
POST /api/agent/runs
POST /api/agent/runs/{runId}/messages
GET  /api/agent/runs/{runId}
POST /api/agent/runs/{runId}/abort
POST /api/agent/runs/{runId}/interrupt
```

只新增两个 admin 接口：

```text
GET /api/admin/console/runs
GET /api/admin/console/runs/{runId}/runtime-state
```

当配置了 `agent.admin.token` 时，admin 接口必须校验 `X-Admin-Token`。

---

## 1. 文件结构

### 1.1 后端新增文件

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

### 1.2 前端新增文件

前端放在当前仓库内部，不新建兄弟仓库：

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
    test/
      fixtures/
        trajectory.ts
        sseEvents.ts
```

---

## 2. 里程碑

| 里程碑 | 交付结果 | Gate |
|---|---|---|
| M1 Backend Console API | run list 和 runtime-state 接口可用并有测试 | `mvn test` |
| M2 Frontend Shell | 三栏 console 用 mock data 可渲染，响应式约束正确 | `npm test`、`npm run build` |
| M3 Real Data Integration | run list、timeline、runtime-state 接入真实后端数据 | 后端和前端测试通过 |
| M4 Chat + SSE | 通过现有 `/api/agent/*` 创建和继续 run，HITL、abort、interrupt 可用 | 本地 smoke 和测试通过 |
| M5 Hardening | README、启动脚本、浏览器 smoke、最终验证完成 | `mvn test`、`npm run build`、本地浏览器 smoke |

执行顺序要求：

- M1 没完成前不能开始 M2。
- M3 不能展示真实 trajectory 前不能开始 M4。
- 每个里程碑完成后做一次 review，再进入下一个里程碑。

---

## 3. M1 后端 Console API

### Task 1：增加 Admin 配置和访问控制

**文件：**

- 修改：`src/main/java/com/ai/agent/config/AgentProperties.java`
- 修改：`src/main/resources/application.yml`
- 新建：`src/main/java/com/ai/agent/web/admin/service/AdminAccessGuard.java`
- 测试：`src/test/java/com/ai/agent/web/admin/service/AdminAccessGuardTest.java`

- [ ] **Step 1：写失败测试**

`AdminAccessGuardTest` 覆盖以下行为：

```text
enabled=true, token blank       -> allowed
enabled=true, token matches     -> allowed
enabled=true, token mismatch    -> throws AdminAccessDeniedException
enabled=false                   -> throws AdminDisabledException
```

运行：

```bash
mvn -q -Dtest=com.ai.agent.web.admin.service.AdminAccessGuardTest test
```

预期：编译失败，因为 `AdminAccessGuard` 尚不存在。

- [ ] **Step 2：增加配置项**

在 `AgentProperties` 中增加嵌套配置：

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

在 `application.yml` 中增加默认值：

```yaml
agent:
  admin:
    enabled: ${AGENT_ADMIN_ENABLED:true}
    token: ${AGENT_ADMIN_TOKEN:}
```

- [ ] **Step 3：实现 guard**

`AdminAccessGuard` 是一个小型 service，只做本地 demo console 的开关和 token 校验：

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

- [ ] **Step 4：验证**

运行：

```bash
mvn -q -Dtest=com.ai.agent.web.admin.service.AdminAccessGuardTest test
mvn -q -DskipTests compile
```

预期：全部通过。

- [ ] **Step 5：提交**

```bash
git add src/main/java/com/ai/agent/config/AgentProperties.java \
        src/main/resources/application.yml \
        src/main/java/com/ai/agent/web/admin/service/AdminAccessGuard.java \
        src/test/java/com/ai/agent/web/admin/service/AdminAccessGuardTest.java
git commit -m "feat(console): add admin access guard"
```

### Task 2：增加 Run List 查询服务

**文件：**

- 新建：`src/main/java/com/ai/agent/web/admin/dto/AdminPageResponse.java`
- 新建：`src/main/java/com/ai/agent/web/admin/dto/AdminRunSummaryDto.java`
- 新建：`src/main/java/com/ai/agent/web/admin/dto/AdminRunListResponse.java`
- 新建：`src/main/java/com/ai/agent/web/admin/service/AdminRunListService.java`
- 测试：`src/test/java/com/ai/agent/web/admin/service/AdminRunListServiceTest.java`

- [ ] **Step 1：定义 DTO**

使用 `LocalDateTime`，和当前持久化实体保持一致。

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

- [ ] **Step 2：写失败测试**

`AdminRunListServiceTest` 覆盖：

```text
page < 1 时归一为 1
pageSize > 100 时截断为 100
传 status 时按 status 过滤
传 userId 时按 userId 过滤
排序固定为 agent_run.updated_at desc
provider/model 从 agent_run_context 读取
```

运行：

```bash
mvn -q -Dtest=com.ai.agent.web.admin.service.AdminRunListServiceTest test
```

预期：编译失败，因为 service 和 DTO 尚不存在。

- [ ] **Step 3：实现查询服务**

使用 `JdbcTemplate` 实现只读 console 查询，不为 admin-only projection 改核心 MyBatis mapper。

安全约束：

```text
不允许动态表名
不允许动态排序列
不允许 `${}` 拼接 SQL
所有参数必须走 bind parameter
ORDER BY 固定为 r.updated_at DESC
```

查询必须 join `agent_run_context`，因为 provider/model 存在那里：

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
WHERE 1 = 1
ORDER BY r.updated_at DESC
LIMIT ? OFFSET ?
```

只有 `status` 和 `userId` 两个可选过滤条件可以追加到 `WHERE`：

```java
if (StringUtils.hasText(status)) {
    sql.append(" AND r.status = ?");
    args.add(status);
}
if (StringUtils.hasText(userId)) {
    sql.append(" AND r.user_id = ?");
    args.add(userId);
}
```

- [ ] **Step 4：验证**

运行：

```bash
mvn -q -Dtest=com.ai.agent.web.admin.service.AdminRunListServiceTest test
mvn -q -DskipTests compile
```

预期：全部通过。

- [ ] **Step 5：提交**

```bash
git add src/main/java/com/ai/agent/web/admin/dto/AdminPageResponse.java \
        src/main/java/com/ai/agent/web/admin/dto/AdminRunSummaryDto.java \
        src/main/java/com/ai/agent/web/admin/dto/AdminRunListResponse.java \
        src/main/java/com/ai/agent/web/admin/service/AdminRunListService.java \
        src/test/java/com/ai/agent/web/admin/service/AdminRunListServiceTest.java
git commit -m "feat(console): add run list query service"
```

### Task 3：增加 Runtime State 查询服务

**文件：**

- 新建：`src/main/java/com/ai/agent/web/admin/dto/AdminRedisEntryDto.java`
- 新建：`src/main/java/com/ai/agent/web/admin/dto/AdminRuntimeStateDto.java`
- 新建：`src/main/java/com/ai/agent/web/admin/service/AdminRuntimeStateService.java`
- 测试：`src/test/java/com/ai/agent/web/admin/service/AdminRuntimeStateServiceTest.java`

- [ ] **Step 1：定义 DTO**

DTO 只能表达单个 run 的 runtime state，不支持任意 Redis key 读取：

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

- [ ] **Step 2：写失败测试**

`AdminRuntimeStateServiceTest` 覆盖：

```text
active set 包含 runId 时 activeRun=true
HASH key 返回 map
ZSET key 返回按 score 排序的 value/score 列表
缺失 key 返回 type=none, value=null
service 只读取 RedisKeys 派生出的固定 key
confirm-tokens 不会出现在结果中
```

运行：

```bash
mvn -q -Dtest=com.ai.agent.web.admin.service.AdminRuntimeStateServiceTest test
```

预期：编译失败，因为 service 和 DTO 尚不存在。

- [ ] **Step 3：实现 runtime state reader**

使用现有 `RedisKeys` 和 `StringRedisTemplate`。只读取这些 key：

```text
agent:active-runs
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

Redis 类型到 DTO 的映射：

| Redis type | DTO value |
|---|---|
| `hash` | `Map<Object,Object>` |
| `zset` | `List<Map<String,Object>>`，包含 `value` 和 `score` |
| `set` | 排序后的 list |
| `string` | string value |
| `none` | `null` |

明确禁止：

```text
不读 confirm-tokens
不按 wildcard scan
不把 Redis key 放到 path variable 让用户指定
```

- [ ] **Step 4：验证**

运行：

```bash
mvn -q -Dtest=com.ai.agent.web.admin.service.AdminRuntimeStateServiceTest test
mvn -q -DskipTests compile
```

预期：全部通过。

- [ ] **Step 5：提交**

```bash
git add src/main/java/com/ai/agent/web/admin/dto/AdminRedisEntryDto.java \
        src/main/java/com/ai/agent/web/admin/dto/AdminRuntimeStateDto.java \
        src/main/java/com/ai/agent/web/admin/service/AdminRuntimeStateService.java \
        src/test/java/com/ai/agent/web/admin/service/AdminRuntimeStateServiceTest.java
git commit -m "feat(console): add runtime state service"
```

### Task 4：增加 Admin Console Controller

**文件：**

- 新建：`src/main/java/com/ai/agent/web/admin/controller/AdminConsoleController.java`
- 测试：`src/test/java/com/ai/agent/web/admin/controller/AdminConsoleControllerTest.java`

- [ ] **Step 1：写失败测试**

使用 standalone MockMvc 覆盖：

```text
GET /api/admin/console/runs 返回 page response
GET /api/admin/console/runs 会把 status/userId/page/pageSize 传给 service
GET /api/admin/console/runs/{runId}/runtime-state 返回 runtime state
admin token 错误返回 403
admin disabled 返回 503
page/pageSize 非法时返回 400
```

运行：

```bash
mvn -q -Dtest=com.ai.agent.web.admin.controller.AdminConsoleControllerTest test
```

预期：编译失败，因为 controller 尚不存在。

- [ ] **Step 2：实现 controller**

接口契约：

```text
GET /api/admin/console/runs?page=1&pageSize=20&status=RUNNING&userId=demo-user
GET /api/admin/console/runs/{runId}/runtime-state
```

每个方法读取可选 header：

```text
X-Admin-Token
```

每个方法必须先调用：

```java
adminAccessGuard.assertAllowed(token);
```

分页边界：

```text
page min = 1
pageSize default = 20
pageSize max = 100
```

异常映射：

| Exception | HTTP |
|---|---|
| `AdminAccessDeniedException` | 403 |
| `AdminDisabledException` | 503 |
| `IllegalArgumentException` | 400 |

- [ ] **Step 3：验证后端 gate**

运行：

```bash
mvn -q -Dtest=com.ai.agent.web.admin.controller.AdminConsoleControllerTest test
MYSQL_PASSWORD='Qaz1234!' mvn test
```

预期：全部通过。

- [ ] **Step 4：提交**

```bash
git add src/main/java/com/ai/agent/web/admin/controller/AdminConsoleController.java \
        src/test/java/com/ai/agent/web/admin/controller/AdminConsoleControllerTest.java
git commit -m "feat(console): add admin console endpoints"
```

---

## 4. M2 前端 Shell

### Task 5：初始化 `admin-web` 前端工程

**文件：**

- 新建：`admin-web/package.json`
- 新建：`admin-web/vite.config.ts`
- 新建：`admin-web/tailwind.config.ts`
- 新建：`admin-web/postcss.config.js`
- 新建：`admin-web/tsconfig.json`
- 新建：`admin-web/index.html`
- 新建：`admin-web/src/main.tsx`
- 新建：`admin-web/src/App.tsx`
- 新建：`admin-web/src/index.css`
- 测试：`admin-web/src/App.test.tsx`

- [ ] **Step 1：创建 Vite React TypeScript 工程骨架**

`package.json` 使用以下依赖：

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
  },
  "scripts": {
    "dev": "vite --host 127.0.0.1",
    "build": "tsc -b && vite build",
    "test": "vitest run",
    "test:watch": "vitest"
  }
}
```

Vite proxy：

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

- [ ] **Step 2：增加基础主题**

`admin-web/src/index.css` 使用安静、工作台风格的配色：

```css
:root {
  color: #24231f;
  background: #f5f3ec;
  font-family: Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
}

body {
  margin: 0;
  min-width: 320px;
  min-height: 100vh;
}
```

设计约束：

```text
不用装饰性渐变球
不用营销式 hero
不用卡片套卡片
控制台优先，密度适中，信息可扫描
按钮内优先使用 lucide icon
```

- [ ] **Step 3：写 smoke test**

`admin-web/src/App.test.tsx`：

```ts
import '@testing-library/jest-dom/vitest';
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

- [ ] **Step 4：验证**

运行：

```bash
cd admin-web
npm install
npm test
npm run build
```

预期：全部通过。

- [ ] **Step 5：提交**

```bash
git add admin-web
git commit -m "feat(console): initialize React frontend"
```

### Task 6：增加共享类型和 API Client

**文件：**

- 新建：`admin-web/src/types/agent.ts`
- 新建：`admin-web/src/types/admin.ts`
- 新建：`admin-web/src/types/sse.ts`
- 新建：`admin-web/src/api/adminApi.ts`
- 新建：`admin-web/src/api/agentApi.ts`
- 新建：`admin-web/src/api/sseParser.ts`
- 测试：`admin-web/src/api/sseParser.test.ts`

- [ ] **Step 1：定义和后端对齐的类型**

`RunStatus`：

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

SSE event name：

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

字段名要求：

```text
tool_use 使用 toolName，不使用 name
final 使用 finalText、status、nextActionRequired
前端类型必须贴近当前后端 DTO，不额外发明字段
```

- [ ] **Step 2：实现 POST SSE parser**

Chat 使用 POST，所以不能用浏览器 `EventSource`。实现基于 `fetch + ReadableStream` 的 parser，解析这样的 chunk：

```text
event: text_delta
data: {"runId":"...","delta":"..."}

event: final
data: {"runId":"...","status":"SUCCEEDED"}
```

`sseParser.test.ts` 覆盖：

```text
单个 chunk 中一个 event
一个 event 被拆成两个 chunk
一个 chunk 中多个 event
ping event 保留到 debug log，但不写入 chat transcript
JSON 非法时产生 parser error event
```

- [ ] **Step 3：实现 API client**

`agentApi.ts` 暴露：

```text
createRun(userId, request, onEvent)
continueRun(userId, runId, message, onEvent)
getTrajectory(userId, runId)
abortRun(userId, runId)
interruptRun(userId, runId)
```

`adminApi.ts` 暴露：

```text
listRuns(params, adminToken)
getRuntimeState(runId, adminToken)
```

请求 header 规则：

```text
/api/agent/* 必须发送 X-User-Id
/api/admin/* 只有提供 adminToken 时才发送 X-Admin-Token
不在 console.log 中打印 adminToken
```

- [ ] **Step 4：验证**

运行：

```bash
cd admin-web
npm test
npm run build
```

预期：全部通过。

- [ ] **Step 5：提交**

```bash
git add admin-web/src/types admin-web/src/api
git commit -m "feat(console): add API clients and SSE parser"
```

### Task 7：构建 Console Shell

**文件：**

- 新建：`admin-web/src/components/shell/ConsoleShell.tsx`
- 新建：`admin-web/src/components/shell/Toolbar.tsx`
- 新建：`admin-web/src/components/ui/Button.tsx`
- 新建：`admin-web/src/components/ui/IconButton.tsx`
- 新建：`admin-web/src/components/ui/Badge.tsx`
- 新建：`admin-web/src/components/ui/EmptyState.tsx`
- 新建：`admin-web/src/components/ui/ErrorBanner.tsx`
- 新建：`admin-web/src/components/ui/Spinner.tsx`
- 修改：`admin-web/src/App.tsx`
- 测试：`admin-web/src/components/shell/ConsoleShell.test.tsx`

- [ ] **Step 1：实现三栏布局**

桌面布局：

```text
header 48px
main grid:
  left  280px min, 22vw max
  middle minmax(420px, 1fr)
  right 380px min, 32vw max
```

移动端布局：

```text
tabs: Runs | Timeline | Chat
```

- [ ] **Step 2：实现基础 UI 组件**

按钮图标：

```text
RefreshCw -> refresh
Square    -> interrupt
Ban       -> abort
Plus      -> new chat
Bug       -> debug
Send      -> send
```

视觉约束：

```text
border-radius 最大 8px
按钮文本不能溢出
紧凑面板内不用 hero 大字
不做 nested card
```

- [ ] **Step 3：验证**

运行：

```bash
cd admin-web
npm test
npm run build
```

预期：shell 能渲染 `Runs`、`Timeline`、`Chat` 三个区域。

- [ ] **Step 4：提交**

```bash
git add admin-web/src/components admin-web/src/App.tsx
git commit -m "feat(console): add console shell layout"
```

---

## 5. M3 接入真实数据

### Task 8：构建 Run List Panel

**文件：**

- 新建：`admin-web/src/hooks/useRunList.ts`
- 新建：`admin-web/src/components/runs/RunListPanel.tsx`
- 新建：`admin-web/src/components/runs/RunListItem.tsx`
- 新建：`admin-web/src/components/runs/RunFilters.tsx`
- 测试：`admin-web/src/components/runs/RunListPanel.test.tsx`

- [ ] **Step 1：实现 hook 状态**

`useRunList` 维护：

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

刷新规则：

```text
refresh 不主动清空当前选择
如果当前 selectedRunId 不在返回页面中，再清空或保持外部 selectedRunId 由 App 决定
```

- [ ] **Step 2：实现列表展示**

每个 run item 展示：

```text
status badge
短 runId
userId
provider/model
turnNo
updatedAt
parentRunId 存在时显示 child marker
parentLinkStatus 存在时显示 link status
```

- [ ] **Step 3：验证**

运行：

```bash
cd admin-web
npm test
npm run build
```

预期：loading、empty、error、data 状态都能正确渲染。

- [ ] **Step 4：提交**

```bash
git add admin-web/src/hooks/useRunList.ts admin-web/src/components/runs
git commit -m "feat(console): add run list panel"
```

### Task 9：构建 Timeline Panel

**文件：**

- 新建：`admin-web/src/hooks/useRunDetail.ts`
- 新建：`admin-web/src/components/timeline/TimelinePanel.tsx`
- 新建：`admin-web/src/components/timeline/TimelineItem.tsx`
- 新建：`admin-web/src/components/timeline/MessageNode.tsx`
- 新建：`admin-web/src/components/timeline/LlmAttemptNode.tsx`
- 新建：`admin-web/src/components/timeline/ToolCallNode.tsx`
- 新建：`admin-web/src/components/timeline/EventNode.tsx`
- 新建：`admin-web/src/components/timeline/CompactionNode.tsx`
- 测试：`admin-web/src/components/timeline/TimelinePanel.test.tsx`

- [ ] **Step 1：构建合并 timeline model**

把 trajectory DTO 数组合并成一个展示序列：

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
优先按时间排序
如果时间相同或缺失，稳定顺序为 message -> tool call -> tool progress -> tool result -> event -> compaction
```

- [ ] **Step 2：实现节点渲染**

必须展示的字段：

```text
MESSAGE: role、content preview、toolUseId
LLM_ATTEMPT: provider、model、status、finishReason、token usage
TOOL_CALL: toolName、concurrent/idempotent、precheckFailed
TOOL_RESULT: status、synthetic、cancelReason、result preview
EVENT: eventType、payload preview
COMPACTION: strategy、beforeTokens、afterTokens、compacted count
```

- [ ] **Step 3：验证**

运行：

```bash
cd admin-web
npm test
npm run build
```

预期：包含所有 item 类型的 fixture 能完整渲染。

- [ ] **Step 4：提交**

```bash
git add admin-web/src/hooks/useRunDetail.ts admin-web/src/components/timeline
git commit -m "feat(console): add run timeline panel"
```

### Task 10：构建 Runtime State Debug View

**文件：**

- 新建：`admin-web/src/hooks/useRuntimeState.ts`
- 新建：`admin-web/src/components/debug/DebugDrawer.tsx`
- 新建：`admin-web/src/components/debug/RuntimeStateView.tsx`
- 测试：`admin-web/src/components/debug/RuntimeStateView.test.tsx`

- [ ] **Step 1：实现 hook 行为**

`useRuntimeState(runId)` 请求：

```text
GET /api/admin/console/runs/{runId}/runtime-state
```

刷新时机：

```text
selected run 改变
用户点击 refresh
chat 收到 tool_use / tool_result / final event
```

- [ ] **Step 2：分组展示 runtime state**

分组：

```text
Control: meta, continuation-lock, control, llm-call-budget
Tool Runtime: queue, tools, tool-use-ids, leases
Planning: todos, todo-reminder
SubAgent: children
Active: active-runs
```

安全要求：

```text
不展示 confirm-tokens
不允许用户输入 Redis key
大 JSON 用折叠 preview 展示
```

- [ ] **Step 3：验证**

运行：

```bash
cd admin-web
npm test
npm run build
```

预期：缺失 key 能正常展示，不会报错。

- [ ] **Step 4：提交**

```bash
git add admin-web/src/hooks/useRuntimeState.ts admin-web/src/components/debug
git commit -m "feat(console): add runtime state debug view"
```

---

## 6. M4 Chat 和 SSE

### Task 11：构建 Chat Stream Hook

**文件：**

- 新建：`admin-web/src/hooks/useChatStream.ts`
- 新建：`admin-web/src/components/debug/SseEventLog.tsx`
- 测试：`admin-web/src/hooks/useChatStream.test.tsx`

- [ ] **Step 1：定义状态模型**

`useChatStream` 维护：

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

安全要求：

```text
不能存储 confirmToken
不能展示 confirmToken
不能把 adminToken 写入 sseEvents
```

- [ ] **Step 2：处理 SSE event**

事件效果：

| Event | State effect |
|---|---|
| `text_delta` | append 到当前 assistant draft |
| `tool_use` | 增加 tool card |
| `tool_progress` | 更新 progress card |
| `tool_result` | 增加 result preview |
| `final` | 设置 runId/status/nextActionRequired，关闭 draft |
| `error` | 设置 error，停止 streaming |
| `ping` | 只进入 debug log，不进入 chat transcript |

交互要求：

```text
WAITING_USER_CONFIRMATION -> 显示确认/拒绝 bar
PAUSED + user_input -> composer 提示用户补充信息
```

- [ ] **Step 3：验证**

运行：

```bash
cd admin-web
npm test
npm run build
```

预期：测试覆盖 happy path、HITL confirmation、可恢复 user input、error event。

- [ ] **Step 4：提交**

```bash
git add admin-web/src/hooks/useChatStream.ts admin-web/src/components/debug/SseEventLog.tsx
git commit -m "feat(console): add chat stream state"
```

### Task 12：构建 Chat Panel

**文件：**

- 新建：`admin-web/src/components/chat/ChatPanel.tsx`
- 新建：`admin-web/src/components/chat/ChatTranscript.tsx`
- 新建：`admin-web/src/components/chat/ChatComposer.tsx`
- 新建：`admin-web/src/components/chat/RunControls.tsx`
- 新建：`admin-web/src/components/chat/ConfirmationBar.tsx`
- 测试：`admin-web/src/components/chat/ChatPanel.test.tsx`

- [ ] **Step 1：实现创建 run**

默认请求：

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

发送到：

```text
POST /api/agent/runs
X-User-Id: <selected userId>
```

- [ ] **Step 2：实现 continuation**

`WAITING_USER_CONFIRMATION`：

```text
Confirm button -> content "确认继续执行"
Reject button  -> content "放弃本次操作"
```

`PAUSED + user_input`：

```text
composer placeholder = "补充订单号、说明或下一步指令..."
```

发送到：

```text
POST /api/agent/runs/{runId}/messages
X-User-Id: <selected userId>
body: {"message":{"role":"user","content":"..."}}
```

- [ ] **Step 3：实现 run controls**

控制项：

```text
New Chat
Refresh Run
Interrupt
Abort
Toggle Debug
```

禁用规则：

```text
runStatus 是 terminal 时禁用 Interrupt 和 Abort
isStreaming=true 时禁用重复 Send
没有 selectedRunId 时禁用 Refresh Run
```

- [ ] **Step 4：验证**

运行：

```bash
cd admin-web
npm test
npm run build
```

预期：测试覆盖 create、continue、confirm、reject、interrupt、abort 按钮状态。

- [ ] **Step 5：提交**

```bash
git add admin-web/src/components/chat
git commit -m "feat(console): add chat panel and controls"
```

### Task 13：串联 App 全局状态

**文件：**

- 修改：`admin-web/src/App.tsx`
- 修改：`admin-web/src/components/shell/Toolbar.tsx`
- 测试：`admin-web/src/App.test.tsx`

- [ ] **Step 1：App 持有共享状态**

`App` 维护：

```text
selectedRunId
selectedUserId default "demo-user"
adminToken from localStorage
debugOpen
```

状态联动：

```text
chat 创建 run 后，从第一个包含 runId 的 SSE event 设置 selectedRunId
run list 选择 run 后，timeline 和 runtime state 读取该 run
chat 收到 final 后 refresh run list 和 timeline
```

- [ ] **Step 2：实现 Toolbar**

Toolbar 字段：

```text
User ID input
Admin token input
Refresh runs
Debug toggle
```

存储规则：

```text
userId 存入 localStorage
adminToken 存入 localStorage
不把 adminToken 打到 console 或 debug panel
```

- [ ] **Step 3：验证**

运行：

```bash
cd admin-web
npm test
npm run build
```

预期：选择 run 后 timeline 和 runtime debug view 同步更新。

- [ ] **Step 4：提交**

```bash
git add admin-web/src/App.tsx admin-web/src/components/shell/Toolbar.tsx
git commit -m "feat(console): wire run selection across panels"
```

---

## 7. M5 Hardening 和 Demo

### Task 14：增加本地 Demo 启动脚本和 README

**文件：**

- 新建：`scripts/start-console-dev.sh`
- 修改：`README.md`

- [ ] **Step 1：实现启动脚本**

脚本行为：

```text
检查 MySQL 是否可连接
检查 Redis 是否可连接
如果 8080 未监听，启动 Spring Boot
启动 Vite admin-web，端口 5173
打印后端 URL 和前端 URL
```

约束：

```text
脚本不内置 provider API key
脚本不打印 secret
如果端口已占用，说明如何处理，不静默换端口
```

- [ ] **Step 2：更新 README 中文说明**

README 增加章节：

```text
Agent Buyer Console
- 启动后端
- 启动前端
- 打开 http://127.0.0.1:5173
- 示例用户 demo-user
- 推荐演示 prompt
```

推荐演示 prompt：

```text
取消我昨天的那个订单，先查订单再 dry-run
没问题，按刚才的取消方案继续处理
这个任务比较复杂，先创建 ToDo，再查最近订单并总结
/purchase-guide 查询最近订单并结合 skill 给我建议
```

- [ ] **Step 3：验证**

运行：

```bash
bash -n scripts/start-console-dev.sh
```

预期：无语法错误。

- [ ] **Step 4：提交**

```bash
git add scripts/start-console-dev.sh README.md
git commit -m "docs(console): add local console demo instructions"
```

### Task 15：增加集成测试和浏览器 Smoke

**文件：**

- 新建：`admin-web/src/test/fixtures/trajectory.ts`
- 新建：`admin-web/src/test/fixtures/sseEvents.ts`
- 新建：`admin-web/src/App.integration.test.tsx`
- 可选新建：`scripts/console-smoke.sh`

- [ ] **Step 1：准备前端集成 fixture**

fixture 必须包含：

```text
一个 run summary
一个 USER message
一个 ASSISTANT message with tool call
一个 LLM attempt
一个 tool call
一个 tool progress
一个 tool result
一个 confirmation final SSE event
一个 compaction
一个 runtime-state response
```

- [ ] **Step 2：实现集成测试**

使用 mocked fetch 渲染 `App`，验证：

```text
run list 能显示
timeline item 包含 message/tool/attempt/event
chat text_delta 会更新 transcript
WAITING_USER_CONFIRMATION 会显示确认/拒绝控件
runtime state view 不展示 confirm token
```

- [ ] **Step 3：浏览器 smoke**

实现后用 Playwright 或 in-app browser 检查：

```text
desktop 1440x900 screenshot
mobile 390x844 screenshot
没有面板重叠
按钮文字不溢出
chat 能发送 prompt 并收到 SSE final
```

- [ ] **Step 4：最终验证**

运行：

```bash
MYSQL_PASSWORD='Qaz1234!' mvn test
cd admin-web && npm test && npm run build
```

真实 LLM e2e 作为单独 smoke：

```bash
MYSQL_PASSWORD='Qaz1234!' \
DEEPSEEK_API_KEY='<local env only>' \
QWEN_API_KEY='<local env only>' \
./scripts/real-llm-e2e.sh
```

预期：

```text
mvn test: BUILD SUCCESS
npm test: all tests pass
npm run build: success
real-llm-e2e: full suite passed
```

- [ ] **Step 5：提交**

```bash
git add admin-web/src/test admin-web/src/App.integration.test.tsx scripts/console-smoke.sh
git commit -m "test(console): add frontend integration smoke"
```

---

## 8. 验收标准

### 8.1 产品验收

- 用户可以从 console 创建 run，并实时看到 SSE text/tool events。
- 用户可以继续 `WAITING_USER_CONFIRMATION` 和 `PAUSED + user_input` 的 run。
- 用户可以从 console interrupt 或 abort 活跃 run。
- 选择 run 后能看到 trajectory timeline：messages、attempts、tools、events、progress、compactions。
- Debug drawer 能展示当前 run 的 Redis runtime state，但不能任意查 Redis key。
- Console 使用真实 `/api/agent/*` chat endpoint，不新增 admin chat proxy。

### 8.2 工程验收

- 新 Java package path 是 `com/ai/agent/web/admin`，不是 `web.admin`。
- 后端 admin endpoint 有 controller test 和 service test。
- 前端 POST SSE 使用 `fetch + ReadableStream`，不是 `EventSource`。
- 前后端字段名对齐当前代码：`toolName`、`finalText`、`nextActionRequired`、`RunStatus`。
- 前端 state、timeline、runtime state、日志、截图中都不能出现原始 `confirmToken`。
- 不存在通用 `SELECT * FROM <user input>` 或任意 Redis key endpoint。
- 最终交付前 `mvn test`、`npm test`、`npm run build` 全部通过。

---

## 9. 实现者注意事项

每个 task 开始前：

1. 运行 `git status --short`。
2. 不修改无关 V2 后端代码。
3. 先写或更新测试。
4. 每个任务独立 commit，commit message 使用 `feat(console): ...`、`test(console): ...` 或 `docs(console): ...`。
5. 如果发现后端契约和计划不一致，先更新计划或 progress note，再改代码。

本计划特别避开的坑：

- `AgentRunEntity` 没有 provider/model 字段，provider/model 在 `agent_run_context`。
- `UserMessage` 构造是 `(role, content)`，不是 `(content, metadata)`。
- `LlmParams` 当前字段是 `model`、`temperature`、`maxTokens`、`maxTurns`。
- Spring SSE 类是 `SseAgentEventSink`，但前端只调用现有 controller，不构造后端 sink。
- 浏览器 `EventSource` 不能 POST，chat streaming 使用 `fetch`。
- Redis key 带 `{}` 和 `:`，不要把完整 Redis key 放进 path variable。
- `confirm-tokens` 故意不进入 runtime-state 输出。

---

## 10. 执行方式

计划已保存到：

```text
docs/superpowers/plans/2026-05-02-admin-frontend-plan.md
```

推荐执行方式：

1. **Subagent-Driven：** M1 后端一个 worker；M2/M3 前端一个 worker；每个里程碑结束后做 review gate。
2. **Inline Execution：** 在当前会话按里程碑顺序执行，每个 gate 跑完测试再进入下一阶段。

不要在 M3 timeline 能展示真实 run trajectory 之前开始 M4 chat。
