import { useState, useCallback, useRef } from 'react'
import type { SseEvent } from '../types'

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

export interface ChatStreamState {
  runId: string | null
  assistantDraft: string
  toolCards: ToolCard[]
  runStatus: string | null
  nextActionRequired: string | null
  error: string | null
  isStreaming: boolean
  debugEvents: SseEvent[]
}

export interface UseChatStreamResult {
  state: ChatStreamState
  processEvent: (event: SseEvent) => void
  startStream: (runId: string) => void
  stopStream: () => void
  resetState: () => void
}

const initialState: ChatStreamState = {
  runId: null,
  assistantDraft: '',
  toolCards: [],
  runStatus: null,
  nextActionRequired: null,
  error: null,
  isStreaming: false,
  debugEvents: [],
}

export function useChatStream(): UseChatStreamResult {
  const [state, setState] = useState<ChatStreamState>(initialState)
  const toolCardsRef = useRef<Map<string, ToolCard>>(new Map())

  const startStream = useCallback((runId: string) => {
    toolCardsRef.current.clear()
    setState({
      ...initialState,
      runId,
      isStreaming: true,
    })
  }, [])

  const stopStream = useCallback(() => {
    setState(prev => ({
      ...prev,
      isStreaming: false,
    }))
  }, [])

  const resetState = useCallback(() => {
    toolCardsRef.current.clear()
    setState(initialState)
  }, [])

  const processEvent = useCallback((event: SseEvent) => {
    // Don't save raw confirmToken in state
    if (event.type !== 'ping' && 'confirmToken' in event) {
      // Redact confirmToken before processing (for safety)
      delete event.confirmToken
    }

    switch (event.type) {
      case 'text_delta':
        setState(prev => ({
          ...prev,
          assistantDraft: prev.assistantDraft + (event.content || ''),
        }))
        break

      case 'tool_use':
        const newToolCard: ToolCard = {
          toolCallId: event.toolCallId || '',
          toolName: event.toolName || '',
          args: event.args || {},
          status: 'pending',
        }
        toolCardsRef.current.set(newToolCard.toolCallId, newToolCard)
        setState(prev => ({
          ...prev,
          toolCards: Array.from(toolCardsRef.current.values()),
        }))
        break

      case 'tool_progress':
        const progressCard = toolCardsRef.current.get(event.toolCallId || '')
        if (progressCard) {
          progressCard.status = 'running'
          progressCard.progress = event.percent
          progressCard.progressMessage = event.message
          setState(prev => ({
            ...prev,
            toolCards: Array.from(toolCardsRef.current.values()),
          }))
        }
        break

      case 'tool_result':
        const resultCard = toolCardsRef.current.get(event.toolCallId || '')
        if (resultCard) {
          resultCard.status = 'completed'
          resultCard.result = event.result
          resultCard.resultStatus = event.status
          setState(prev => ({
            ...prev,
            toolCards: Array.from(toolCardsRef.current.values()),
          }))
        }
        break

      case 'final':
        setState(prev => ({
          ...prev,
          runStatus: event.status || 'SUCCEEDED',
          nextActionRequired: event.nextActionRequired || null,
          isStreaming: false,
        }))
        break

      case 'error':
        setState(prev => ({
          ...prev,
          error: event.error || 'Unknown error',
          isStreaming: false,
        }))
        break

      case 'ping':
        // Ping only goes to debug events
        setState(prev => ({
          ...prev,
          debugEvents: [...prev.debugEvents, event],
        }))
        break

      default:
        // Unknown event type - add to debug
        setState(prev => ({
          ...prev,
          debugEvents: [...prev.debugEvents, event],
        }))
    }
  }, [])

  return {
    state,
    processEvent,
    startStream,
    stopStream,
    resetState,
  }
}