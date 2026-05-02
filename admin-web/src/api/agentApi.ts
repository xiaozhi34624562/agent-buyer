import { readSseStream } from './sseParser'

const API_BASE = '/api/agent'

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
        allowedToolNames: request.allowedToolNames ?? ['query_order', 'cancel_order', 'skill_list', 'skill_view', 'agent_tool', 'todo_create', 'todo_write'],
        llmParams: {
          model: request.llmParams?.model ?? 'deepseek-reasoner',
          temperature: request.llmParams?.temperature ?? 0.2,
          maxTokens: request.llmParams?.maxTokens ?? 4096,
          maxTurns: request.llmParams?.maxTurns ?? 10,
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