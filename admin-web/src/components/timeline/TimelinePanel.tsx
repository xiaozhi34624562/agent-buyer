import type { TrajectoryNode, TrajectoryNodeType } from '../../types'

interface TimelineNodeProps {
  node: TrajectoryNode
}

function formatTimestamp(timestamp: string | null): string {
  if (!timestamp) return ''
  const date = new Date(timestamp)
  return date.toLocaleString('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  })
}

function NodeTypeBadge({ nodeType }: { nodeType: TrajectoryNodeType }) {
  const colors: Record<TrajectoryNodeType, string> = {
    MESSAGE: 'bg-purple-100 text-purple-700',
    LLM_ATTEMPT: 'bg-indigo-100 text-indigo-700',
    TOOL_CALL: 'bg-blue-100 text-blue-700',
    TOOL_PROGRESS: 'bg-cyan-100 text-cyan-700',
    TOOL_RESULT: 'bg-green-100 text-green-700',
    EVENT: 'bg-orange-100 text-orange-700',
    COMPACTION: 'bg-gray-100 text-gray-700',
  }
  return (
    <span className={`px-2 py-0.5 rounded text-xs font-medium ${colors[nodeType]}`}>
      {nodeType}
    </span>
  )
}

function MessageNode({ node }: TimelineNodeProps) {
  const roleColors: Record<string, string> = {
    user: 'text-blue-600',
    assistant: 'text-green-600',
    system: 'text-gray-600',
  }
  return (
    <div className="p-2">
      <div className="flex items-center gap-2 mb-1">
        <span className={`font-medium ${roleColors[node.role || 'user']}`}>
          {node.role}
        </span>
        <span className="text-xs text-gray-400">{formatTimestamp(node.timestamp)}</span>
      </div>
      <div className="text-sm text-gray-700 whitespace-pre-wrap max-h-32 overflow-auto">
        {node.content}
      </div>
    </div>
  )
}

function LlmAttemptNode({ node }: TimelineNodeProps) {
  return (
    <div className="p-2">
      <div className="flex items-center gap-2 mb-1">
        <span className="text-xs text-gray-400">{formatTimestamp(node.timestamp)}</span>
      </div>
      <div className="text-sm text-gray-600">
        Provider: {node.provider} | Model: {node.model}
      </div>
      <div className="text-xs text-gray-500">
        Tokens: {node.inputTokens} in / {node.outputTokens} out
      </div>
    </div>
  )
}

function ToolCallNode({ node }: TimelineNodeProps) {
  return (
    <div className="p-2">
      <div className="flex items-center gap-2 mb-1">
        <span className="font-medium text-blue-600">{node.toolName}</span>
        <span className="text-xs text-gray-400">{formatTimestamp(node.timestamp)}</span>
      </div>
      <div className="text-xs text-gray-500">
        Args: {JSON.stringify(node.args)}
      </div>
    </div>
  )
}

function ToolProgressNode({ node }: TimelineNodeProps) {
  return (
    <div className="p-2">
      <div className="flex items-center gap-2 mb-1">
        <span className="text-xs text-gray-400">{formatTimestamp(node.timestamp)}</span>
      </div>
      <div className="text-sm text-cyan-600">
        Progress: {node.percent}% {node.message}
      </div>
    </div>
  )
}

function ToolResultNode({ node }: TimelineNodeProps) {
  return (
    <div className="p-2">
      <div className="flex items-center gap-2 mb-1">
        <span className="text-xs text-gray-400">{formatTimestamp(node.timestamp)}</span>
      </div>
      {node.synthetic && (
        <div className="text-xs text-orange-500 mb-1">Synthetic result</div>
      )}
      {node.cancelReason && (
        <div className="text-xs text-red-500 mb-1">Cancel: {node.cancelReason}</div>
      )}
      <div className="text-sm text-gray-700 whitespace-pre-wrap max-h-32 overflow-auto">
        {node.result}
      </div>
    </div>
  )
}

function EventNode({ node }: TimelineNodeProps) {
  return (
    <div className="p-2">
      <div className="flex items-center gap-2 mb-1">
        <span className="font-medium text-orange-600">{node.eventType}</span>
        <span className="text-xs text-gray-400">{formatTimestamp(node.timestamp)}</span>
      </div>
      <div className="text-xs text-gray-500">
        {JSON.stringify(node.eventData)}
      </div>
    </div>
  )
}

function CompactionNode({ node }: TimelineNodeProps) {
  return (
    <div className="p-2">
      <div className="flex items-center gap-2 mb-1">
        <span className="font-medium text-gray-600">{node.strategy}</span>
        <span className="text-xs text-gray-400">{formatTimestamp(node.timestamp)}</span>
      </div>
      <div className="text-xs text-gray-500">
        Tokens: {node.beforeTokens} {'->'} {node.afterTokens}
      </div>
      <div className="text-xs text-gray-400">
        Compacted: {node.compactedMessageIds?.length} messages
      </div>
    </div>
  )
}

function TimelineNode({ node }: TimelineNodeProps) {
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
    <div data-testid={`timeline-node-${node.nodeId}`} className="border-b border-gray-100">
      <div className="flex items-center gap-2 px-2 py-1 bg-gray-50">
        <NodeTypeBadge nodeType={node.nodeType} />
        <span className="font-mono text-xs text-gray-500">{node.nodeId}</span>
      </div>
      <Component node={node} />
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
    <div className="h-full flex flex-col">
      {/* Header */}
      <div className="p-2 border-b border-gray-200">
        <span className="text-sm font-medium text-gray-700">
          Timeline ({nodes.length} nodes)
        </span>
      </div>

      {/* Content */}
      <div className="flex-1 overflow-auto">
        {loading && (
          <div className="p-4 text-center text-gray-500">Loading...</div>
        )}
        {error && (
          <div className="p-4 text-center text-red-500">{error}</div>
        )}
        {!loading && !error && nodes.length === 0 && (
          <div className="p-4 text-center text-gray-500">No trajectory data</div>
        )}
        {!loading && !error && nodes.map(node => (
          <TimelineNode key={node.nodeId} node={node} />
        ))}
      </div>
    </div>
  )
}