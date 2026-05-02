import { useState, useCallback } from 'react'
import type { SseEvent, ToolCard } from '../types'
import { readSseStream } from '../api/sseParser'

export interface ChatMessage {
  role: 'user' | 'assistant'
  content: string
  toolCards?: ToolCard[]
}

export interface UseChatMessagesOptions {
  userId: string
  onEvent?: (event: SseEvent) => void
}

export interface UseChatMessagesResult {
  messages: ChatMessage[]
  sendMessage: (runId: string, content: string) => Promise<void>
  sendConfirmation: (runId: string, confirmed: boolean) => Promise<void>
  addAssistantMessage: (content: string, toolCards?: ChatMessage['toolCards']) => void
  clearMessages: () => void
}

export function useChatMessages(options: UseChatMessagesOptions): UseChatMessagesResult {
  const { userId, onEvent } = options
  const [messages, setMessages] = useState<ChatMessage[]>([])

  const addAssistantMessage = useCallback((content: string, toolCards?: ChatMessage['toolCards']) => {
    setMessages(prev => [...prev, { role: 'assistant', content, toolCards }])
  }, [])

  const clearMessages = useCallback(() => {
    setMessages([])
  }, [])

  const sendMessage = useCallback(async (runId: string, content: string) => {
    // Add user message to history
    setMessages(prev => [...prev, { role: 'user', content }])

    try {
      const response = await fetch(`/api/agent/runs/${runId}/messages`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-User-Id': userId,
        },
        body: JSON.stringify({
          message: {
            role: 'user',
            content,
          },
        }),
      })

      if (!response.ok) {
        throw new Error(`sendMessage failed: ${response.status}`)
      }

      // Use unified SSE stream reader
      for await (const event of readSseStream(response)) {
        onEvent?.(event)
      }
    } catch (error) {
      onEvent?.({ type: 'error', error: (error as Error).message })
    }
  }, [userId, onEvent])

  const sendConfirmation = useCallback(async (runId: string, confirmed: boolean) => {
    const content = confirmed ? '确认继续执行' : '放弃本次操作'
    await sendMessage(runId, content)
  }, [sendMessage])

  return {
    messages,
    sendMessage,
    sendConfirmation,
    addAssistantMessage,
    clearMessages,
  }
}