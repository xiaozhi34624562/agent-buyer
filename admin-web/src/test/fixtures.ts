import type { RunSummary, TrajectoryNode, RuntimeState, SseEvent } from '../types'

// Run summary fixtures
export const fixtureRunSummaries: RunSummary[] = [
  {
    runId: 'run-console-demo-001',
    userId: 'demo-user',
    status: 'RUNNING',
    turnNo: 2,
    agentType: 'MainAgent',
    parentRunId: null,
    parentLinkStatus: null,
    primaryProvider: 'deepseek',
    fallbackProvider: 'qwen',
    model: 'deepseek-reasoner',
    maxTurns: 10,
    startedAt: '2025-05-02T10:00:00Z',
    updatedAt: '2025-05-02T10:05:00Z',
    completedAt: null,
    lastError: null,
  },
  {
    runId: 'run-console-demo-002',
    userId: 'demo-user',
    status: 'WAITING_USER_CONFIRMATION',
    turnNo: 3,
    agentType: 'MainAgent',
    parentRunId: null,
    parentLinkStatus: null,
    primaryProvider: 'deepseek',
    fallbackProvider: 'qwen',
    model: 'deepseek-reasoner',
    maxTurns: 10,
    startedAt: '2025-05-02T09:00:00Z',
    updatedAt: '2025-05-02T09:30:00Z',
    completedAt: null,
    lastError: null,
  },
  {
    runId: 'run-console-demo-003',
    userId: 'demo-user',
    status: 'SUCCEEDED',
    turnNo: 5,
    agentType: 'MainAgent',
    parentRunId: null,
    parentLinkStatus: null,
    primaryProvider: 'deepseek',
    fallbackProvider: 'qwen',
    model: 'deepseek-reasoner',
    maxTurns: 10,
    startedAt: '2025-05-02T08:00:00Z',
    updatedAt: '2025-05-02T08:45:00Z',
    completedAt: '2025-05-02T08:45:00Z',
    lastError: null,
  },
]

// Trajectory node fixtures
export const fixtureTrajectoryNodes: TrajectoryNode[] = [
  {
    nodeId: 'msg-user-001',
    nodeType: 'MESSAGE',
    timestamp: '2025-05-02T10:00:00Z',
    parentId: null,
    attemptId: null,
    toolCallId: null,
    role: 'user',
    content: '取消我昨天的订单',
  },
  {
    nodeId: 'att-001',
    nodeType: 'LLM_ATTEMPT',
    timestamp: '2025-05-02T10:00:05Z',
    parentId: null,
    attemptId: 'att-001',
    toolCallId: null,
    provider: 'deepseek',
    model: 'deepseek-reasoner',
    inputTokens: 500,
    outputTokens: 100,
  },
  {
    nodeId: 'tc-001',
    nodeType: 'TOOL_CALL',
    timestamp: '2025-05-02T10:00:10Z',
    parentId: null,
    attemptId: 'att-001',
    toolCallId: 'tc-001',
    toolName: 'query_order',
    args: { orderId: 'ORD-123' },
  },
  {
    nodeId: 'tp-001',
    nodeType: 'TOOL_PROGRESS',
    timestamp: '2025-05-02T10:00:15Z',
    parentId: null,
    attemptId: null,
    toolCallId: 'tc-001',
    percent: 50,
    message: '查询中',
  },
  {
    nodeId: 'tr-001',
    nodeType: 'TOOL_RESULT',
    timestamp: '2025-05-02T10:00:20Z',
    parentId: null,
    attemptId: null,
    toolCallId: 'tc-001',
    toolName: 'query_order',
    result: '{"orderId":"ORD-123","status":"PAID","amount":199.99}',
  },
  {
    nodeId: 'evt-001',
    nodeType: 'EVENT',
    timestamp: '2025-05-02T10:00:25Z',
    parentId: null,
    attemptId: null,
    toolCallId: null,
    eventType: 'WAITING_USER_CONFIRMATION',
    eventData: { toolName: 'cancel_order', orderId: 'ORD-123' },
  },
  {
    nodeId: 'comp-001',
    nodeType: 'COMPACTION',
    timestamp: '2025-05-02T10:01:00Z',
    parentId: null,
    attemptId: null,
    toolCallId: null,
    strategy: 'SUMMARY_COMPACT',
    beforeTokens: 50000,
    afterTokens: 5000,
    compactedMessageIds: ['msg-001', 'msg-002', 'msg-003'],
  },
]

// Runtime state fixture
export const fixtureRuntimeState: RuntimeState = {
  runId: 'run-console-demo-001',
  activeRun: true,
  entries: {
    'agent:run:run-console-demo-001:meta': { status: 'RUNNING', userId: 'demo-user' },
    'agent:run:run-console-demo-001:queue': ['tc-002'],
    'agent:run:run-console-demo-001:tools:tc-001': { status: 'WAITING', toolName: 'query_order' },
    'agent:run:run-console-demo-001:lease:tc-001': { leaseHolder: 'instance-1', expireAt: 1234567890 },
    'agent:run:run-console-demo-001:control': { abort_requested: false, interrupt_requested: false },
    'agent:run:run-console-demo-001:todos': [],
    // Malicious field with confirmToken - should be redacted
    'confirmToken': 'secret-confirm-abc-def-123',
  },
}

// SSE events fixture for confirmation flow
export const fixtureSseEvents: SseEvent[] = [
  { type: 'text_delta', content: '好的，我来帮你查询订单信息。' },
  {
    type: 'tool_use',
    toolCallId: 'tc-001',
    toolName: 'query_order',
    args: { orderId: 'ORD-123' },
  },
  {
    type: 'tool_progress',
    toolCallId: 'tc-001',
    percent: 50,
    message: '查询中',
  },
  {
    type: 'tool_result',
    toolCallId: 'tc-001',
    toolName: 'query_order',
    result: '{"orderId":"ORD-123","status":"PAID"}',
    status: 'SUCCESS',
  },
  {
    type: 'final',
    runId: 'run-console-demo-002',
    status: 'WAITING_USER_CONFIRMATION',
    nextActionRequired: 'user_confirmation',
  },
]

// SSE events for PAUSED with user_input
export const fixturePausedSseEvents: SseEvent[] = [
  { type: 'text_delta', content: '请补充订单号信息...' },
  {
    type: 'tool_result',
    toolCallId: 'tc-001',
    toolName: 'query_order',
    result: '{"error":"orderId required"}',
    status: 'PRECHECK_FAILED',
  },
  {
    type: 'final',
    runId: 'run-console-demo-paused',
    status: 'PAUSED',
    nextActionRequired: 'user_input',
  },
]

// SSE events with malicious confirmToken
export const fixtureMaliciousSseEvents: SseEvent[] = [
  {
    type: 'tool_result',
    toolCallId: 'tc-001',
    toolName: 'cancel_order',
    result: '{"status":"PENDING_CONFIRM","confirmToken":"sk-secret-token-abc123"}',
    status: 'PENDING_CONFIRM',
    confirmToken: 'sk-secret-token-abc123',
  } as unknown as SseEvent,
]

// Trajectory fixture with all node types
export const fixtureFullTrajectory = {
  runId: 'run-console-demo-001',
  nodes: fixtureTrajectoryNodes,
}

// Inactive runtime state
export const fixtureInactiveRuntimeState: RuntimeState = {
  runId: 'run-console-demo-003',
  activeRun: false,
  entries: {},
}