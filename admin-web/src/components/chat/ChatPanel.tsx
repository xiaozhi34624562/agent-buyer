import { useState, useCallback, useRef, useEffect } from 'react'
import { Send, Square, Check, X, Plus, RefreshCw } from 'lucide-react'
import { Button } from '../ui/Button'
import { Panel } from '../ui/Panel'
import type { ToolCard } from '../../types'

interface ChatMessage {
  role: 'user' | 'assistant'
  content: string
  toolCards?: ToolCard[]
}

// Terminal statuses that cannot be interrupted or aborted
const TERMINAL_STATUSES = ['SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMEOUT']

interface ChatPanelProps {
  assistantDraft: string
  toolCards: ToolCard[]
  isStreaming: boolean
  runStatus: string | null
  nextActionRequired: string | null
  messages: ChatMessage[]
  onSendMessage: (content: string) => void
  onConfirm: () => void
  onReject: () => void
  onStopStream: () => void
  // Run controls
  selectedRunId: string | null
  onNewChat: () => void
  onRefreshRun: () => void
  onInterrupt: () => void
  onAbort: () => void
}

function ToolCardView({ card }: { card: ToolCard }) {
  const getStatusColor = () => {
    switch (card.status) {
      case 'completed':
        return 'var(--color-success)'
      case 'error':
        return 'var(--color-error)'
      case 'running':
        return 'var(--color-accent)'
      default:
        return 'var(--color-text-muted)'
    }
  }

  return (
    <div
      className="mb-3 p-3 rounded animate-slide-up"
      style={{
        background: 'var(--color-bg-subtle)',
        border: '1px solid var(--color-border-subtle)',
      }}
    >
      {/* Tool header */}
      <div className="flex items-center gap-2 mb-2">
        <span
          style={{
            fontFamily: 'var(--font-sans)',
            fontWeight: 500,
            fontSize: '0.8rem',
            color: 'var(--color-text)',
          }}
        >
          {card.toolName}
        </span>
        <span
          style={{
            fontFamily: 'var(--font-sans)',
            fontSize: '0.7rem',
            color: getStatusColor(),
            textTransform: 'uppercase',
            letterSpacing: '0.05em',
          }}
        >
          {card.status}
        </span>
      </div>

      {/* Progress */}
      {card.progress !== undefined && (
        <div
          className="mb-2"
          style={{
            fontFamily: 'var(--font-sans)',
            fontSize: '0.75rem',
            color: 'var(--color-text-secondary)',
          }}
        >
          <div className="flex items-center justify-between mb-1">
            <span>{card.progressMessage}</span>
            <span>{card.progress}%</span>
          </div>
          {/* Progress bar */}
          <div
            className="h-1.5 rounded overflow-hidden"
            style={{ background: 'var(--color-border)' }}
          >
            <div
              className="h-full rounded transition-all duration-300"
              style={{
                width: `${card.progress}%`,
                background: 'var(--color-accent)',
              }}
            />
          </div>
        </div>
      )}

      {/* Result */}
      {card.result && (
        <div
          className="text-xs max-h-32 overflow-auto rounded p-2"
          style={{
            fontFamily: 'var(--font-serif)',
            background: 'var(--color-bg-card)',
            color: 'var(--color-text-secondary)',
          }}
        >
          {card.result}
        </div>
      )}
    </div>
  )
}

