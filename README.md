# agent-buyer

> **你**：取消我昨天的那个订单。
>
> **Agent**：好的，我先查一下你昨天的订单……找到 `O-1001 - 降噪耳机 ¥129.90`。  
> 你确认取消吗？此操作会触发退款流程，无法撤销。
>
> **你**：确认。
>
> **Agent**：已取消。订单 `O-1001` 状态更新为 `CANCELLED`，退款已发起。

这一段对话背后发生了什么：模型调了两次 `query_order`、一次 `cancel_order` dry-run、等了你确认、用服务端持有的 token 真正执行了 `cancel_order`、写了 11 行 trajectory、SSE 流式回了 5 个事件、跑了 1 次 DeepSeek call。**全过程可观测、可重放、可中断、可在两个实例间无缝迁移。**

这就是 agent-buyer。

---

## 一分钟跑起来

```bash
# 本地依赖（MySQL 8 + Redis 7）
docker start agent-buyer-mysql8 agent-buyer-redis7

# 跑后端
export MYSQL_PASSWORD='<your password>' DEEPSEEK_API_KEY='<your key>'
mvn spring-boot:run

# 一个完整 demo（自动走"取消订单 → 确认 → 完成"全流程）
./scripts/demo-cancel-order.sh
```

想看可视化界面（trajectory timeline + chat + runtime debug）：

```bash
./scripts/start-console-dev.sh   # 然后浏览器开 http://localhost:5173
```

---

## 这是什么

把已有的 Java 业务系统（这里是订单查询/取消）包装成 LLM agent 的一层。**不是 chatbot demo**——是一个完整的 agent runtime：模型真的调工具，工具真的写库，调度真的跨实例原子化。

**不是这些**：

- ❌ LangChain4j / Spring AI 的 wrapper
- ❌ 一个 RAG 或 vector demo
- ❌ "我接通了 OpenAI API" 级别的项目
- ❌ 全部从教程抄来的 best practices 集合

**是这些**：

- ✅ Java/Spring Boot 写的完整 agent harness（约 15K 行生产代码 + 12K 行测试）
- ✅ DeepSeek + Qwen 双 provider，自动 fallback——但**只在安全的时机**（流式中途绝不切，否则会业务双执行）
- ✅ 多实例 Redis Lua 原子调度，6 段外置脚本，282 个测试全绿
- ✅ 6 轮真实 LLM E2E 跑通（DeepSeek + Qwen 真 API，不 mock）
- ✅ 11 张 MySQL 表的事件流式 trajectory，任意时刻可重放
- ✅ React + Vite 写的 admin console，能看到每个 run 的完整故事

---

## 为什么花 5 分钟看这个项目可能值得

挑你最在意的角度看：

**如果你在做 agent 系统**——`agent-buyer` 给了你一份 Java 生态下的参考，特别是这两件大部分人会做错的事：

1. **Provider fallback 的安全边界**：错误分三态（`RETRYABLE_PRE_STREAM` / `NON_RETRYABLE` / `STREAM_STARTED`），fallback 只在 `PRE_STREAM` 时放行。流已经吐了 tool delta 后切 provider，会让业务工具被双重执行。这条边界在 [`llm.provider.ProviderFallbackPolicy`](src/main/java/com/ai/agent/llm/provider/ProviderFallbackPolicy.java) 51 行里。
2. **HITL 不让 LLM 持有 confirmToken**：dry-run 后服务端持久化 pending tool，用户确认后服务端直接执行——绕过 LLM。代码在 [`application.PendingConfirmToolStore`](src/main/java/com/ai/agent/application) + [`loop.ToolCallCoordinator.executePendingConfirmTool`](src/main/java/com/ai/agent/loop)。

**如果你在做基础架构 / 后端**——这个项目主要的工程价值是 Redis 和 MySQL 的用法：

