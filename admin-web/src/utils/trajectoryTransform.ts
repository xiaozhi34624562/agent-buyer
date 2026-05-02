import type { TrajectoryNode } from '../types'

// Backend DTO types
interface BackendRunDto {
  runId: string
  status: string
  turnNo: number
  parentRunId: string | null
  parentToolCallId: string | null
  agentType: string
  parentLinkStatus: string | null
  startedAt: string
  updatedAt: string
  completedAt: string | null
  lastErrorPreview: string | null
}

interface BackendMessageDto {
  messageId: string
  seq: number
  role: string
  contentPreview: string
  toolUseId: string | null
  toolCalls: Array<{ toolUseId: string; toolName: string }>
  createdAt: string
}

interface BackendLlmAttemptDto {
  attemptId: string
  turnNo: number
  provider: string
  model: string
  status: string
  finishReason: string
  promptTokens: number
  completionTokens: number
  totalTokens: number
  errorPreview: string | null
  startedAt: string
  completedAt: string
}

interface BackendToolCallDto {
  toolCallId: string
  messageId: string
  seq: number
  toolUseId: string
  toolName: string
  concurrent: boolean
  idempotent: boolean
  precheckFailed: boolean
  precheckErrorPreview: string | null
  createdAt: string
}

interface BackendToolResultDto {
  resultId: string
  toolCallId: string
  toolUseId: string
  status: string
  synthetic: boolean
  cancelReason: string | null
  preview: string
  createdAt: string
}

interface BackendEventDto {
  eventId: string
  eventType: string
  payloadPreview: string
  createdAt: string
}

interface BackendToolProgressDto {
  progressId: string
  toolCallId: string
  stage: string
  messagePreview: string
  percent: number
  createdAt: string
}

interface BackendCompactionDto {
  compactionId: string
  turnNo: number
  attemptId: string
  strategy: string
  beforeTokens: number
  afterTokens: number
  compactedMessageIds: string[]
  createdAt: string
}

interface BackendTrajectoryDto {
  run: BackendRunDto
  messages: BackendMessageDto[]
  llmAttempts: BackendLlmAttemptDto[]
  toolCalls: BackendToolCallDto[]
  toolResults: BackendToolResultDto[]
  events: BackendEventDto[]
  toolProgress: BackendToolProgressDto[]
  compactions: BackendCompactionDto[]
}

/**
 * Transform backend trajectory DTO to frontend TrajectoryNode format.
 */
export function transformTrajectory(dto: BackendTrajectoryDto): TrajectoryNode[] {
  const nodes: TrajectoryNode[] = []

  // Add messages
  for (const msg of dto.messages) {
    const role = msg.role.toLowerCase() as 'user' | 'assistant' | 'system'
    nodes.push({
      nodeId: msg.messageId,
      nodeType: 'MESSAGE',
      timestamp: msg.createdAt,
      parentId: null,
      attemptId: null,
      toolCallId: msg.toolUseId || null,
      role,
      content: msg.contentPreview,
    })
  }

  // Add LLM attempts
  for (const attempt of dto.llmAttempts) {
    nodes.push({
      nodeId: attempt.attemptId,
      nodeType: 'LLM_ATTEMPT',
      timestamp: attempt.startedAt,
      parentId: null,
      attemptId: attempt.attemptId,
      toolCallId: null,
      provider: attempt.provider,
      model: attempt.model,
      inputTokens: attempt.promptTokens,
      outputTokens: attempt.completionTokens,
    })
  }

  // Add tool calls
  for (const tc of dto.toolCalls) {
    nodes.push({
      nodeId: tc.toolCallId,
      nodeType: 'TOOL_CALL',
      timestamp: tc.createdAt,
      parentId: null,
      attemptId: null,
      toolCallId: tc.toolCallId,
      toolName: tc.toolName,
      args: {},
    })
  }

  // Add tool progress
  for (const tp of dto.toolProgress) {
    nodes.push({
      nodeId: tp.progressId,
      nodeType: 'TOOL_PROGRESS',
      timestamp: tp.createdAt,
      parentId: null,
      attemptId: null,
      toolCallId: tp.toolCallId,
      percent: tp.percent,
      message: tp.messagePreview,
    })
  }

  // Add tool results
  for (const tr of dto.toolResults) {
    nodes.push({
      nodeId: tr.resultId,
      nodeType: 'TOOL_RESULT',
      timestamp: tr.createdAt,
      parentId: null,
      attemptId: null,
      toolCallId: tr.toolCallId,
      toolName: '', // Not in DTO, will be resolved from toolCalls
      result: tr.preview,
    })
  }

  // Add events
  for (const evt of dto.events) {
    let eventData: Record<string, unknown> = {}
    try {
      eventData = JSON.parse(evt.payloadPreview)
    } catch {
      eventData = { raw: evt.payloadPreview }
    }
    nodes.push({
      nodeId: evt.eventId,
      nodeType: 'EVENT',
      timestamp: evt.createdAt,
      parentId: null,
      attemptId: null,
      toolCallId: null,
      eventType: evt.eventType,
      eventData,
    })
  }

  // Add compactions
  for (const comp of dto.compactions) {
    nodes.push({
      nodeId: comp.compactionId,
      nodeType: 'COMPACTION',
      timestamp: comp.createdAt,
      parentId: null,
      attemptId: comp.attemptId,
      toolCallId: null,
      strategy: comp.strategy,
      beforeTokens: comp.beforeTokens,
      afterTokens: comp.afterTokens,
      compactedMessageIds: comp.compactedMessageIds,
    })
  }

  // Sort by timestamp
  nodes.sort((a, b) => {
    if (!a.timestamp && !b.timestamp) return 0
    if (!a.timestamp) return 1
    if (!b.timestamp) return -1
    return new Date(a.timestamp).getTime() - new Date(b.timestamp).getTime()
  })

  return nodes
}