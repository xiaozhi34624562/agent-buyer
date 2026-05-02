// Run summary for list display
export interface RunSummary {
  runId: string
  userId: string
  status: string
  turnNo: number
  agentType: string
  parentRunId: string | null
  parentLinkStatus: string | null
  primaryProvider: string
  fallbackProvider: string
  model: string
  maxTurns: number
  startedAt: string
  updatedAt: string
  completedAt: string | null
  lastError: string | null
}

// Runtime state debug view
export interface RuntimeState {
  runId: string
  activeRun: boolean
  entries: Record<string, unknown>
}

// Tool card for chat display
export interface ToolCard {
  toolCallId: string
  toolName: string
  args: Record<string, unknown>
  status: 'pending' | 'running' | 'completed' | 'error'
  progress?: number
  progressMessage?: string
  result?: string
  resultStatus?: string
}

// Trajectory timeline node types
export type TrajectoryNodeType =
  | 'MESSAGE'
  | 'LLM_ATTEMPT'
  | 'TOOL_CALL'
  | 'TOOL_PROGRESS'
  | 'TOOL_RESULT'
  | 'EVENT'
  | 'COMPACTION'

export interface TrajectoryNode {
  nodeId: string
  nodeType: TrajectoryNodeType
  timestamp: string | null
  parentId: string | null
  attemptId: string | null
  toolCallId: string | null
  role?: 'user' | 'assistant' | 'system'
  content?: string
  provider?: string
  model?: string
  inputTokens?: number
  outputTokens?: number
  toolName?: string
  args?: Record<string, unknown>
  result?: string
  percent?: number
  message?: string
  eventType?: string
  eventData?: Record<string, unknown>
  synthetic?: boolean
  cancelReason?: string
  strategy?: string
  beforeTokens?: number
  afterTokens?: number
  compactedMessageIds?: string[]
}

export interface Trajectory {
  runId: string
  nodes: TrajectoryNode[]
}

// SSE event types
export interface SseEvent {
  type: string
  runId?: string
  content?: string
  toolName?: string
  toolCallId?: string
  args?: Record<string, unknown>
  result?: string
  percent?: number
  message?: string
  status?: string
  nextActionRequired?: string
  error?: string
  confirmToken?: string // Will be redacted before processing
}

export interface SseTextDeltaEvent extends SseEvent {
  type: 'text_delta'
  content: string
}

export interface SseToolUseEvent extends SseEvent {
  type: 'tool_use'
  toolName: string
  toolCallId: string
  args: Record<string, unknown>
}

export interface SseToolProgressEvent extends SseEvent {
  type: 'tool_progress'
  toolCallId: string
  percent: number
  message?: string
}

export interface SseToolResultEvent extends SseEvent {
  type: 'tool_result'
  toolCallId: string
  toolName: string
  result: string
  status: string
  confirmToken?: string // Will be redacted
}

export interface SseFinalEvent extends SseEvent {
  type: 'final'
  runId: string
  status: string
  nextActionRequired?: string
}

export interface SseErrorEvent extends SseEvent {
  type: 'error'
  error: string
}

export interface SsePingEvent extends SseEvent {
  type: 'ping'
}