1. **6 段外置 Lua 脚本**（[`src/main/resources/redis/`](src/main/resources/redis/)）做调度原子性。`schedule.lua` 80 行实现 safe 工具并发 + 写工具串行屏障 + lease 三段式 token；`complete.lua` 28 行做三层 CAS（status / attempt / leaseToken）。
2. **多实例 schedule 不重复执行任何 tool**——靠 `INGEST` 的 HSETNX 幂等键、`SCHEDULE` 的 attempt 自增、`COMPLETE` 的三层 CAS。
3. **MySQL 11 个 Flyway migration** 含 V6 fail-closed（修一个 V5 误授权）和 V11 主动删除影子索引（V10 加新覆盖索引后）。
4. **Run 状态机 118 行 0 处直接 UPDATE**，全部走 CAS + 5 次 retry 上限（[`application.RunStateMachine`](src/main/java/com/ai/agent/application/RunStateMachine.java)）。

**如果你是工程负责人 / tech lead**——看这个项目的工程纪律：

- `spec/{requirement,design,task,progress}.md` 四件套维护项目宪法和决策日志
- 每个里程碑独立做 review gate（用 `java-alibaba-review` skill 独立审），P0/P1/P2 未清零不允许标 DONE
- [`spec/progress.md`](spec/progress.md) 1300+ 行真实开发记录——能看到一次 confirmation intent 修复走了 3 轮 review 才稳

---

## 看一眼真实的 SSE 流

发请求：

```bash
curl -N -H 'X-User-Id: demo-user' -H 'Content-Type: application/json' \
  -d '{"messages":[{"role":"user","content":"取消我昨天的那个订单"}],
       "allowedToolNames":["query_order","cancel_order"]}' \
  http://127.0.0.1:8080/api/agent/runs
```

返回（精简）：

```
event: text_delta    data: {"content":"我先帮你查一下昨天的订单..."}
event: tool_use      data: {"toolName":"query_order","args":{"userId":"demo-user","daysAgo":1}}
event: tool_progress data: {"status":"RUNNING"}
event: tool_result   data: {"orders":[{"orderId":"O-1001","amount":129.9,"status":"PAID"}]}
event: tool_use      data: {"toolName":"cancel_order","args":{"orderId":"O-1001"},"dryRun":true}
event: tool_result   data: {"actionStatus":"PENDING_CONFIRM","summary":"将取消订单 O-1001..."}
event: final         data: {"runId":"run_xxx","status":"WAITING_USER_CONFIRMATION","nextActionRequired":"user_input"}
```

继续发 `确认取消这个订单`：

```
event: tool_use      data: {"toolName":"cancel_order","args":{"orderId":"O-1001","confirmToken":"***"}}
event: tool_result   data: {"status":"CANCELLED","refundStatus":"INITIATED"}
event: text_delta    data: {"content":"已经帮你取消了订单 O-1001..."}
event: final         data: {"status":"SUCCEEDED"}
```

`business_order.O-1001.status` 现在是 `CANCELLED`。整个过程在 MySQL 留了完整 trajectory，admin console 上能看到每一步。

---

## 架构（hot path）

```
HTTP POST /api/agent/runs                    ← SSE 流
   │
   ▼
AgentController ──→ AgentRunApplicationService
                      │  权限校验 / 请求预算 / RunContext 落库
                      ▼
                   DefaultAgentLoop ──→ AgentTurnOrchestrator
                                            │
                              ┌─────────────┴────────────┐
                              ▼                          ▼
                       LlmAttemptService         ToolCallCoordinator
                              │                          │
                              ▼                          ▼
                     ContextViewBuilder           Redis ToolRuntime
                     (Spill / Micro / Summary)    (6 段外置 Lua)
                              │                          │
                              ▼                          │
                     LlmProviderRegistry                 │
                     ├─ DeepSeek (primary)               │
                     └─ Qwen (fallback, PRE_STREAM only) │
                                                         ▼
                                                  business tools
                                                  (query_order / cancel_order /
                                                   skill_list / skill_view /
                                                   todo_create / todo_write /
                                                   agent_tool → SubAgent)

           ↓ 全程写入 ↓
        MySQL trajectory (event-sourced, 11 tables, 11 Flyway migrations)
```

