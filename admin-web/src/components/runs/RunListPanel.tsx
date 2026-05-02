import type { RunSummary } from '../../types'
import { Panel } from '../ui/Panel'

interface RunItemProps {
  run: RunSummary
  selected: boolean
  onClick: () => void
}

function formatRunId(runId: string): string {
  // Show first 8 characters of runId with subtle styling
  return runId.length > 12 ? runId.slice(0, 12) + '...' : runId
}

function formatDate(dateStr: string | null): string {
  if (!dateStr) return ''
  const date = new Date(dateStr)
  const now = new Date()
  const diffMs = now.getTime() - date.getTime()
  const diffMins = Math.floor(diffMs / 60000)
  const diffHours = Math.floor(diffMs / 3600000)
  const diffDays = Math.floor(diffMs / 86400000)

  // Relative time for better UX
  if (diffMins < 1) return 'now'
  if (diffMins < 60) return `${diffMins}m ago`
  if (diffHours < 24) return `${diffHours}h ago`
  if (diffDays < 7) return `${diffDays}d ago`

  return date.toLocaleDateString('zh-CN', { month: 'short', day: 'numeric' })
}

function StatusBadge({ status }: { status: string }) {
  const getStyles = (): React.CSSProperties => {
    const base: React.CSSProperties = {
      fontFamily: 'var(--font-sans)',
      fontSize: '0.7rem',
      fontWeight: 500,
      padding: '0.2rem 0.4rem',
      borderRadius: 'var(--radius-sm)',
      letterSpacing: '0.04em',
      textTransform: 'uppercase',
    }

    switch (status) {
      case 'RUNNING':
        return { ...base, background: 'var(--color-accent-subtle)', color: 'var(--color-accent-hover)' }
      case 'SUCCEEDED':
        return { ...base, background: '#e8ebe8', color: 'var(--color-success)' }
      case 'FAILED':
        return { ...base, background: '#f5e8e8', color: 'var(--color-error)' }
      case 'PAUSED':
        return { ...base, background: '#f0ede8', color: 'var(--color-text-secondary)' }
      case 'WAITING_USER_CONFIRMATION':
        return { ...base, background: '#f5ebe0', color: 'var(--color-warning)' }
      case 'CANCELLED':
      case 'TIMEOUT':
        return { ...base, background: 'var(--color-bg-subtle)', color: 'var(--color-text-muted)' }
      default:
        return { ...base, background: 'var(--color-bg-subtle)', color: 'var(--color-text-secondary)' }
    }
  }

  const displayStatus = status === 'WAITING_USER_CONFIRMATION' ? 'WAITING' : status

  return <span style={getStyles()}>{displayStatus}</span>
}

