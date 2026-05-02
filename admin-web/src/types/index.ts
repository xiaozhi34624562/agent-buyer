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
  status?: string
  nextActionRequired?: string
  error?: string
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