完整 package 边界 → [`spec/package-architecture.md`](spec/package-architecture.md)

---

## 想看哪个细节直接去看

| 想看什么 | 文件 |
|---|---|
| Redis Lua 调度（schedule / complete CAS / abort / lease reaper） | [`src/main/resources/redis/tool/`](src/main/resources/redis/tool/) |
| SubAgent 双幂等键 + 三层 budget | [`src/main/resources/redis/subagent/reserve-child.lua`](src/main/resources/redis/subagent/reserve-child.lua) |
| Provider fallback 安全边界 | [`llm.provider.ProviderFallbackPolicy`](src/main/java/com/ai/agent/llm/provider/ProviderFallbackPolicy.java) |
| Context 三阶段压缩 | [`llm.context.ContextViewBuilder`](src/main/java/com/ai/agent/llm/context/ContextViewBuilder.java) + [`llm.compact/`](src/main/java/com/ai/agent/llm/compact/) |
| Run 状态机 + CAS retry | [`application.RunStateMachine`](src/main/java/com/ai/agent/application/RunStateMachine.java) |
| HITL 服务端执行 pending tool | [`loop.ToolCallCoordinator`](src/main/java/com/ai/agent/loop/ToolCallCoordinator.java) |
| Synthetic tool result 闭合 | [`loop.ToolResultCloser`](src/main/java/com/ai/agent/loop/ToolResultCloser.java) |
| MySQL schema 演化（含 V6 fail-closed、V11 删除影子索引） | [`src/main/resources/db/migration/`](src/main/resources/db/migration/) |
| 多实例 sweeper + Pub/Sub fallback | [`tool.runtime.ActiveRunSweeper`](src/main/java/com/ai/agent/tool/runtime/) |

---

## 范围与里程碑（全部 DONE）

| 里程碑 | 内容 |
|---|---|
| **V1a** | 基础 agent loop + DeepSeek + Redis 调度 + MySQL trajectory + HITL + 安全边界 |
| **V2.0** | DeepSeek + Qwen 双 provider + fallback PRE_STREAM 边界 + LLM 调用预算 + context compact 三阶段 |
| **V2.1** | Anthropic-style skill 渐进式加载 + SubAgent / AgentTool 同步委派 |
| **V2.2** | 多实例部署 + Pub/Sub + ToDo + interrupt 级联 |
| **V3** | React admin console（run list / timeline / runtime debug / chat + SSE） |

明确**不**做：GLM、RAG / vector store、LangChain4j AI Services 接管 loop、Kafka / RabbitMQ、完整 outbox、生产级 RBAC、通用 Redis/MySQL browser。理由全部写在 [`spec/`](spec/) 里。

---

## API

```http
POST   /api/agent/runs                      创建 run，SSE 返回
POST   /api/agent/runs/{runId}/messages     追加消息（continuation / 确认 / 拒绝）
POST   /api/agent/runs/{runId}/abort        终止 run
POST   /api/agent/runs/{runId}/interrupt    中断当前 turn 进 PAUSED
GET    /api/agent/runs/{runId}              查询完整 trajectory（DTO 脱敏）

GET    /api/admin/console/runs              run list（admin token）
GET    /api/admin/console/runs/{runId}/runtime-state   Redis 运行态投影
```

所有请求需要 `X-User-Id`；admin endpoint 额外需要 `X-Admin-Token`（local/demo profile 可空）。

负向用例和完整请求示例：[`postman/agent-buyer-v1a.postman_collection.json`](postman/agent-buyer-v1a.postman_collection.json)

---

## 测试

```bash
# 后端 282 tests（用 Testcontainers 起真 MySQL/Redis）
MYSQL_PASSWORD='<password>' mvn test

# 前端 162 tests
cd admin-web && npm test

# 真实 LLM E2E（不进默认 mvn test，覆盖 6 个完整场景）
export DEEPSEEK_API_KEY='<key>' QWEN_API_KEY='<key>'
./scripts/real-llm-e2e.sh
```

