import { readSseStream } from './sseParser'

const API_BASE = '/api/agent'

// Default configuration for agent runs
export const DEFAULT_ALLOWED_TOOL_NAMES = [
  'query_order', 'cancel_order', 'skill_list', 'skill_view',
  'agent_tool', 'todo_create', 'todo_write'
] as const

export const DEFAULT_LLM_PARAMS: LlmParams = {
  model: 'deepseek-reasoner',
  temperature: 0.2,
  maxTokens: 4096,
  maxTurns: 10,
}

export interface UserMessage {
  role: 'user' | 'assistant' | 'system'
  content: string
}

export interface LlmParams {
  model?: string
  temperature?: number
  maxTokens?: number
  maxTurns?: number
}

export interface CreateRunRequest {
  userId: string
  messages: UserMessage[]
  allowedToolNames?: string[]
  llmParams?: LlmParams
}

export interface ContinueRunRequest {
  userId: string
  message: UserMessage
}

export interface AgentApiConfig {
  userId: string
}

export function createAgentApi(config: AgentApiConfig) {
  const headers = () => ({
    'Content-Type': 'application/json',
    'X-User-Id': config.userId,
  })

  async function createRun(request: Omit<CreateRunRequest, 'userId'>): Promise<string> {
    const response = await fetch(`${API_BASE}/runs`, {
      method: 'POST',
      headers: headers(),
      body: JSON.stringify({
        messages: request.messages,
        allowedToolNames: request.allowedToolNames ?? DEFAULT_ALLOWED_TOOL_NAMES,
        llmParams: {
          model: request.llmParams?.model ?? DEFAULT_LLM_PARAMS.model,
          temperature: request.llmParams?.temperature ?? DEFAULT_LLM_PARAMS.temperature,
          maxTokens: request.llmParams?.maxTokens ?? DEFAULT_LLM_PARAMS.maxTokens,
          maxTurns: request.llmParams?.maxTurns ?? DEFAULT_LLM_PARAMS.maxTurns,
        },
      }),
    })

    if (!response.ok) {
      throw new Error(`createRun failed: ${response.status}`)
    }

    // Return SSE stream URL (same URL returns SSE)
    return response.url
  }

  async function continueRun(runId: string, message: UserMessage): Promise<string> {
    const response = await fetch(`${API_BASE}/runs/${runId}/messages`, {
      method: 'POST',
      headers: headers(),
      body: JSON.stringify({ message }),
    })

    if (!response.ok) {
      throw new Error(`continueRun failed: ${response.status}`)
    }

    return response.url
  }

  async function getTrajectory(runId: string): Promise<unknown> {
    const response = await fetch(`${API_BASE}/runs/${runId}`, {
      method: 'GET',
      headers: headers(),
    })

    if (!response.ok) {
      throw new Error(`getTrajectory failed: ${response.status}`)
    }

    return response.json()
  }

  async function abortRun(runId: string): Promise<void> {
    const response = await fetch(`${API_BASE}/runs/${runId}/abort`, {
      method: 'POST',
      headers: headers(),
    })

    if (!response.ok) {
      throw new Error(`abortRun failed: ${response.status}`)
    }
  }

  async function interruptRun(runId: string): Promise<void> {
    const response = await fetch(`${API_BASE}/runs/${runId}/interrupt`, {
      method: 'POST',
      headers: headers(),
    })

    if (!response.ok) {
      throw new Error(`interruptRun failed: ${response.status}`)
    }
  }

  return {
    createRun,
    continueRun,
    getTrajectory,
    abortRun,
    interruptRun,
    readSseStream,
    headers,
  }
}