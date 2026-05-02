import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { useChatMessages } from '../hooks/useChatMessages'

// Mock fetch
const mockFetch = vi.fn()
vi.stubGlobal('fetch', mockFetch)

describe('useChatMessages', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('should initialize with empty messages', () => {
    const { result } = renderHook(() => useChatMessages({ userId: 'demo-user' }))
    expect(result.current.messages).toEqual([])
  })

  it('should add user message when sending', async () => {
    mockFetch.mockResolvedValueOnce({
      ok: true,
      body: {
        getReader: () => ({
          read: vi.fn().mockResolvedValue({ done: true, value: undefined }),
        }),
      },
    })

    const { result } = renderHook(() => useChatMessages({ userId: 'demo-user' }))

    await act(async () => {
      await result.current.sendMessage('run-001', '取消订单')
    })

    expect(result.current.messages).toHaveLength(1)
    expect(result.current.messages[0]).toEqual({
      role: 'user',
      content: '取消订单',
    })
  })

  it('should call fetch with correct endpoint and headers', async () => {
    mockFetch.mockResolvedValueOnce({
      ok: true,
      body: {
        getReader: () => ({
          read: vi.fn().mockResolvedValue({ done: true, value: undefined }),
        }),
      },
    })

    const { result } = renderHook(() => useChatMessages({ userId: 'test-user' }))

    await act(async () => {
      await result.current.sendMessage('run-123', '查询订单')
    })

    expect(mockFetch).toHaveBeenCalledWith(
      '/api/agent/runs/run-123/messages',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({
          'Content-Type': 'application/json',
          'X-User-Id': 'test-user',
        }),
      })
    )
  })

  it('should send confirmation message with correct content', async () => {
    mockFetch.mockResolvedValueOnce({
      ok: true,
      body: {
        getReader: () => ({
          read: vi.fn().mockResolvedValue({ done: true, value: undefined }),
        }),
      },
    })

    const { result } = renderHook(() => useChatMessages({ userId: 'demo-user' }))

    await act(async () => {
      await result.current.sendConfirmation('run-001', true)
    })

    expect(result.current.messages[0].content).toBe('确认继续执行')
  })

  it('should send rejection message with correct content', async () => {
    mockFetch.mockResolvedValueOnce({
      ok: true,
      body: {
        getReader: () => ({
          read: vi.fn().mockResolvedValue({ done: true, value: undefined }),
        }),
      },
    })

    const { result } = renderHook(() => useChatMessages({ userId: 'demo-user' }))

    await act(async () => {
      await result.current.sendConfirmation('run-001', false)
    })

    expect(result.current.messages[0].content).toBe('放弃本次操作')
  })

  it('should call onEvent for SSE events', async () => {
    const onEvent = vi.fn()
    const mockEvent = { type: 'text_delta', content: 'Hello' }

    // Create a mock stream that returns an event
    const mockReader = {
      read: vi.fn()
        .mockResolvedValueOnce({
          done: false,
          value: new TextEncoder().encode(`data:${JSON.stringify(mockEvent)}\n`),
        })
        .mockResolvedValueOnce({ done: true, value: undefined }),
    }

    mockFetch.mockResolvedValueOnce({
      ok: true,
      body: { getReader: () => mockReader },
    })

    const { result } = renderHook(() => useChatMessages({ userId: 'demo-user', onEvent }))

    await act(async () => {
      await result.current.sendMessage('run-001', 'test')
    })

    expect(onEvent).toHaveBeenCalledWith(expect.objectContaining({ type: 'text_delta' }))
  })

  it('should call onEvent with error on fetch failure', async () => {
    const onEvent = vi.fn()
    mockFetch.mockResolvedValueOnce({ ok: false, status: 500 })

    const { result } = renderHook(() => useChatMessages({ userId: 'demo-user', onEvent }))

    await act(async () => {
      await result.current.sendMessage('run-001', 'test')
    })

    expect(onEvent).toHaveBeenCalledWith(
      expect.objectContaining({ type: 'error', error: expect.stringContaining('500') })
    )
  })

  it('should add assistant message manually', () => {
    const { result } = renderHook(() => useChatMessages({ userId: 'demo-user' }))

    act(() => {
      result.current.addAssistantMessage('好的，我来帮你处理')
    })

    expect(result.current.messages).toHaveLength(1)
    expect(result.current.messages[0]).toEqual({
      role: 'assistant',
      content: '好的，我来帮你处理',
    })
  })

  it('should add assistant message with tool cards', () => {
    const { result } = renderHook(() => useChatMessages({ userId: 'demo-user' }))

    act(() => {
      result.current.addAssistantMessage('处理完成', [
        { toolCallId: 'tc-001', toolName: 'query_order', status: 'completed', result: '{"orderId":"ORD-123"}' },
      ])
    })

    expect(result.current.messages[0].toolCards).toHaveLength(1)
    expect(result.current.messages[0].toolCards?.[0].toolName).toBe('query_order')
  })

  it('should clear messages', async () => {
    mockFetch.mockResolvedValueOnce({
      ok: true,
      body: {
        getReader: () => ({
          read: vi.fn().mockResolvedValue({ done: true, value: undefined }),
        }),
      },
    })

    const { result } = renderHook(() => useChatMessages({ userId: 'demo-user' }))

    await act(async () => {
      await result.current.sendMessage('run-001', 'test')
    })

    expect(result.current.messages).toHaveLength(1)

    act(() => {
      result.current.clearMessages()
    })

    expect(result.current.messages).toHaveLength(0)
  })

  it('should skip ping events in stream', async () => {
    const onEvent = vi.fn()

    const mockReader = {
      read: vi.fn()
        .mockResolvedValueOnce({
          done: false,
          value: new TextEncoder().encode('data:ping\n'),
        })
        .mockResolvedValueOnce({ done: true, value: undefined }),
    }

    mockFetch.mockResolvedValueOnce({
      ok: true,
      body: { getReader: () => mockReader },
    })

    const { result } = renderHook(() => useChatMessages({ userId: 'demo-user', onEvent }))

    await act(async () => {
      await result.current.sendMessage('run-001', 'test')
    })

    // ping should not trigger onEvent
    expect(onEvent).not.toHaveBeenCalled()
  })
})