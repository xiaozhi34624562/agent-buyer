import { X } from 'lucide-react'
import { Button } from '../ui/Button'
import type { RuntimeState } from '../../types'
import { redactObject } from '../../utils/redaction'

interface DebugDrawerProps {
  runtimeState: RuntimeState | null
  loading: boolean
  error: string | null
  onClose: () => void
}

interface EntryGroupProps {
  title: string
  entries: Record<string, unknown>
}

function EntryGroup({ title, entries }: EntryGroupProps) {
  if (!entries || Object.keys(entries).length === 0) {
    return null
  }

  // Redact sensitive values before display
  const redactedEntries = redactObject(entries)

  return (
    <div className="mb-4">
      <h3 className="text-sm font-semibold text-gray-700 mb-2">{title}</h3>
      <div className="bg-gray-50 rounded p-2">
        {Object.entries(redactedEntries).map(([key, value]) => (
          <div key={key} className="text-xs mb-1">
            <span className="font-medium text-gray-600">{key}:</span>
            <span className="ml-1 text-gray-800">
              {typeof value === 'object' ? JSON.stringify(value) : String(value)}
            </span>
          </div>
        ))}
      </div>
    </div>
  )
}

export function DebugDrawer({ runtimeState, loading, error, onClose }: DebugDrawerProps) {
  if (!runtimeState && !loading && !error) {
    return null
  }

  // Extract entries by category (backend returns short keys: meta, queue, tools, etc.)
  const entries = runtimeState?.entries || {}

  // Backend AdminRuntimeStateDto returns entries with short keys
  const meta = (entries.meta as Record<string, unknown>) || {}
  const queue = (entries.queue as Record<string, unknown>) || {}
  const tools = (entries.tools as Record<string, unknown>) || {}
  const leases = (entries.leases as Record<string, unknown>) || {}
  const children = (entries.children as Record<string, unknown>) || {}
  const todos = (entries.todos as Record<string, unknown>) || {}
  const todoReminder = (entries['todo-reminder'] as string) || null

  // Group any remaining unrecognized keys
  const other: Record<string, unknown> = {}
  const knownKeys = ['meta', 'queue', 'tools', 'leases', 'children', 'todos', 'todo-reminder']
  for (const [key, value] of Object.entries(entries)) {
    if (!knownKeys.includes(key)) {
      other[key] = value
    }
  }

  return (
    <div className="fixed inset-y-0 right-0 w-80 bg-white border-l border-gray-200 shadow-lg z-50 flex flex-col">
      {/* Header */}
      <div className="p-4 border-b border-gray-200 flex items-center justify-between">
        <h2 className="text-lg font-bold text-gray-800">Debug View</h2>
        <Button variant="ghost" size="sm" onClick={onClose} icon={<X className="w-4 h-4" />}>
          Close
        </Button>
      </div>

      {/* Active run indicator */}
      {runtimeState?.activeRun !== undefined && (
        <div className="p-2 bg-blue-50 border-b border-blue-100">
          <span className={`text-sm font-medium ${runtimeState.activeRun ? 'text-blue-700' : 'text-gray-500'}`}>
            {runtimeState.activeRun ? '● Active Run' : '○ Not Active'}
          </span>
        </div>
      )}

      {/* Content */}
      <div className="flex-1 overflow-auto p-4">
        {loading && (
          <div className="text-center text-gray-500">Loading...</div>
        )}
        {error && (
          <div className="text-center text-red-500">{error}</div>
        )}
        {!loading && !error && runtimeState && (
          <>
            <EntryGroup title="Meta" entries={meta} />
            <EntryGroup title="Queue" entries={queue} />
            <EntryGroup title="Tools" entries={tools} />
            <EntryGroup title="Leases" entries={leases} />
            <EntryGroup title="Children" entries={children} />
            <EntryGroup title="Todos" entries={todos} />
            {todoReminder && (
              <div className="mb-4">
                <h3 className="text-sm font-semibold text-gray-700 mb-2">Todo Reminder</h3>
                <div className="bg-gray-50 rounded p-2">
                  <pre className="text-xs text-gray-800 whitespace-pre-wrap">{todoReminder}</pre>
                </div>
              </div>
            )}
            <EntryGroup title="Other" entries={other} />
          </>
        )}
      </div>

      {/* Footer - runId */}
      {runtimeState?.runId && (
        <div className="p-2 border-t border-gray-200 bg-gray-50">
          <span className="text-xs text-gray-500">Run: {runtimeState.runId}</span>
        </div>
      )}
    </div>
  )
}