export function ChatPanel({
  assistantDraft,
  toolCards,
  isStreaming,
  runStatus,
  nextActionRequired,
  messages,
  onSendMessage,
  onConfirm,
  onReject,
  onStopStream,
  selectedRunId,
  onNewChat,
  onRefreshRun,
  onInterrupt,
  onAbort,
}: ChatPanelProps) {
  const [inputValue, setInputValue] = useState('')
  const messagesEndRef = useRef<HTMLDivElement>(null)

  // Auto-scroll to bottom
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages, assistantDraft, toolCards])

  const handleSend = useCallback(() => {
    if (inputValue.trim()) {
      onSendMessage(inputValue.trim())
      setInputValue('')
    }
  }, [inputValue, onSendMessage])

  const placeholder = runStatus === 'PAUSED' && nextActionRequired === 'user_input'
    ? '补充说明后继续...'
    : '输入消息...'

  const isTerminal = runStatus ? TERMINAL_STATUSES.includes(runStatus) : true
  const hasSelection = selectedRunId !== null

  return (
    <Panel title="Chat" className="h-full flex flex-col">
      {/* Run controls - at top of chat panel */}
      <div
        className="px-3 py-2 flex items-center gap-1.5 flex-wrap"
        style={{
          borderBottom: '1px solid var(--color-border-subtle)',
          background: 'var(--color-bg-subtle)',
        }}
      >
        <Button
          variant="ghost"
          size="sm"
          onClick={onNewChat}
          disabled={isStreaming}
        >
          <Plus className="w-3.5 h-3.5" />
          New
        </Button>

        <Button
          variant="ghost"
          size="sm"
          onClick={onRefreshRun}
          disabled={!hasSelection || isStreaming}
        >
          <RefreshCw className={`w-3.5 h-3.5 ${isStreaming ? 'animate-spin' : ''}`} />
          Refresh
        </Button>

        <div className="flex-1" />

        <Button
          variant="secondary"
          size="sm"
          onClick={onInterrupt}
          disabled={!hasSelection || isTerminal || isStreaming}
        >
          Interrupt
        </Button>

        <Button
          variant="danger"
          size="sm"
          onClick={onAbort}
          disabled={!hasSelection || isTerminal || isStreaming}
        >
          Abort
        </Button>
      </div>

      {/* Messages area */}
      <div
        className="flex-1 overflow-auto px-4 py-4"
        style={{ background: 'var(--color-bg)' }}
      >
        {/* Empty state */}
        {messages.length === 0 && !isStreaming && (
          <div
            className="py-12 text-center"
            style={{
              fontFamily: 'var(--font-serif)',
              fontStyle: 'italic',
              color: 'var(--color-text-muted)',
            }}
          >
            <p className="mb-2">对话尚未开始</p>
            <p className="text-sm">选择一个 Run 或创建新对话</p>
          </div>
        )}

        {/* Messages */}
        {messages.map((msg, idx) => (
          <div
            key={idx}
            className={`mb-4 animate-slide-up ${msg.role === 'user' ? 'flex justify-end' : ''}`}
            style={{ animationDelay: `${idx * 50}ms` }}
          >
            {/* User message - warm accent background */}
            {msg.role === 'user' && (
              <div
                className="max-w-[85%] px-4 py-3 rounded-lg"
                style={{
                  background: 'var(--color-accent-subtle)',
                  borderLeft: '3px solid var(--color-accent)',
                }}
              >
                <p
                  style={{
                    fontFamily: 'var(--font-serif)',
                    color: 'var(--color-text)',
                    lineHeight: 1.5,
                  }}
                >
                  {msg.content}
                </p>
              </div>
            )}

            {/* Assistant message */}
            {msg.role === 'assistant' && (
              <div className="max-w-[100%]">
                <p
                  className="mb-3"
                  style={{
                    fontFamily: 'var(--font-serif)',
                    color: 'var(--color-text)',
                    lineHeight: 1.7,
                  }}
                >
                  {msg.content}
                </p>
                {msg.toolCards && msg.toolCards.map(card => (
                  <ToolCardView key={card.toolCallId} card={card} />
                ))}
              </div>
            )}
          </div>
        ))}

        {/* Streaming assistant draft */}
        {isStreaming && (
          <div className="mb-4">
            {assistantDraft && (
              <p
                style={{
                  fontFamily: 'var(--font-serif)',
                  color: 'var(--color-text)',
                  lineHeight: 1.7,
                }}
              >
                {assistantDraft}
                {/* Typing indicator */}
                <span
                  className="inline-block ml-1 animate-pulse-soft"
                  style={{ color: 'var(--color-accent)' }}
                >
                  ▊
                </span>
              </p>
            )}
            {toolCards.length > 0 && toolCards.map(card => (
              <ToolCardView key={card.toolCallId} card={card} />
            ))}
          </div>
        )}

        {/* Scroll anchor */}
        <div ref={messagesEndRef} />
      </div>

      {/* HITL confirmation - warm, inviting */}
      {runStatus === 'WAITING_USER_CONFIRMATION' && !isStreaming && (
        <div
          className="px-4 py-3 flex gap-3 justify-center"
          style={{
            background: '#f5ebe0',
            borderTop: '1px solid var(--color-warning)',
          }}
        >
          <Button
            variant="accent"
            size="md"
            onClick={onConfirm}
          >
            <Check className="w-4 h-4" />
            确认继续
          </Button>
          <Button
            variant="ghost"
            size="md"
            onClick={onReject}
          >
            <X className="w-4 h-4" />
            放弃操作
          </Button>
        </div>
      )}

      {/* Input area */}
      <div
        className="px-4 py-3 flex gap-3"
        style={{
          borderTop: '1px solid var(--color-border)',
          background: 'var(--color-bg-card)',
        }}
      >
        <input
          type="text"
          className="flex-1 px-3 py-2.5 rounded text-sm transition-all duration-150"
          style={{
            fontFamily: 'var(--font-serif)',
            background: 'var(--color-bg)',
            border: '1px solid var(--color-border)',
            color: 'var(--color-text)',
          }}
          placeholder={placeholder}
          value={inputValue}
          onChange={(e) => setInputValue(e.target.value)}
          disabled={isStreaming}
          onFocus={(e) => {
            e.target.style.borderColor = 'var(--color-accent)'
          }}
          onBlur={(e) => {
            e.target.style.borderColor = 'var(--color-border)'
          }}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !isStreaming) {
              handleSend()
            }
          }}
        />

        {/* Send/Stop button */}
        {isStreaming ? (
          <Button
            variant="danger"
            size="md"
            onClick={onStopStream}
          >
            <Square className="w-4 h-4" />
            停止
          </Button>
        ) : (
          <Button
            variant="primary"
            size="md"
            onClick={handleSend}
            disabled={!inputValue.trim()}
          >
            <Send className="w-4 h-4" />
            发送
          </Button>
        )}
      </div>
    </Panel>
  )
}