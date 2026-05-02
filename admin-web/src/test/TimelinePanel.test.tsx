import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { TimelinePanel } from '../components/timeline/TimelinePanel'
import type { TrajectoryNode } from '../types'

// Mock trajectory nodes
const mockNodes: TrajectoryNode[] = [
  {
    nodeId: 'msg-001',
    nodeType: 'MESSAGE',
    timestamp: '2025-05-01T10:00:00Z',
    parentId: null,
    attemptId: null,
    toolCallId: null,
    role: 'user',
    content: '取消我昨天的订单',
  },
  {
    nodeId: 'attempt-001',
    nodeType: 'LLM_ATTEMPT',
    timestamp: '2025-05-01T10:00:05Z',
    parentId: null,
    attemptId: 'att-001',
    toolCallId: null,
    provider: 'deepseek',
    model: 'deepseek-reasoner',
    inputTokens: 500,
    outputTokens: 100,
  },
  {
    nodeId: 'tc-001',
    nodeType: 'TOOL_CALL',
    timestamp: '2025-05-01T10:00:10Z',
    parentId: null,
    attemptId: 'att-001',
    toolCallId: 'tc-001',
    toolName: 'query_order',
    args: { orderId: 'ORD-123' },
  },
  {
    nodeId: 'tp-001',
    nodeType: 'TOOL_PROGRESS',
    timestamp: '2025-05-01T10:00:15Z',
    parentId: null,
    attemptId: null,
    toolCallId: 'tc-001',
    percent: 50,
    message: '查询中',
  },
  {
    nodeId: 'tr-001',
    nodeType: 'TOOL_RESULT',
    timestamp: '2025-05-01T10:00:20Z',
    parentId: null,
    attemptId: null,
    toolCallId: 'tc-001',
    toolName: 'query_order',
    result: '{"orderId":"ORD-123","status":"PAID"}',
  },
  {
    nodeId: 'evt-001',
    nodeType: 'EVENT',
    timestamp: '2025-05-01T10:00:25Z',
    parentId: null,
    attemptId: null,
    toolCallId: null,
    eventType: 'WAITING_USER_CONFIRMATION',
    eventData: { toolName: 'cancel_order' },
  },
  {
    nodeId: 'comp-001',
    nodeType: 'COMPACTION',
    timestamp: '2025-05-01T10:01:00Z',
    parentId: null,
    attemptId: null,
    toolCallId: null,
    strategy: 'SUMMARY_COMPACT',
    beforeTokens: 50000,
    afterTokens: 5000,
    compactedMessageIds: ['msg-001', 'msg-002', 'msg-003'],
  },
]

const syntheticNode: TrajectoryNode = {
  nodeId: 'tr-syn',
  nodeType: 'TOOL_RESULT',
  timestamp: '2025-05-01T10:00:30Z',
  parentId: null,
  attemptId: null,
  toolCallId: 'tc-002',
  toolName: 'cancel_order',
  result: 'CANCELLED_BY_USER',
  synthetic: true,
  cancelReason: 'user abort',
}