export function RunItem({ run, selected, onClick }: RunItemProps) {
  return (
    <div
      data-testid={`run-item-${run.runId}`}
      className="px-4 py-3 cursor-pointer transition-all duration-150"
      style={{
        borderBottom: '1px solid var(--color-border-subtle)',
        background: selected ? 'var(--color-accent-subtle)' : 'transparent',
        opacity: selected ? 1 : 0.95,
      }}
      onClick={onClick}
      onMouseEnter={(e) => {
        if (!selected) {
          e.currentTarget.style.background = 'var(--color-bg-subtle)'
        }
      }}
      onMouseLeave={(e) => {
        if (!selected) {
          e.currentTarget.style.background = 'transparent'
        }
      }}
    >
      {/* Top row: ID + Status */}
      <div className="flex items-center justify-between mb-1.5">
        {/* Run ID - monospace but warm */}
        <span
          style={{
            fontFamily: 'var(--font-sans)',
            fontSize: '0.8rem',
            color: selected ? 'var(--color-text)' : 'var(--color-text-secondary)',
            fontWeight: selected ? 500 : 400,
            letterSpacing: '-0.02em',
          }}
        >
          {formatRunId(run.runId)}
        </span>
        <StatusBadge status={run.status} />
      </div>

      {/* Middle row: Provider/Model */}
      <div
        className="text-sm mb-1"
        style={{
          fontFamily: 'var(--font-serif)',
          color: 'var(--color-text-secondary)',
        }}
      >
        {run.primaryProvider} · {run.model}
      </div>

      {/* Bottom row: Turn + Time */}
      <div className="flex items-center justify-between">
        <span
          style={{
            fontFamily: 'var(--font-sans)',
            fontSize: '0.75rem',
            color: 'var(--color-text-muted)',
          }}
        >
          Turn {run.turnNo}/{run.maxTurns}
        </span>
        <span
          style={{
            fontFamily: 'var(--font-sans)',
            fontSize: '0.75rem',
            color: 'var(--color-text-muted)',
          }}
        >
          {formatDate(run.updatedAt)}
        </span>
      </div>

      {/* Parent run indicator */}
      {run.parentRunId && (
        <div
          className="mt-1.5 flex items-center gap-1"
          style={{
            fontFamily: 'var(--font-sans)',
            fontSize: '0.7rem',
            color: 'var(--color-text-muted)',
          }}
        >
          <span style={{ opacity: 0.5 }}>↳</span>
          <span>{formatRunId(run.parentRunId)}</span>
          {run.parentLinkStatus && (
            <span style={{ color: 'var(--color-accent)' }}>
              ({run.parentLinkStatus})
            </span>
          )}
        </div>
      )}

      {/* Error indicator - subtle */}
      {run.lastError && (
        <div
          className="mt-2 text-xs truncate"
          style={{
            fontFamily: 'var(--font-sans)',
            color: 'var(--color-error)',
            background: '#f5e8e8',
            padding: '0.25rem 0.5rem',
            borderRadius: 'var(--radius-sm)',
          }}
        >
          {run.lastError}
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
    <Panel title="Runs" className="h-full flex flex-col">
      {/* Filters - subtle, integrated */}
      <div
        className="px-4 py-2 flex gap-3"
        style={{
          borderBottom: '1px solid var(--color-border-subtle)',
          background: 'var(--color-bg-subtle)',
        }}
      >
        {/* Status filter */}
        <select
          className="px-2 py-1.5 text-xs rounded transition-all duration-150"
          style={{
            fontFamily: 'var(--font-sans)',
            background: 'var(--color-bg-card)',
            border: '1px solid var(--color-border)',
            color: 'var(--color-text)',
          }}
          value={statusFilter || ''}
          onChange={(e) => onStatusFilterChange?.(e.target.value || null)}
        >
          <option value="">All</option>
          {statuses.map(s => (
            <option key={s} value={s}>{s === 'WAITING_USER_CONFIRMATION' ? 'WAITING' : s}</option>
          ))}
        </select>

        {/* User filter */}
        <input
          type="text"
          className="px-2 py-1.5 text-xs flex-1 rounded transition-all duration-150"
          style={{
            fontFamily: 'var(--font-serif)',
            background: 'var(--color-bg-card)',
            border: '1px solid var(--color-border)',
            color: 'var(--color-text)',
          }}
          placeholder="Filter user..."
          value={userIdFilter || ''}
          onChange={(e) => onUserIdFilterChange?.(e.target.value || null)}
        />
      </div>

      {/* List content */}
      <div className="flex-1 overflow-auto">
        {loading && (
          <div
            className="py-8 text-center"
            style={{
              fontFamily: 'var(--font-serif)',
              fontStyle: 'italic',
              color: 'var(--color-text-muted)',
            }}
          >
            <span className="animate-pulse-soft">Loading runs...</span>
          </div>
        )}

        {error && (
          <div
            className="py-8 text-center px-4"
            style={{
              fontFamily: 'var(--font-sans)',
              color: 'var(--color-error)',
            }}
          >
            {error}
          </div>
        )}

        {!loading && !error && runs.length === 0 && (
          <div
            className="py-8 text-center"
            style={{
              fontFamily: 'var(--font-serif)',
              fontStyle: 'italic',
              color: 'var(--color-text-muted)',
            }}
          >
            No runs found
          </div>
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

      {/* Count indicator */}
      {!loading && runs.length > 0 && (
        <div
          className="px-4 py-2 text-center"
          style={{
            borderTop: '1px solid var(--color-border-subtle)',
            fontFamily: 'var(--font-sans)',
            fontSize: '0.7rem',
            color: 'var(--color-text-muted)',
          }}
        >
          {runs.length} runs
        </div>
      )}
    </Panel>
  )
}