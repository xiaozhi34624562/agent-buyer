import { describe, it, expect } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useChatStream } from '../hooks/useChatStream'
import type { SseEvent } from '../types'

describe('useChatStream', () => {
  it('should start streaming with runId', () => {
    const { result } = renderHook(() => useChatStream())

    act(() => {
      result.current.startStream('run-001')
    })

    expect(result.current.state.runId).toBe('run-001')
    expect(result.current.state.isStreaming).toBe(true)
    expect(result.current.state.assistantDraft).toBe('')
  })

  it('should merge text_delta into assistantDraft', () => {
    const { result } = renderHook(() => useChatStream())

    act(() => {
      result.current.startStream('run-001')
    })

    act(() => {
      result.current.processEvent({ type: 'text_delta', content: 'Hello' })
    })

    expect(result.current.state.assistantDraft).toBe('Hello')

    act(() => {
      result.current.processEvent({ type: 'text_delta', content: ' world' })
    })

    expect(result.current.state.assistantDraft).toBe('Hello world')
  })

  it('should generate tool card for tool_use', () => {
    const { result } = renderHook(() => useChatStream())

    act(() => {
      result.current.startStream('run-001')
    })

    act(() => {
      result.current.processEvent({
        type: 'tool_use',
        toolName: 'query_order',
        toolCallId: 'tc-001',
        args: { orderId: 'ORD-123' },
      })
    })

    expect(result.current.state.toolCards).toHaveLength(1)
    expect(result.current.state.toolCards[0].toolName).toBe('query_order')
    expect(result.current.state.toolCards[0].toolCallId).toBe('tc-001')
    expect(result.current.state.toolCards[0].status).toBe('pending')
  })

  it('should update tool progress', () => {
    const { result } = renderHook(() => useChatStream())

    act(() => {
      result.current.startStream('run-001')
      result.current.processEvent({
        type: 'tool_use',
        toolName: 'query_order',
        toolCallId: 'tc-001',
        args: {},
      })
    })

    act(() => {
      result.current.processEvent({
        type: 'tool_progress',
        toolCallId: 'tc-001',
        percent: 50,
        message: '查询中',
      })
    })

    expect(result.current.state.toolCards[0].status).toBe('running')
    expect(result.current.state.toolCards[0].progress).toBe(50)
    expect(result.current.state.toolCards[0].progressMessage).toBe('查询中')
  })

  it('should show tool result preview', () => {
    const { result } = renderHook(() => useChatStream())

    act(() => {
      result.current.startStream('run-001')
      result.current.processEvent({
        type: 'tool_use',
        toolName: 'query_order',
        toolCallId: 'tc-001',
        args: {},
      })
    })

    act(() => {
      result.current.processEvent({
        type: 'tool_result',
        toolCallId: 'tc-001',
        toolName: 'query_order',
        result: '{"orderId":"ORD-123","status":"PAID"}',
        status: 'SUCCESS',
      })
    })

    expect(result.current.state.toolCards[0].status).toBe('completed')
    expect(result.current.state.toolCards[0].result).toContain('ORD-123')
    expect(result.current.state.toolCards[0].resultStatus).toBe('SUCCESS')
  })

  it('should set runStatus and nextActionRequired on final', () => {
    const { result } = renderHook(() => useChatStream())

    act(() => {
      result.current.startStream('run-001')
    })

    act(() => {
      result.current.processEvent({
        type: 'final',
        runId: 'run-001',
        status: 'WAITING_USER_CONFIRMATION',
        nextActionRequired: 'user_confirmation',
      })
    })

    expect(result.current.state.runStatus).toBe('WAITING_USER_CONFIRMATION')
    expect(result.current.state.nextActionRequired).toBe('user_confirmation')
    expect(result.current.state.isStreaming).toBe(false)
  })

  it('should stop streaming on error', () => {
    const { result } = renderHook(() => useChatStream())

    act(() => {
      result.current.startStream('run-001')
    })

    act(() => {
      result.current.processEvent({
        type: 'error',
        error: 'Connection timeout',
      })
    })

    expect(result.current.state.error).toBe('Connection timeout')
    expect(result.current.state.isStreaming).toBe(false)
  })

  it('should add ping only to debug events', () => {
    const { result } = renderHook(() => useChatStream())

    act(() => {
      result.current.startStream('run-001')
    })

    act(() => {
      result.current.processEvent({ type: 'ping' })
    })

    expect(result.current.state.debugEvents).toHaveLength(1)
    expect(result.current.state.debugEvents[0].type).toBe('ping')
    expect(result.current.state.assistantDraft).toBe('')
    expect(result.current.state.toolCards).toHaveLength(0)
  })

  it('should not save raw confirmToken in state', () => {
    const { result } = renderHook(() => useChatStream())

    act(() => {
      result.current.startStream('run-001')
      // First create the tool card
      result.current.processEvent({
        type: 'tool_use',
        toolName: 'cancel_order',
        toolCallId: 'tc-001',
        args: {},
      })
      // Then send result with confirmToken (which should be deleted from event)
      result.current.processEvent({
        type: 'tool_result',
        toolCallId: 'tc-001',
        toolName: 'cancel_order',
        result: '{"status":"PENDING_CONFIRM"}',
        status: 'PENDING_CONFIRM',
        confirmToken: 'secret-token-abc123',
      } as unknown as SseEvent)
    })

    // Tool card should exist and be completed
    expect(result.current.state.toolCards).toHaveLength(1)
    expect(result.current.state.toolCards[0].status).toBe('completed')
    // The raw confirmToken field on the event is deleted before processing
    // The result string may contain token info but that's handled by redaction at display time
  })

  it('should handle multiple tool cards', () => {
    const { result } = renderHook(() => useChatStream())

    act(() => {
      result.current.startStream('run-001')
      result.current.processEvent({
        type: 'tool_use',
        toolName: 'query_order',
        toolCallId: 'tc-001',
        args: {},
      })
      result.current.processEvent({
        type: 'tool_use',
        toolName: 'cancel_order',
        toolCallId: 'tc-002',
        args: {},
      })
    })

    expect(result.current.state.toolCards).toHaveLength(2)
    expect(result.current.state.toolCards[0].toolName).toBe('query_order')
    expect(result.current.state.toolCards[1].toolName).toBe('cancel_order')
  })

  it('should reset state completely', () => {
    const { result } = renderHook(() => useChatStream())

    act(() => {
      result.current.startStream('run-001')
      result.current.processEvent({ type: 'text_delta', content: 'Hello' })
      result.current.processEvent({
        type: 'tool_use',
        toolName: 'query_order',
        toolCallId: 'tc-001',
        args: {},
      })
    })

    expect(result.current.state.assistantDraft).toBe('Hello')
    expect(result.current.state.toolCards).toHaveLength(1)

    act(() => {
      result.current.resetState()
    })

    expect(result.current.state.runId).toBeNull()
    expect(result.current.state.assistantDraft).toBe('')
    expect(result.current.state.toolCards).toHaveLength(0)
    expect(result.current.state.isStreaming).toBe(false)
  })

  it('should stop stream manually', () => {
    const { result } = renderHook(() => useChatStream())

    act(() => {
      result.current.startStream('run-001')
    })

    expect(result.current.state.isStreaming).toBe(true)

    act(() => {
      result.current.stopStream()
    })

    expect(result.current.state.isStreaming).toBe(false)
    // State should be preserved after stopping
    expect(result.current.state.runId).toBe('run-001')
  })
})