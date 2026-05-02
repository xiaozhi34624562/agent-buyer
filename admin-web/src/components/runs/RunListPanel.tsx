import type { RunSummary } from '../../types'

interface RunItemProps {
  run: RunSummary
  selected: boolean
  onClick: () => void
}

function formatRunId(runId: string): string {
  // Show first 8 characters of runId
  return runId.length > 8 ? runId.slice(0, 8) + '...' : runId
}

function formatDate(dateStr: string | null): string {
  if (!dateStr) return ''
  const date = new Date(dateStr)
  return date.toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

function StatusBadge({ status }: { status: string }) {
  const colors: Record<string, string> = {
    RUNNING: 'bg-blue-100 text-blue-700',
    SUCCEEDED: 'bg-green-100 text-green-700',
    FAILED: 'bg-red-100 text-red-700',
    PAUSED: 'bg-yellow-100 text-yellow-700',
    WAITING_USER_CONFIRMATION: 'bg-orange-100 text-orange-700',
    CANCELLED: 'bg-gray-100 text-gray-700',
    TIMEOUT: 'bg-gray-100 text-gray-700',
  }
  const colorClass = colors[status] || 'bg-gray-100 text-gray-700'
  return (
    <span className={`px-2 py-0.5 rounded text-xs font-medium ${colorClass}`}>
      {status}
    </span>
  )
}

export function RunItem({ run, selected, onClick }: RunItemProps) {
  return (
    <div
      data-testid={`run-item-${run.runId}`}
      className={`p-3 border-b border-gray-100 cursor-pointer hover:bg-gray-50 ${
        selected ? 'bg-blue-50 border-l-2 border-l-blue-500' : ''
      }`}
      onClick={onClick}
    >
      <div className="flex items-center justify-between mb-1">
        <span className="font-mono text-sm text-gray-600">{formatRunId(run.runId)}</span>
        <StatusBadge status={run.status} />
      </div>
      <div className="text-sm text-gray-500 mb-1">
        {run.userId} | {run.primaryProvider}/{run.model}
      </div>
      <div className="flex items-center justify-between text-xs text-gray-400">
        <span>Turn {run.turnNo}/{run.maxTurns}</span>
        <span>{formatDate(run.updatedAt)}</span>
      </div>
      {run.parentRunId && (
        <div className="text-xs text-gray-400 mt-1">
          Parent: {formatRunId(run.parentRunId)}
          {run.parentLinkStatus && <span className="ml-1">({run.parentLinkStatus})</span>}
        </div>
      )}
      {run.lastError && (
        <div className="text-xs text-red-500 mt-1 truncate">
          Error: {run.lastError}
        </div>
      )}
    </div>
  )
}

interface RunListPanelProps {
  runs: RunSummary[]
  loading: boolean
  error: string | null
  selectedRunId: string | null
  onSelectRun: (runId: string) => void
  statusFilter?: string | null
  userIdFilter?: string | null
  onStatusFilterChange?: (status: string | null) => void
  onUserIdFilterChange?: (userId: string | null) => void
}

export function RunListPanel({
  runs,
  loading,
  error,
  selectedRunId,
  onSelectRun,
  statusFilter,
  userIdFilter,
  onStatusFilterChange,
  onUserIdFilterChange,
}: RunListPanelProps) {
  const statuses = ['RUNNING', 'SUCCEEDED', 'FAILED', 'PAUSED', 'WAITING_USER_CONFIRMATION', 'CANCELLED', 'TIMEOUT']

  return (
    <div className="h-full flex flex-col">
      {/* Filters */}
      <div className="p-2 border-b border-gray-200 flex gap-2">
        <select
          className="px-2 py-1 border border-gray-300 rounded text-sm"
          value={statusFilter || ''}
          onChange={(e) => onStatusFilterChange?.(e.target.value || null)}
        >
          <option value="">All Status</option>
          {statuses.map(s => (
            <option key={s} value={s}>{s}</option>
          ))}
        </select>
        <input
          type="text"
          className="px-2 py-1 border border-gray-300 rounded text-sm flex-1"
          placeholder="Filter by userId"
          value={userIdFilter || ''}
          onChange={(e) => onUserIdFilterChange?.(e.target.value || null)}
        />
      </div>

      {/* List content */}
      <div className="flex-1 overflow-auto">
        {loading && (
          <div className="p-4 text-center text-gray-500">Loading...</div>
        )}
        {error && (
          <div className="p-4 text-center text-red-500">{error}</div>
        )}
        {!loading && !error && runs.length === 0 && (
          <div className="p-4 text-center text-gray-500">No runs found</div>
        )}
        {!loading && !error && runs.map(run => (
          <RunItem
            key={run.runId}
            run={run}
            selected={selectedRunId === run.runId}
            onClick={() => onSelectRun(run.runId)}
          />
        ))}
      </div>
    </div>
  )
}