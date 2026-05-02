# Agent Buyer Console V3 设计

## 概述

V3 为 `agent-buyer` 增加一个本地可演示、可调试的 Agent Buyer Console。它不是通用 MySQL/Redis 管理后台，而是面向 agent lifecycle 的产品化观察台：通过 run list、trajectory timeline、chat/SSE、runtime state debug 四类视图，把 AgentLoop、tool calling、human-in-the-loop、interrupt、SubAgent、ToDo、context compact 等能力展示清楚。

V3 的核心原则是“复用 agent-buyer 现有业务接口，只补最小 console 查询能力”。Chat、SSE、trajectory 继续走 `/api/agent/*`；后端只新增分页 run list 和当前 run runtime-state 两个 admin endpoint。这样可以避免前端和后端各做一套 chat proxy，也避免把 demo console 做成不安全的通用数据库浏览器。

## 目录

- [需求摘要](#需求摘要)
- [V3 范围边界](#v3-范围边界)
- [整体架构](#整体架构)
- [视觉风格](#视觉风格)
- [前端信息架构](#前端信息架构)
- [前端组件设计](#前端组件设计)
- [后端 API 设计](#后端-api-设计)
- [数据流与交互](#数据流与交互)
- [错误处理与安全](#错误处理与安全)
- [测试与部署](#测试与部署)
- [开发计划](#开发计划)

## 需求摘要

| 需求项 | V3 决策 |
|--------|---------|
| 产品定位 | Agent Buyer Console，用于演示和调试 agent-buyer，而不是通用管理后台 |
| 技术栈 | React 18 + TypeScript + Vite + Tailwind CSS + lucide-react |
| 部署位置 | 前端放在当前仓库 `admin-web/`，后端 admin API 放在 `com.ai.agent.web.admin` |
| 页面结构 | 三栏工作台：Run List、Run Timeline、Chat / Controls，Debug Drawer 展示 runtime state |
| Chat 能力 | 直接调用现有 `/api/agent/runs` 和 `/api/agent/runs/{runId}/messages`，使用 POST SSE streaming |
| Run 查询 | 新增 `GET /api/admin/console/runs`，分页展示 run 摘要 |
| Runtime 查询 | 新增 `GET /api/admin/console/runs/{runId}/runtime-state`，只展示当前 run 固定 Redis key |
| Trajectory | 复用 `GET /api/agent/runs/{runId}`，展示 messages、attempts、tool calls、tool results、events、compactions |
| 安全边界 | 不暴露任意 SQL、任意 Redis key、`confirmToken`、provider raw payload、secret |
| 认证方式 | 本地 demo 使用可选 `X-Admin-Token`；用户身份仍由 `/api/agent/*` 的 `X-User-Id` 表达 |
| 设计风格 | 浅色、紧凑、信息密度适中的运维控制台风格，避免营销页、装饰背景和卡片套卡片 |

## V3 范围边界

### V3 必须做

- 展示最近 run 列表，支持 status、userId、分页筛选。
- 选择 run 后展示 trajectory timeline。
- 在 console 内创建 run、继续 run、确认/拒绝 human-in-the-loop、interrupt、abort。
- 实时展示 SSE `text_delta`、`tool_use`、`tool_progress`、`tool_result`、`final`、`error`、`ping`。
- Debug drawer 展示当前 run 的 Redis runtime state。
- 本地启动脚本和 README 能让 reviewer 快速跑起 demo。

### V3 不做

- 不做通用 MySQL 表浏览。
- 不做任意 Redis key 查询。
- 不新增 `/api/admin/chat`。
- 不做多租户后台权限系统。
- 不展示原始 `confirmToken`。
- 不展示完整未脱敏大 tool result。
- 不把 provider API key、admin token 写入日志、debug 面板或截图。

## 整体架构

```text
┌─────────────────────────────────────────────────────────────┐
│                 admin-web (React Console)                    │
│                                                             │
│  ┌──────────────┬─────────────────────┬──────────────────┐  │
│  │  Run List    │    Run Timeline      │  Chat / Controls │  │
│  │              │                     │                  │  │
│  │  status      │  messages           │  user input      │  │
│  │  userId      │  attempts           │  SSE stream      │  │
│  │  provider    │  tool calls/results │  HITL actions    │  │
│  │  model       │  events/compactions │  abort/interrupt │  │
│  └──────────────┴─────────────────────┴──────────────────┘  │
│                                                             │
│  Debug Drawer: current-run runtime state + SSE event log     │
└─────────────────────────────────────────────────────────────┘
                              │
                              │ REST + POST SSE
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                 agent-buyer Spring Boot                      │
│                                                             │
│  Existing API                                                │
│    /api/agent/runs                                           │
│    /api/agent/runs/{runId}/messages                          │
│    /api/agent/runs/{runId}                                   │
│    /api/agent/runs/{runId}/abort                             │
│    /api/agent/runs/{runId}/interrupt                         │
│                                                             │
│  V3 Admin Console API                                        │
│    /api/admin/console/runs                                   │
│    /api/admin/console/runs/{runId}/runtime-state             │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
        ┌─────────────────┬─────────────────────────────┐
        │     MySQL       │            Redis            │
        │  trajectory     │ current run runtime state   │
        └─────────────────┴─────────────────────────────┘
```

关键设计点：

- 前端只面向 agent lifecycle 展示，不变成数据库浏览器。
- 后端新增 API 只提供 console 必须的数据投影。
- Chat 流程直接复用现有 `/api/agent/*`，保证 console 看到的行为就是用户真实使用路径。
- Runtime state 只读 `RedisKeys` 推导出的固定 key，不接受用户自定义 key。
- trajectory 以已有安全 DTO 为准，不直接返回 MyBatis entity 或 provider raw payload。

## 视觉风格

V3 采用浅色工作台风格，强调可读性和扫描效率：

| 元素 | 建议 |
|------|------|
| 主背景 | `#f5f3ec`，柔和浅色 |
| 主文字 | `#24231f` |
| 面板边框 | 低对比度边线，避免厚重阴影 |
| 强调色 | 用于选中 run、主要按钮、active 状态 |
| 状态色 | success/warning/error/info 清晰区分 |
| 圆角 | 统一 8px 以内 |
| 字号 | 控制台面板内使用紧凑字号，不使用 hero 大字 |

设计约束：

- 不使用营销页 hero。
- 不使用装饰性渐变球、orb、bokeh 背景。
- 不使用卡片套卡片。
- 按钮优先使用 lucide icon。
- 所有按钮和 badge 必须在移动端不溢出。
- 三栏布局在桌面保持信息密度，移动端切换为 tabs。

## 前端信息架构

桌面端：

```text
header 48px
main grid:
  left  280px min, 22vw max     -> Run List
  middle minmax(420px, 1fr)     -> Timeline
  right 380px min, 32vw max     -> Chat / Controls
debug drawer                    -> Runtime State + SSE Log
```

移动端：

```text
tabs:
  Runs
  Timeline
  Chat
debug drawer 独立打开
```

主要状态：

```text
selectedRunId
selectedUserId default "demo-user"
adminToken from localStorage
debugOpen
runList filters
chat stream state
runtime state cache
```

## 前端组件设计

```text
admin-web/
  src/
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

组件职责：

| 组件 | 职责 |
|------|------|
| `ConsoleShell` | 三栏布局与移动端 tabs |
| `Toolbar` | userId、adminToken、refresh、debug toggle |
| `RunListPanel` | run 列表、筛选、分页、选择 run |
| `TimelinePanel` | 合并展示 messages、attempts、tool calls/results、events、compactions |
| `ChatPanel` | 创建 run、继续 run、显示 chat transcript |
| `ConfirmationBar` | 处理 `WAITING_USER_CONFIRMATION` 的确认和拒绝 |
| `RunControls` | new chat、refresh、interrupt、abort |
| `RuntimeStateView` | 展示当前 run 固定 Redis key 的 state |
| `SseEventLog` | 展示原始 SSE event log，便于调试 |

## 后端 API 设计

### Package 结构

```text
com.ai.agent.web.admin
  controller
    AdminConsoleController
  dto
    AdminPageResponse
    AdminRunListResponse
    AdminRunSummaryDto
    AdminRuntimeStateDto
    AdminRedisEntryDto
  service
    AdminAccessGuard
    AdminRunListService
    AdminRuntimeStateService
```

### 新增 API

| API | 方法 | 描述 |
|-----|------|------|
| `/api/admin/console/runs` | GET | 分页查询 run 摘要 |
| `/api/admin/console/runs/{runId}/runtime-state` | GET | 查询当前 run 的 Redis runtime state |

### 复用 API

| API | 方法 | 用途 |
|-----|------|------|
| `/api/agent/runs` | POST | 创建 run，返回 SSE |
| `/api/agent/runs/{runId}/messages` | POST | continuation，返回 SSE |
| `/api/agent/runs/{runId}` | GET | 查询 trajectory |
| `/api/agent/runs/{runId}/abort` | POST | abort run |
| `/api/agent/runs/{runId}/interrupt` | POST | interrupt current turn |

### Run List DTO

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

查询规则：

- `provider/model` 从 `agent_run_context` 读取。
- 支持 `status` 和 `userId` 过滤。
- 固定 `ORDER BY r.updated_at DESC`。
- 不支持动态表名、动态排序字段或自由 SQL。

### Runtime State DTO

```java
public record AdminRuntimeStateDto(
        String runId,
        boolean activeRun,
        List<AdminRedisEntryDto> entries
) {
}
```

只允许读取：

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

明确禁止读取：

```text
confirm-tokens
任意用户输入 Redis key
wildcard scan
```

## 数据流与交互

### 创建 run

```text
用户输入 prompt
  ↓
ChatPanel 调用 createRun(userId, request, onEvent)
  ↓
POST /api/agent/runs
  ↓
fetch + ReadableStream 解析 SSE
  ↓
text_delta 更新 assistant draft
tool_use / tool_progress / tool_result 更新 tool cards
final 设置 runStatus / nextActionRequired / selectedRunId
  ↓
刷新 run list / timeline / runtime state
```

### 继续 run

```text
runStatus = WAITING_USER_CONFIRMATION
  ↓
ConfirmationBar 显示 Confirm / Reject
  ↓
POST /api/agent/runs/{runId}/messages
  ↓
同样使用 POST SSE
```

`PAUSED + user_input` 时，composer 提示用户补充订单号、说明或下一步指令。

### Timeline 合并

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
优先按 timestamp 排序
时间相同或缺失时稳定排序：
message -> tool call -> tool progress -> tool result -> event -> compaction
```

### Runtime state 展示

分组：

```text
Control: meta, continuation-lock, control, llm-call-budget
Tool Runtime: queue, tools, tool-use-ids, leases
Planning: todos, todo-reminder
SubAgent: children
Active: active-runs
```

## 错误处理与安全

| 风险 | V3 处理 |
|------|---------|
| admin console 被当成数据库浏览器 | 不提供任意 SQL 和任意 Redis key |
| `confirmToken` 泄露 | 后端 runtime state 不读，前端不存不展示 |
| admin token 泄露 | 只存在 localStorage 和 request header，不打印 |
| POST SSE 用错 `EventSource` | 使用 `fetch + ReadableStream` |
| runtime state 太大 | 大 JSON 折叠展示，只显示 preview |
| trajectory 包含敏感字段 | 复用已有 trajectory DTO 和脱敏策略 |
| run 终态误操作 | terminal status 禁用 interrupt / abort |
| provider 或网络错误 | SSE `error` event 进入 error banner 和 debug log |

## 测试与部署

### 后端测试

```text
AdminAccessGuardTest
AdminRunListServiceTest
AdminRuntimeStateServiceTest
AdminConsoleControllerTest
```

验证：

- token blank / match / mismatch / disabled。
- run list 分页、过滤、排序、join context。
- runtime state 只读固定 key，不包含 `confirm-tokens`。
- controller 异常映射为 400 / 403 / 503。

### 前端测试

```text
App.test.tsx
sseParser.test.ts
RunListPanel.test.tsx
TimelinePanel.test.tsx
RuntimeStateView.test.tsx
useChatStream.test.tsx
ChatPanel.test.tsx
App.integration.test.tsx
```

验证：

- 三栏 layout 渲染。
- POST SSE parser 支持 split chunk、多 event、非法 JSON。
- run list、timeline、runtime state、chat 状态更新。
- `WAITING_USER_CONFIRMATION` 展示确认/拒绝控件。
- runtime state 不展示 confirm token。

### 本地启动

```bash
MYSQL_PASSWORD='Qaz1234!' mvn spring-boot:run
cd admin-web && npm run dev
```

访问：

```text
http://127.0.0.1:5173
```

## 开发计划

V3 按五个里程碑推进：

| 里程碑 | 内容 | Gate |
|---|---|---|
| M1 | 后端 Console API：admin guard、run list、runtime state、controller | `mvn test` |
| M2 | 前端 Shell：Vite 工程、三栏 layout、基础 UI | `npm test && npm run build` |
| M3 | 真实数据接入：run list、timeline、runtime debug | 前后端测试 |
| M4 | Chat + SSE：create、continue、HITL、interrupt、abort | 本地 smoke |
| M5 | Hardening：README、启动脚本、集成测试、浏览器 smoke | 全量验证 |

执行要求：

- M1 完成前不开始 M2。
- M3 能展示真实 trajectory 前不开始 M4。
- 每个里程碑完成后 review，再进入下一阶段。
- 每个 task 单独 commit，便于回滚和评审。