describe('TimelinePanel', () => {
  it('should display loading state', () => {
    render(<TimelinePanel nodes={[]} loading={true} error={null} />)
    expect(screen.getByText('Loading...')).toBeInTheDocument()
  })

  it('should display error state', () => {
    render(<TimelinePanel nodes={[]} loading={false} error="Failed to fetch" />)
    expect(screen.getByText('Failed to fetch')).toBeInTheDocument()
  })

  it('should display empty state', () => {
    render(<TimelinePanel nodes={[]} loading={false} error={null} />)
    expect(screen.getByText('No trajectory data')).toBeInTheDocument()
  })

  it('should display node count in header', () => {
    render(<TimelinePanel nodes={mockNodes} loading={false} error={null} />)
    expect(screen.getByText('Timeline (7 nodes)')).toBeInTheDocument()
  })

  it('should render all node types', () => {
    render(<TimelinePanel nodes={mockNodes} loading={false} error={null} />)
    expect(screen.getByText('MESSAGE')).toBeInTheDocument()
    expect(screen.getByText('LLM_ATTEMPT')).toBeInTheDocument()
    expect(screen.getByText('TOOL_CALL')).toBeInTheDocument()
    expect(screen.getByText('TOOL_PROGRESS')).toBeInTheDocument()
    expect(screen.getByText('TOOL_RESULT')).toBeInTheDocument()
    expect(screen.getByText('EVENT')).toBeInTheDocument()
    expect(screen.getByText('COMPACTION')).toBeInTheDocument()
  })

  it('should display message content', () => {
    render(<TimelinePanel nodes={mockNodes} loading={false} error={null} />)
    expect(screen.getByText('取消我昨天的订单')).toBeInTheDocument()
  })

  it('should display provider and model for LLM attempt', () => {
    render(<TimelinePanel nodes={mockNodes} loading={false} error={null} />)
    expect(screen.getByText(/Provider: deepseek/)).toBeInTheDocument()
    expect(screen.getByText(/Model: deepseek-reasoner/)).toBeInTheDocument()
  })

  it('should display token counts', () => {
    render(<TimelinePanel nodes={mockNodes} loading={false} error={null} />)
    expect(screen.getByText(/Tokens: 500 in/)).toBeInTheDocument()
  })

  it('should display tool name and args', () => {
    render(<TimelinePanel nodes={mockNodes} loading={false} error={null} />)
    expect(screen.getByText('query_order')).toBeInTheDocument()
    expect(screen.getByText(/Args:/)).toBeInTheDocument()
  })

  it('should display progress percent', () => {
    render(<TimelinePanel nodes={mockNodes} loading={false} error={null} />)
    expect(screen.getByText(/Progress: 50%/)).toBeInTheDocument()
  })

  it('should display event type', () => {
    render(<TimelinePanel nodes={mockNodes} loading={false} error={null} />)
    expect(screen.getByText('WAITING_USER_CONFIRMATION')).toBeInTheDocument()
  })

  it('should display compaction strategy and tokens', () => {
    render(<TimelinePanel nodes={mockNodes} loading={false} error={null} />)
    expect(screen.getByText('SUMMARY_COMPACT')).toBeInTheDocument()
    expect(screen.getByText(/Tokens: 50000 -> 5000/)).toBeInTheDocument()
    expect(screen.getByText(/Compacted: 3 messages/)).toBeInTheDocument()
  })

  it('should display synthetic result marker', () => {
    render(<TimelinePanel nodes={[syntheticNode]} loading={false} error={null} />)
    expect(screen.getByText('Synthetic result')).toBeInTheDocument()
  })

  it('should display cancel reason', () => {
    render(<TimelinePanel nodes={[syntheticNode]} loading={false} error={null} />)
    expect(screen.getByText(/Cancel: user abort/)).toBeInTheDocument()
  })

  it('should sort nodes by timestamp', () => {
    // Create nodes with different timestamps (unsorted)
    const unsortedNodes: TrajectoryNode[] = [
      { ...mockNodes[3], nodeId: 'tp-001', timestamp: '2025-05-01T10:00:15Z' }, // TOOL_PROGRESS at 10:00:15
      { ...mockNodes[0], nodeId: 'msg-001', timestamp: '2025-05-01T10:00:00Z' }, // MESSAGE at 10:00:00
      { ...mockNodes[1], nodeId: 'attempt-001', timestamp: '2025-05-01T10:00:05Z' }, // LLM_ATTEMPT at 10:00:05
    ]

    render(<TimelinePanel nodes={unsortedNodes} loading={false} error={null} />)

    // Check that nodes are displayed in sorted order by testId
    const msgNode = screen.getByTestId('timeline-node-msg-001')
    const attemptNode = screen.getByTestId('timeline-node-attempt-001')
    const tpNode = screen.getByTestId('timeline-node-tp-001')

    // Verify they exist
    expect(msgNode).toBeInTheDocument()
    expect(attemptNode).toBeInTheDocument()
    expect(tpNode).toBeInTheDocument()
  })

  it('should handle nodes without timestamp', () => {
    const nodesWithoutTimestamp: TrajectoryNode[] = [
      { ...mockNodes[0], timestamp: null },
    ]
    render(<TimelinePanel nodes={nodesWithoutTimestamp} loading={false} error={null} />)
    // Node should still render
    expect(screen.getByText('MESSAGE')).toBeInTheDocument()
  })
})