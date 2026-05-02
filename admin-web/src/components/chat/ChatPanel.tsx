import { useState, useCallback } from 'react'
import { Send, Square } from 'lucide-react'
import { Button } from '../ui/Button'
import type { ToolCard } from '../../hooks/useChatStream'

interface ChatMessage {
  role: 'user' | 'assistant'
  content: string
  toolCards?: ToolCard[]
}

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
}

function ToolCardView({ card }: { card: ToolCard }) {
  return (
    <div className="bg-gray-50 rounded p-2 mb-2 text-sm">
      <div className="flex items-center gap-2 mb-1">
        <span className="font-medium text-blue-600">{card.toolName}</span>
        <span className={`text-xs ${
          card.status === 'completed' ? 'text-green-600' :
          card.status === 'error' ? 'text-red-600' :
          card.status === 'running' ? 'text-cyan-600' :
          'text-gray-500'
        }`}>
          {card.status}
        </span>
      </div>
      {card.progress !== undefined && (
        <div className="text-xs text-gray-500 mb-1">
          Progress: {card.progress}% {card.progressMessage}
        </div>
      )}
      {card.result && (
        <div className="text-xs text-gray-600 max-h-20 overflow-auto">
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
}: ChatPanelProps) {
  const [inputValue, setInputValue] = useState('')

  const handleSend = useCallback(() => {
    if (inputValue.trim()) {
      onSendMessage(inputValue.trim())
      setInputValue('')
    }
  }, [inputValue, onSendMessage])

  const placeholder = runStatus === 'PAUSED' && nextActionRequired === 'user_input'
    ? '补充订单号、说明或下一步指令...'
    : '输入消息...'

  return (
    <div className="h-full flex flex-col">
      {/* Messages area */}
      <div className="flex-1 overflow-auto p-4">
        {messages.map((msg, idx) => (
          <div key={idx} className={`mb-4 ${msg.role === 'user' ? 'text-right' : 'text-left'}`}>
            <div className={`inline-block max-w-[80%] rounded-lg px-3 py-2 ${
              msg.role === 'user' ? 'bg-blue-100 text-blue-800' : 'bg-gray-100 text-gray-800'
            }`}>
              {msg.content}
            </div>
            {msg.toolCards && msg.toolCards.map(card => (
              <ToolCardView key={card.toolCallId} card={card} />
            ))}
          </div>
        ))}

        {/* Streaming assistant draft */}
        {isStreaming && (
          <div className="mb-4 text-left">
            {assistantDraft && (
              <div className="inline-block max-w-[80%] rounded-lg px-3 py-2 bg-gray-100 text-gray-800">
                {assistantDraft}
              </div>
            )}
            {toolCards.length > 0 && toolCards.map(card => (
              <ToolCardView key={card.toolCallId} card={card} />
            ))}
          </div>
        )}
      </div>

      {/* HITL buttons */}
      {runStatus === 'WAITING_USER_CONFIRMATION' && !isStreaming && (
        <div className="p-2 bg-orange-50 border-t border-orange-100 flex gap-2 justify-center">
          <Button variant="primary" size="sm" onClick={onConfirm}>
            确认继续执行
          </Button>
          <Button variant="danger" size="sm" onClick={onReject}>
            放弃本次操作
          </Button>
        </div>
      )}

      {/* Input area */}
      <div className="p-2 border-t border-gray-200 flex gap-2">
        <input
          type="text"
          className="flex-1 px-3 py-2 border border-gray-300 rounded text-sm"
          placeholder={placeholder}
          value={inputValue}
          onChange={(e) => setInputValue(e.target.value)}
          disabled={isStreaming}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !isStreaming) {
              handleSend()
            }
          }}
        />
        {isStreaming ? (
          <Button variant="danger" size="sm" onClick={onStopStream} icon={<Square className="w-4 h-4" />}>
            Stop
          </Button>
        ) : (
          <Button variant="primary" size="sm" onClick={handleSend} icon={<Send className="w-4 h-4" />}>
            Send
          </Button>
        )}
      </div>
    </div>
  )
}