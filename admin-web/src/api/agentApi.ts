import type { SseEvent, Trajectory } from '../types'

const API_BASE = '/api/agent'

export interface CreateRunRequest {
  userId: string
  prompt: string
  model?: string
  maxTurns?: number
  allowedToolNames?: string[]
}

export interface ContinueRunRequest {
  userId: string
  content: string
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
        userId: config.userId,
        prompt: request.prompt,
        model: request.model ?? 'deepseek-reasoner',
        maxTurns: request.maxTurns ?? 10,
        allowedToolNames: request.allowedToolNames ?? ['query_order', 'cancel_order', 'skill_list', 'skill_view', 'agent_tool', 'todo_create', 'todo_write'],
      }),
    })

    if (!response.ok) {
      throw new Error(`createRun failed: ${response.status}`)
    }

    // Return SSE stream URL (same URL returns SSE)
    return response.url
  }

  async function continueRun(runId: string, content: string): Promise<string> {
    const response = await fetch(`${API_BASE}/runs/${runId}/messages`, {
      method: 'POST',
      headers: headers(),
      body: JSON.stringify({ content }),
    })

    if (!response.ok) {
      throw new Error(`continueRun failed: ${response.status}`)
    }

    return response.url
  }

  async function getTrajectory(runId: string): Promise<Trajectory> {
    const response = await fetch(`${API_BASE}/runs/${runId}`, {
      method: 'GET',
      headers: headers(),
    })

    if (!response.ok) {
      throw new Error(`getTrajectory failed: ${response.status}`)
    }

    return response.json() as Promise<Trajectory>
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

  function parseSseStream(response: Response): AsyncIterable<SseEvent> {
    const reader = response.body?.getReader()
    if (!reader) {
      throw new Error('No response body')
    }
    const safeReader = reader

    const decoder = new TextDecoder()
    let buffer = ''

    async function* generate(): AsyncIterable<SseEvent> {
      while (true) {
        const { done, value } = await safeReader.read()
        if (done) break

        buffer += decoder.decode(value, { stream: true })

        // Process complete events in buffer
        const lines = buffer.split('\n')
        buffer = lines.pop() ?? ''

        for (const line of lines) {
          if (line.startsWith('data:')) {
            const data = line.slice(5).trim()
            if (data === '') continue

            try {
              const event = JSON.parse(data) as SseEvent
              // Normalize toolName field (handle legacy 'name' field)
              if (event.type === 'tool_use' && 'name' in event && !event.toolName) {
                event.toolName = (event as Record<string, unknown>).name as string
              }
              yield event
            } catch {
              // Invalid JSON - skip (ping or malformed)
              if (data === 'ping') {
                yield { type: 'ping' }
              }
            }
          }
        }
      }
    }

    return generate()
  }

  return {
    createRun,
    continueRun,
    getTrajectory,
    abortRun,
    interruptRun,
    parseSseStream,
    headers,
  }
}