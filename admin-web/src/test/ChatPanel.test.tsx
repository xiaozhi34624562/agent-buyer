import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { ChatPanel } from '../components/chat/ChatPanel'
import type { ToolCard } from '../types'

describe('ChatPanel', () => {
  const defaultProps = {
    assistantDraft: '',
    toolCards: [] as ToolCard[],
    isStreaming: false,
    runStatus: null,
    nextActionRequired: null,
    messages: [] as { role: 'user' | 'assistant'; content: string; toolCards?: ToolCard[] }[],
    onSendMessage: vi.fn(),
    onConfirm: vi.fn(),
    onReject: vi.fn(),
    onStopStream: vi.fn(),
    // Run control props
    selectedRunId: null,
    onNewChat: vi.fn(),
    onRefreshRun: vi.fn(),
    onInterrupt: vi.fn(),
    onAbort: vi.fn(),
  }

  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('should display input placeholder', () => {
    render(<ChatPanel {...defaultProps} />)
    expect(screen.getByPlaceholderText('输入消息...')).toBeInTheDocument()
  })

  it('should display paused placeholder when PAUSED + user_input', () => {
    render(<ChatPanel {...defaultProps} runStatus="PAUSED" nextActionRequired="user_input" />)
    expect(screen.getByPlaceholderText('补充说明后继续...')).toBeInTheDocument()
  })

  it('should call onSendMessage when Send clicked', () => {
    const onSendMessage = vi.fn()
    render(<ChatPanel {...defaultProps} onSendMessage={onSendMessage} />)

    const input = screen.getByPlaceholderText('输入消息...')
    fireEvent.change(input, { target: { value: '取消我昨天的订单' } })
    fireEvent.click(screen.getByText('发送'))

    expect(onSendMessage).toHaveBeenCalledWith('取消我昨天的订单')
  })

  it('should call onSendMessage on Enter key', () => {
    const onSendMessage = vi.fn()
    render(<ChatPanel {...defaultProps} onSendMessage={onSendMessage} />)

    const input = screen.getByPlaceholderText('输入消息...')
    fireEvent.change(input, { target: { value: '查询订单状态' } })
    fireEvent.keyDown(input, { key: 'Enter' })

    expect(onSendMessage).toHaveBeenCalledWith('查询订单状态')
  })

  it('should not call onSendMessage when streaming', () => {
    const onSendMessage = vi.fn()
    render(<ChatPanel {...defaultProps} isStreaming={true} onSendMessage={onSendMessage} />)

    const input = screen.getByPlaceholderText('输入消息...')
    expect(input).toBeDisabled()
  })

  it('should show Stop button when streaming', () => {
    render(<ChatPanel {...defaultProps} isStreaming={true} />)
    expect(screen.getByText('停止')).toBeInTheDocument()
    expect(screen.queryByText('发送')).not.toBeInTheDocument()
  })

  it('should call onStopStream when Stop clicked', () => {
    const onStopStream = vi.fn()
    render(<ChatPanel {...defaultProps} isStreaming={true} onStopStream={onStopStream} />)

    fireEvent.click(screen.getByText('停止'))
    expect(onStopStream).toHaveBeenCalled()
  })

  it('should display user messages', () => {
    const messages = [{ role: 'user' as const, content: '取消订单' }]
    render(<ChatPanel {...defaultProps} messages={messages} />)
    expect(screen.getByText('取消订单')).toBeInTheDocument()
  })

  it('should display assistant messages', () => {
    const messages = [{ role: 'assistant' as const, content: '好的，我来帮你取消订单' }]
    render(<ChatPanel {...defaultProps} messages={messages} />)
    expect(screen.getByText('好的，我来帮你取消订单')).toBeInTheDocument()
  })

  it('should display streaming assistant draft', () => {
    render(<ChatPanel {...defaultProps} isStreaming={true} assistantDraft="正在查询..." />)
    expect(screen.getByText('正在查询...')).toBeInTheDocument()
  })

  it('should display tool cards', () => {
    const toolCards: ToolCard[] = [{
      toolCallId: 'tc-001',
      toolName: 'query_order',
      args: {},
      status: 'completed',
      result: '{"orderId":"ORD-123"}',
      resultStatus: 'SUCCESS',
    }]
    render(<ChatPanel {...defaultProps} isStreaming={true} toolCards={toolCards} />)
    expect(screen.getByText('query_order')).toBeInTheDocument()
    expect(screen.getByText('completed')).toBeInTheDocument()
  })

  it('should display WAITING_USER_CONFIRMATION buttons', () => {
    render(<ChatPanel {...defaultProps} runStatus="WAITING_USER_CONFIRMATION" />)
    expect(screen.getByText('确认继续')).toBeInTheDocument()
    expect(screen.getByText('放弃操作')).toBeInTheDocument()
  })

  it('should call onConfirm when Confirm clicked', () => {
    const onConfirm = vi.fn()
    render(<ChatPanel {...defaultProps} runStatus="WAITING_USER_CONFIRMATION" onConfirm={onConfirm} />)
    fireEvent.click(screen.getByText('确认继续'))
    expect(onConfirm).toHaveBeenCalled()
  })

  it('should call onReject when Reject clicked', () => {
    const onReject = vi.fn()
    render(<ChatPanel {...defaultProps} runStatus="WAITING_USER_CONFIRMATION" onReject={onReject} />)
    fireEvent.click(screen.getByText('放弃操作'))
    expect(onReject).toHaveBeenCalled()
  })

  it('should not show HITL buttons when streaming', () => {
    render(<ChatPanel {...defaultProps} runStatus="WAITING_USER_CONFIRMATION" isStreaming={true} />)
    expect(screen.queryByText('确认继续')).not.toBeInTheDocument()
  })

  it('should clear input after sending', () => {
    render(<ChatPanel {...defaultProps} />)

    const input = screen.getByPlaceholderText('输入消息...')
    fireEvent.change(input, { target: { value: 'test message' } })
    fireEvent.click(screen.getByText('Send'))

    expect(input).toHaveValue('')
  })

  it('should not send empty message', () => {
    const onSendMessage = vi.fn()
    render(<ChatPanel {...defaultProps} onSendMessage={onSendMessage} />)

    fireEvent.click(screen.getByText('发送'))
    expect(onSendMessage).not.toHaveBeenCalled()
  })

  it('should not send whitespace only message', () => {
    const onSendMessage = vi.fn()
    render(<ChatPanel {...defaultProps} onSendMessage={onSendMessage} />)

    const input = screen.getByPlaceholderText('输入消息...')
    fireEvent.change(input, { target: { value: '   ' } })
    fireEvent.click(screen.getByText('Send'))

    expect(onSendMessage).not.toHaveBeenCalled()
  })
})