E2E 覆盖：订单取消 dry-run + 确认、ToDo create/write、AgentTool → SubAgent、interrupt → PAUSED、slash skill + 三类 context compact、DeepSeek 故障 fallback Qwen。产物落 `/tmp/agent-buyer-real-llm-e2e/<timestamp>/`。

---

## 关键配置

`src/main/resources/application.yml`：

```yaml
agent:
  agent-loop:
    llm-call-budget-per-user-turn: 30        # main agent 单 turn LLM call 上限
    run-wide-llm-call-budget: 80             # run 累计 LLM call 上限
  context:
    large-result-threshold-tokens: 2000      # 单 tool result 超过触发 spill
    micro-compact-threshold-tokens: 50000    # provider view 超过触发 micro compact
    summary-compact-threshold-tokens: 30000  # 超过触发 summary compact
  sub-agent:
    max-spawn-per-run: 2                     # 单 run 累计 SubAgent 上限
    max-concurrent-per-run: 1                # 同时 in-flight 上限
    wait-timeout-ms: 180000                  # 父等子 3 分钟
  skills:
    max-per-message: 3                       # 单 user message slash skill 上限
    max-token-per-message: 8000              # slash skill 总 token 上限
```

完整 → [`application.yml`](src/main/resources/application.yml)

---

## 已知限制（不藏着）

- 多实例靠 sweeper + Redis CAS + Pub/Sub/polling fallback，没引入 MQ worker / outbox
- Fallback 只覆盖 pre-stream retryable failure（流式中途绝不切 provider，这是 feature）
- TokenEstimator 用 `chars/4` 线性估计，中文场景误差 ±30%（下一步接 jtokkit）
- Lua 没主动 `SCRIPT LOAD` 预热（Spring Data 的 EVALSHA cache 在 Redis 重启后会失效一次）
- SubAgent 同步等待，单 run 累计 ≤ 2、in-flight ≤ 1（异步唤醒模型未做）
- Admin console 不做生产级 RBAC，仅 local/demo profile 安全

---

## 关于 AI 协作开发

这个项目的代码由 AI（Claude Code 与 Codex）协作生成。我作为人在这个项目里做的事是：

- **定义不变量**——`spec/requirement.md` 里那些"必须"和"绝不"的取舍是我和 AI 多轮迭代后由我决定的（比如"已 emit tool delta 后绝不 fallback"）
- **控制写入边界**——`spec/task.md` 里每个 task 的写入文件清单和"高冲突文件单 owner"规则
- **判断 review finding**——哪些是 P0/P1 必修、哪些 P3 可拖、哪些是 reviewer 过度
- **跑真实 E2E 找出单测覆盖不到的协议级 bug**——比如 HITL 让 LLM 复制 confirmToken 会让 toolCallId 复用（详见 [`spec/progress.md`](spec/progress.md)）

**参考但不照搬 Anthropic Claude Code** 的部分：skill 渐进式加载、SubAgent / AgentTool、SummaryCompactor 字段（`businessFacts / toolFacts / openQuestions`）、ToDo reminder 注入。

**独立设计的部分**：fallback 的 `STREAM_STARTED` 边界、HITL 服务端直接执行 pending tool 模式、abort 对非幂等写工具不伪造结果、`{run:<runId>}` Redis hash tag + 6 段外置 Lua、CAS retry 状态机、V6 fail-closed migration、V10/V11 由 sort buffer 不足驱动的索引演化。

---

## 链接

- [`spec/requirement.md`](spec/requirement.md) — 系统级不变量与重构目标
- [`spec/design.md`](spec/design.md) — 详细组件设计
- [`spec/task.md`](spec/task.md) — 任务拆分与 review gate
- [`spec/progress.md`](spec/progress.md) — 1300+ 行开发过程日志（review-fix 循环、踩坑、修复）
- [`spec/package-architecture.md`](spec/package-architecture.md) — Package 边界与依赖方向
- [`admin-web/README.md`](admin-web/README.md) — Console 前端

---

License: MIT.
