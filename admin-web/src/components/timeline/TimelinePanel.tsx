import type { TrajectoryNode, TrajectoryNodeType } from '../../types'
import { Panel } from '../ui/Panel'

interface TimelineNodeProps {
  node: TrajectoryNode
  index: number
}

function formatTimestamp(timestamp: string | null): string {
  if (!timestamp) return ''
  const date = new Date(timestamp)
  return date.toLocaleTimeString('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  })
}

function NodeTypeBadge({ nodeType }: { nodeType: TrajectoryNodeType }) {
  const getStyles = (): React.CSSProperties => {
    const base: React.CSSProperties = {
      fontFamily: 'var(--font-sans)',
      fontSize: '0.65rem',
      fontWeight: 500,
      padding: '0.15rem 0.35rem',
      borderRadius: 'var(--radius-sm)',
      letterSpacing: '0.05em',
      textTransform: 'uppercase',
    }

    switch (nodeType) {
      case 'MESSAGE':
        return { ...base, background: '#f0ede8', color: 'var(--color-text-secondary)' }
      case 'LLM_ATTEMPT':
        return { ...base, background: '#e5e0f0', color: '#7050a0' }
      case 'TOOL_CALL':
        return { ...base, background: 'var(--color-accent-subtle)', color: 'var(--color-accent-hover)' }
      case 'TOOL_PROGRESS':
        return { ...base, background: '#e8f5e8', color: 'var(--color-success)' }
      case 'TOOL_RESULT':
        return { ...base, background: '#e8ebe8', color: 'var(--color-success)' }
      case 'EVENT':
        return { ...base, background: '#f5ebe0', color: 'var(--color-warning)' }
      case 'COMPACTION':
        return { ...base, background: 'var(--color-bg-subtle)', color: 'var(--color-text-muted)' }
      default:
        return { ...base, background: 'var(--color-bg-subtle)', color: 'var(--color-text-secondary)' }
    }
  }

  // Shorten labels for readability
  const labels: Partial<Record<TrajectoryNodeType, string>> = {
    MESSAGE: 'MSG',
    LLM_ATTEMPT: 'LLM',
    TOOL_CALL: 'CALL',
    TOOL_PROGRESS: 'PROG',
    TOOL_RESULT: 'RES',
    EVENT: 'EVT',
    COMPACTION: 'CMP',
  }

  return <span style={getStyles()}>{labels[nodeType] || nodeType}</span>
}

function MessageNode({ node }: TimelineNodeProps) {
  const getRoleColor = () => {
    switch (node.role) {
      case 'user': return 'var(--color-accent)'
      case 'assistant': return 'var(--color-success)'
      case 'system': return 'var(--color-text-muted)'
      default: return 'var(--color-text)'
    }
  }

  return (
    <div className="px-3 py-2">
      {/* Role indicator */}
      <div className="flex items-center gap-2 mb-1.5">
        <span
          style={{
            fontFamily: 'var(--font-sans)',
            fontWeight: 500,
            fontSize: '0.8rem',
            color: getRoleColor(),
          }}
        >
          {node.role}
        </span>
        <span
          style={{
            fontFamily: 'var(--font-sans)',
            fontSize: '0.7rem',
            color: 'var(--color-text-muted)',
          }}
        >
          {formatTimestamp(node.timestamp)}
        </span>
      </div>

      {/* Content */}
      <div
        className="text-sm max-h-24 overflow-auto"
        style={{
          fontFamily: 'var(--font-serif)',
          color: 'var(--color-text)',
          lineHeight: 1.5,
        }}
      >
        {node.content}
      </div>
    </div>
  )
}

function LlmAttemptNode({ node }: TimelineNodeProps) {
  return (
    <div className="px-3 py-2">
      {/* Provider/Model */}
      <div
        className="mb-1"
        style={{
          fontFamily: 'var(--font-sans)',
          fontSize: '0.75rem',
          color: 'var(--color-text)',
        }}
      >
        {node.provider} · {node.model}
      </div>

      {/* Token usage */}
      <div
        style={{
          fontFamily: 'var(--font-sans)',
          fontSize: '0.7rem',
          color: 'var(--color-text-muted)',
        }}
      >
        {node.inputTokens} in → {node.outputTokens} out
        {node.totalTokens && <span className="ml-2">({node.totalTokens} total)</span>}
      </div>

      {/* Finish reason */}
      {node.finishReason && (
        <div
          className="mt-1"
          style={{
            fontFamily: 'var(--font-sans)',
            fontSize: '0.65rem',
            color: 'var(--color-accent)',
            textTransform: 'uppercase',
          }}
        >
          {node.finishReason}
        </div>
      )}
    </div>
  )
}

function ToolCallNode({ node }: TimelineNodeProps) {
  return (
    <div className="px-3 py-2">
      {/* Tool name */}
      <div className="flex items-center gap-2 mb-1.5">
        <span
          style={{
            fontFamily: 'var(--font-sans)',
            fontWeight: 500,
            fontSize: '0.85rem',
            color: 'var(--color-accent)',
          }}
        >
          {node.toolName}
        </span>
        <span
          style={{
            fontFamily: 'var(--font-sans)',
            fontSize: '0.65rem',
            color: 'var(--color-text-muted)',
          }}
        >
          {formatTimestamp(node.timestamp)}
        </span>
      </div>

      {/* Args - collapsible/expandable would be nice but keep simple */}
      {node.args && (
        <div
          className="text-xs max-h-16 overflow-auto rounded p-1.5"
          style={{
            fontFamily: 'var(--font-serif)',
            background: 'var(--color-bg-subtle)',
            color: 'var(--color-text-secondary)',
          }}
        >
          {JSON.stringify(node.args, null, 2)}
        </div>
      )}
    </div>
  )
}

function ToolProgressNode({ node }: TimelineNodeProps) {
  return (
    <div className="px-3 py-2">
      {/* Progress bar */}
      <div className="flex items-center gap-2 mb-1">
        <div
          className="flex-1 h-1.5 rounded overflow-hidden"
          style={{ background: 'var(--color-border)' }}
        >
          <div
            className="h-full rounded"
            style={{
              width: `${node.percent || 0}%`,
              background: 'var(--color-accent)',
            }}
          />
        </div>
        <span
          style={{
            fontFamily: 'var(--font-sans)',
            fontSize: '0.7rem',
            color: 'var(--color-accent)',
          }}
        >
          {node.percent}%
        </span>
      </div>

      {/* Message */}
      {node.message && (
        <div
          style={{
            fontFamily: 'var(--font-serif)',
            fontSize: '0.75rem',
            color: 'var(--color-text-secondary)',
          }}
        >
          {node.message}
        </div>
      )}
    </div>
  )
}

function ToolResultNode({ node }: TimelineNodeProps) {
  return (
    <div className="px-3 py-2">
      {/* Status indicators */}
      <div className="flex items-center gap-2 mb-1.5">
        {node.synthetic && (
          <span
            style={{
              fontFamily: 'var(--font-sans)',
              fontSize: '0.65rem',
              background: '#f5e8e8',
              color: 'var(--color-error)',
              padding: '0.1rem 0.3rem',
              borderRadius: 'var(--radius-sm)',
            }}
          >
            synthetic
          </span>
        )}
        {node.cancelReason && (
          <span
            style={{
              fontFamily: 'var(--font-sans)',
              fontSize: '0.65rem',
              color: 'var(--color-error)',
            }}
          >
            {node.cancelReason}
          </span>
        )}
      </div>

      {/* Result content */}
      {node.result && (
        <div
          className="text-sm max-h-20 overflow-auto"
          style={{
            fontFamily: 'var(--font-serif)',
            color: 'var(--color-text)',
          }}
        >
          {node.result}
        </div>
      )}
    </div>
  )
}

function EventNode({ node }: TimelineNodeProps) {
  return (
    <div className="px-3 py-2">
      {/* Event type */}
      <div className="flex items-center gap-2 mb-1">
        <span
          style={{
            fontFamily: 'var(--font-sans)',
            fontWeight: 500,
            fontSize: '0.8rem',
            color: 'var(--color-warning)',
          }}
        >
          {node.eventType}
        </span>
        <span
          style={{
            fontFamily: 'var(--font-sans)',
            fontSize: '0.65rem',
            color: 'var(--color-text-muted)',
          }}
        >
          {formatTimestamp(node.timestamp)}
        </span>
      </div>

      {/* Event data */}
      {node.eventData && (
        <div
          className="text-xs max-h-16 overflow-auto rounded p-1.5"
          style={{
            fontFamily: 'var(--font-serif)',
            background: 'var(--color-bg-subtle)',
            color: 'var(--color-text-secondary)',
          }}
        >
          {JSON.stringify(node.eventData, null, 2)}
        </div>
      )}
    </div>
  )
}

function CompactionNode({ node }: TimelineNodeProps) {
  return (
    <div className="px-3 py-2">
      {/* Strategy */}
      <div className="flex items-center gap-2 mb-1">
        <span
          style={{
            fontFamily: 'var(--font-sans)',
            fontWeight: 500,
            fontSize: '0.8rem',
            color: 'var(--color-text-secondary)',
          }}
        >
          {node.strategy}
        </span>
      </div>

      {/* Token reduction */}
      <div
        style={{
          fontFamily: 'var(--font-sans)',
          fontSize: '0.7rem',
          color: 'var(--color-text-muted)',
        }}
      >
        {node.beforeTokens} → {node.afterTokens}
        {node.beforeTokens && node.afterTokens && (
          <span className="ml-2" style={{ color: 'var(--color-success)' }}>
            ({Math.round((1 - node.afterTokens / node.beforeTokens) * 100)}% saved)
          </span>
        )}
      </div>

      {/* Compacted messages */}
      {node.compactedMessageIds?.length && (
        <div
          style={{
            fontFamily: 'var(--font-sans)',
            fontSize: '0.65rem',
            color: 'var(--color-text-muted)',
          }}
        >
          {node.compactedMessageIds.length} messages compacted
        </div>
      )}
    </div>
  )
}

function TimelineNode({ node, index }: TimelineNodeProps) {
  const components: Record<TrajectoryNodeType, React.FC<TimelineNodeProps>> = {
    MESSAGE: MessageNode,
    LLM_ATTEMPT: LlmAttemptNode,
    TOOL_CALL: ToolCallNode,
    TOOL_PROGRESS: ToolProgressNode,
    TOOL_RESULT: ToolResultNode,
    EVENT: EventNode,
    COMPACTION: CompactionNode,
  }
  const Component = components[node.nodeType]

  return (
    <div
      data-testid={`timeline-node-${node.nodeId}`}
      className="animate-slide-up"
      style={{
        borderBottom: '1px solid var(--color-border-subtle)',
        animationDelay: `${Math.min(index * 30, 500)}ms`,
        opacity: 0,
        animationFillMode: 'forwards',
      }}
    >
      {/* Node header */}
      <div
        className="flex items-center gap-2 px-3 py-1.5"
        style={{ background: 'var(--color-bg-subtle)' }}
      >
        <NodeTypeBadge nodeType={node.nodeType} />
        <span
          style={{
            fontFamily: 'var(--font-sans)',
            fontSize: '0.65rem',
            color: 'var(--color-text-muted)',
          }}
        >
          {node.nodeId}
        </span>
      </div>

      {/* Node content */}
      <Component node={node} index={index} />
    </div>
  )
}

interface TimelinePanelProps {
  nodes: TrajectoryNode[]
  loading: boolean
  error: string | null
}

export function TimelinePanel({ nodes, loading, error }: TimelinePanelProps) {
  return (
    <Panel
      title="Timeline"
      className="h-full flex flex-col overflow-hidden"
      actions={
        nodes.length > 0 && (
          <span
            style={{
              fontFamily: 'var(--font-sans)',
              fontSize: '0.7rem',
              color: 'var(--color-text-muted)',
            }}
          >
            {nodes.length} nodes
          </span>
        )
      }
    >
      {/* Content */}
      <div className="flex-1 overflow-auto">
        {loading && (
          <div
            className="py-12 text-center"
            style={{
              fontFamily: 'var(--font-serif)',
              fontStyle: 'italic',
              color: 'var(--color-text-muted)',
            }}
          >
            <span className="animate-pulse-soft">Loading trajectory...</span>
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

        {!loading && !error && nodes.length === 0 && (
          <div
            className="py-12 text-center"
            style={{
              fontFamily: 'var(--font-serif)',
              fontStyle: 'italic',
              color: 'var(--color-text-muted)',
            }}
          >
            <p>No trajectory data</p>
            <p className="text-sm mt-1">Select a run to view timeline</p>
          </div>
        )}

        {!loading && !error && nodes.map((node, idx) => (
          <TimelineNode key={node.nodeId} node={node} index={idx} />
        ))}
      </div>
    </Panel>
  )
}