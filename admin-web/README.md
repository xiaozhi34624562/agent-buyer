# Agent Buyer Console

Agent Buyer Console 是一个 React 前端应用，用于观察和操作 Agent Buyer 的 run 执行过程。

## 功能

- **Run List**: 分页浏览所有 run，支持按状态和 userId 过滤
- **Timeline**: 查看完整 trajectory 节点（MESSAGE、LLM_ATTEMPT、TOOL_CALL、TOOL_PROGRESS、TOOL_RESULT、EVENT、COMPACTION）
- **Runtime State**: Debug Drawer 显示 Redis runtime state（meta、queue、tools、leases、control、children、todos）
- **Chat**: 创建新 run、发送消息、处理 HITL 确认、PAUSED 输入
- **Run Controls**: New Chat、Refresh、Interrupt、Abort

## 启动

### 1. 启动后端

```bash
cd ../
export MYSQL_PASSWORD='Qaz1234!'
export DEEPSEEK_API_KEY='<your deepseek api key>'
export DASHSCOPE_API_KEY='<your qwen api key>'
mvn spring-boot:run
```

或使用启动脚本：

```bash
../scripts/start-console-dev.sh
```

### 2. 启动前端

```bash
npm run dev
```

### 3. 访问

浏览器打开 http://localhost:5173

## 配置

### User ID

页面顶部 Toolbar 显示当前 userId。点击 Settings 可以修改：
- **User ID**: 用于 `/api/agent/*` 请求的 `X-User-Id` header
- **Admin Token**: 用于 `/api/admin/*` 请求的 `X-Admin-Token` header（本地开发可留空）

修改后自动保存到 localStorage，刷新页面保留。

### Admin Token / Local Profile

- **local profile**: 后端配置 `agent.admin.enabled=true` 且 `agent.admin.token` 非空时，非 local profile 需要正确的 admin token
- **local profile**: 后端 `application-local.yml` 中 `agent.admin.enabled=true` 且 token 为空时，本地开发无需 admin token

## 推荐 Prompt 示例

```
取消我昨天的订单
```

```
查询我最近三天的订单状态
```

```
帮我处理退货申请，订单号是 ORD-12345
```

## 安全边界

Console **不做**：
- 不做通用 MySQL table browser
- 不做任意 Redis key browser
- 不新增 `/api/admin/chat`
- 不实现生产级多租户 admin RBAC
- 不暴露原始 `confirmToken`、provider key、admin token

敏感字段在 Timeline、Runtime State、Chat transcript 中自动脱敏显示为 `[REDACTED]`。

## 开发

```bash
# 安装依赖
npm install

# 运行测试
npm test

# 构建
npm run build

# 类型检查
tsc -b
```

## 技术栈

- React 18 + TypeScript
- Vite
- Tailwind CSS
- lucide-react icons
- Vitest + @testing-library/react