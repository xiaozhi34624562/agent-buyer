import { Plus, RefreshCw, PauseCircle, XCircle } from 'lucide-react'
import { Button } from '../ui/Button'

interface RunControlsProps {
  selectedRunId: string | null
  runStatus: string | null
  isStreaming: boolean
  onNewChat: () => void
  onRefreshRun: () => void
  onInterrupt: () => void
  onAbort: () => void
}

// Terminal statuses that cannot be interrupted or aborted
const TERMINAL_STATUSES = ['SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMEOUT']

export function RunControls({
  selectedRunId,
  runStatus,
  isStreaming,
  onNewChat,
  onRefreshRun,
  onInterrupt,
  onAbort,
}: RunControlsProps) {
  const isTerminal = runStatus ? TERMINAL_STATUSES.includes(runStatus) : true
  const hasSelection = selectedRunId !== null

  return (
    <div className="flex items-center gap-2 p-2 bg-gray-50 border-b border-gray-200">
      <Button
        variant="ghost"
        size="sm"
        onClick={onNewChat}
        icon={<Plus className="w-4 h-4" />}
        disabled={isStreaming}
      >
        New Chat
      </Button>

      <Button
        variant="ghost"
        size="sm"
        onClick={onRefreshRun}
        icon={<RefreshCw className="w-4 h-4" />}
        disabled={!hasSelection || isStreaming}
        loading={isStreaming}
      >
        Refresh
      </Button>

      <Button
        variant="secondary"
        size="sm"
        onClick={onInterrupt}
        icon={<PauseCircle className="w-4 h-4" />}
        disabled={!hasSelection || isTerminal || isStreaming}
      >
        Interrupt
      </Button>

      <Button
        variant="danger"
        size="sm"
        onClick={onAbort}
        icon={<XCircle className="w-4 h-4" />}
        disabled={!hasSelection || isTerminal || isStreaming}
      >
        Abort
      </Button>
    </div>
  )
}