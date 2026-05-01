# Package Architecture

## 目录

- [1. 目标](#1-目标)
- [2. 顶层分层](#2-顶层分层)
- [3. 核心链路导航](#3-核心链路导航)
- [4. 依赖方向](#4-依赖方向)
- [5. 维护规则](#5-维护规则)

## 1. 目标

本次 package 重排只解决代码可读性与导航问题，不改变业务行为。

核心原则：

- 入口、用例编排、agent loop、LLM、tool runtime、业务工具、轨迹存储分开。
- 同一个 package 内的类应该属于同一层抽象。
- 具体实现依赖抽象，避免核心 loop 依赖具体 provider、具体 Redis Lua 或具体业务 client。
- 测试 package 跟随生产 package，方便按模块定位测试。

## 2. 顶层分层

```text
com.ai.agent
  web                  HTTP / SSE / request DTO / servlet filter
  application          run 生命周期、权限、确认、中断、修复、限流
  loop                 agent loop 控制流、LLM attempt、tool call 协调
  budget               LLM call budget 与 Redis 计数
  llm                  provider、message model、context view、compact、summary
  tool                 Tool contract、tool model、registry、runtime、Redis 调度、内置业务工具
  skill                Anthropic-style skill 扫描、路径解析、slash command、skill tools
  subagent             SubAgent profile、registry、runtime、AgentTool
  todo                 ToDo model、Redis store、tools、reminder injection
  trajectory           trajectory port、MyBatis store、query DTO
  business             订单与用户业务 client / model
  persistence          MyBatis entity / mapper
  config               Spring 配置与 properties
  domain               跨模块状态 enum
  util                 通用小工具
```

## 3. 核心链路导航

HTTP / SSE 入口：

```text
web.controller.AgentController
  -> application.AgentRunApplicationService
  -> loop.DefaultAgentLoop
  -> loop.AgentTurnOrchestrator
```

LLM provider 调用：

```text
loop.LlmAttemptService
  -> llm.provider.LlmProviderAdapterRegistry
  -> llm.provider.deepseek.DeepSeekProviderAdapter
  -> llm.provider.qwen.QwenProviderAdapter
```

Context 组装与压缩：

```text
llm.context.ContextViewBuilder
  -> llm.compact.LargeResultSpiller
  -> llm.compact.MicroCompactor
  -> llm.compact.SummaryCompactor
  -> llm.summary.ProviderSummaryGenerator
  -> llm.transcript.TranscriptPairValidator
```

Tool 调度执行：

```text
loop.ToolCallCoordinator
  -> tool.runtime.RedisToolRuntime
  -> tool.runtime.redis.LuaRedisToolStore
  -> tool.runtime.ToolExecutionLauncher
  -> tool.registry.ToolRegistry
  -> tool.core.Tool
```

内置业务工具：

```text
tool.builtin.order.QueryOrderTool
tool.builtin.order.CancelOrderTool
skill.tool.SkillListTool
skill.tool.SkillViewTool
todo.tool.ToDoCreateTool
todo.tool.ToDoWriteTool
subagent.tool.AgentTool
```

Trajectory：

```text
trajectory.port.TrajectoryStore / TrajectoryReader / TrajectoryWriter
  -> trajectory.store.MybatisTrajectoryStore
  -> trajectory.query.TrajectoryQueryService
  -> trajectory.dto.*
```

## 4. 依赖方向

推荐依赖方向：

```text
web -> application -> loop -> llm/tool/trajectory ports
tool runtime -> tool registry/core/model -> concrete tools
concrete tools -> business clients / skill runtime / subagent runtime / todo runtime
trajectory store -> persistence mapper/entity
```

禁止方向：

- `tool.runtime` 不应依赖具体 `tool.builtin.*`。
- `loop` 不应依赖具体 provider 包，例如 `llm.provider.deepseek`。
- `llm.provider.*` 不应依赖 `web` 或具体 HTTP controller。
- `business` 不应依赖 agent loop、tool runtime 或 SSE。
- `persistence.mapper` 不应依赖 application / loop / tool runtime。

## 5. 维护规则

- 新增 HTTP endpoint 放在 `web.controller`，请求/响应 DTO 放在 `web.dto`。
- 新增 run 生命周期能力优先放在 `application`，不要塞进 controller。
- 新增 provider 放在 `llm.provider.<providerName>`，通过 `llm.provider.LlmProviderAdapter` 接入。
- 新增 context 变换放在 `llm.context` 或 `llm.compact`，必须保持原始 trajectory 不被修改。
- 新增通用 tool runtime 能力放在 `tool.runtime`，Redis 细节放在 `tool.runtime.redis`。
- 新增业务工具放在对应业务子包，例如 `tool.builtin.order`；跨领域工具优先独立成自己的模块包。
- 新增 SubAgent 暴露给 LLM 的工具放在 `subagent.tool`，不要放回 `tool` 根包。
- 测试文件 package 跟随生产 package；测试辅助类放在 `src/test/java/com/ai/agent/support` 或对应模块 test package。
