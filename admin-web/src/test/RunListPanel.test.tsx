import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { RunListPanel, RunItem } from '../components/runs/RunListPanel'
import type { RunSummary } from '../types'

// Mock run data
const mockRuns: RunSummary[] = [
  {
    runId: 'run-001-abc123def456',
    userId: 'user-alice',
    status: 'RUNNING',
    turnNo: 3,
    agentType: 'MainAgent',
    parentRunId: null,
    parentLinkStatus: null,
    primaryProvider: 'deepseek',
    fallbackProvider: 'qwen',
    model: 'deepseek-reasoner',
    maxTurns: 10,
    startedAt: '2025-05-01T10:00:00Z',
    updatedAt: '2025-05-01T10:30:00Z',
    completedAt: null,
    lastError: null,
  },
  {
    runId: 'run-002-xyz789',
    userId: 'user-bob',
    status: 'SUCCEEDED',
    turnNo: 5,
    agentType: 'MainAgent',
    parentRunId: null,
    parentLinkStatus: null,
    primaryProvider: 'deepseek',
    fallbackProvider: 'qwen',
    model: 'deepseek-reasoner',
    maxTurns: 10,
    startedAt: '2025-05-01T09:00:00Z',
    updatedAt: '2025-05-01T09:45:00Z',
    completedAt: '2025-05-01T09:45:00Z',
    lastError: null,
  },
  {
    runId: 'run-003-sub',
    userId: 'user-alice',
    status: 'PAUSED',
    turnNo: 2,
    agentType: 'ExploreAgent',
    parentRunId: 'run-001-abc123def456',
    parentLinkStatus: 'ATTACHED',
    primaryProvider: 'deepseek',
    fallbackProvider: 'qwen',
    model: 'deepseek-reasoner',
    maxTurns: 10,
    startedAt: '2025-05-01T10:15:00Z',
    updatedAt: '2025-05-01T10:20:00Z',
    completedAt: null,
    lastError: 'SUB_TURN_BUDGET',
  },
]

describe('RunItem', () => {
  it('should display short runId', () => {
    render(<RunItem run={mockRuns[0]} selected={false} onClick={() => {}} />)
    // First 8 chars of "run-001-abc123def456" is "run-001-"
    expect(screen.getByText('run-001-...')).toBeInTheDocument()
  })

  it('should display userId and provider/model', () => {
    render(<RunItem run={mockRuns[0]} selected={false} onClick={() => {}} />)
    expect(screen.getByText(/user-alice/)).toBeInTheDocument()
    expect(screen.getByText(/deepseek\/deepseek-reasoner/)).toBeInTheDocument()
  })

  it('should display turn number', () => {
    render(<RunItem run={mockRuns[0]} selected={false} onClick={() => {}} />)
    expect(screen.getByText('Turn 3/10')).toBeInTheDocument()
  })

  it('should display parent run info for SubAgent', () => {
    render(<RunItem run={mockRuns[2]} selected={false} onClick={() => {}} />)
    expect(screen.getByText(/Parent: run-001/)).toBeInTheDocument()
    expect(screen.getByText('(ATTACHED)')).toBeInTheDocument()
  })

  it('should display error message', () => {
    render(<RunItem run={mockRuns[2]} selected={false} onClick={() => {}} />)
    expect(screen.getByText(/SUB_TURN_BUDGET/)).toBeInTheDocument()
  })

  it('should have selected styling when selected', () => {
    render(<RunItem run={mockRuns[0]} selected={true} onClick={() => {}} />)
    const container = screen.getByTestId('run-item-run-001-abc123def456')
    expect(container.className).toContain('bg-blue-50')
  })

  it('should call onClick when clicked', () => {
    const onClick = vi.fn()
    render(<RunItem run={mockRuns[0]} selected={false} onClick={onClick} />)
    fireEvent.click(screen.getByText('run-001-...'))
    expect(onClick).toHaveBeenCalled()
  })
})

describe('RunListPanel', () => {
  it('should display loading state', () => {
    render(
      <RunListPanel
        runs={[]}
        loading={true}
        error={null}
        selectedRunId={null}
        onSelectRun={() => {}}
      />
    )
    expect(screen.getByText('Loading...')).toBeInTheDocument()
  })

  it('should display error state', () => {
    render(
      <RunListPanel
        runs={[]}
        loading={false}
        error="Failed to fetch"
        selectedRunId={null}
        onSelectRun={() => {}}
      />
    )
    expect(screen.getByText('Failed to fetch')).toBeInTheDocument()
  })

  it('should display empty state', () => {
    render(
      <RunListPanel
        runs={[]}
        loading={false}
        error={null}
        selectedRunId={null}
        onSelectRun={() => {}}
      />
    )
    expect(screen.getByText('No runs found')).toBeInTheDocument()
  })

  it('should display all runs', () => {
    render(
      <RunListPanel
        runs={mockRuns}
        loading={false}
        error={null}
        selectedRunId={null}
        onSelectRun={() => {}}
      />
    )
    // First 8 chars of each runId
    expect(screen.getByText('run-001-...')).toBeInTheDocument()
    expect(screen.getByText('run-002-...')).toBeInTheDocument()
    expect(screen.getByText('run-003-...')).toBeInTheDocument()
  })

  it('should highlight selected run', () => {
    render(
      <RunListPanel
        runs={mockRuns}
        loading={false}
        error={null}
        selectedRunId="run-001-abc123def456"
        onSelectRun={() => {}}
      />
    )
    const container = screen.getByTestId('run-item-run-001-abc123def456')
    expect(container.className).toContain('bg-blue-50')
  })

  it('should filter by status', async () => {
    const onStatusFilterChange = vi.fn()
    render(
      <RunListPanel
        runs={mockRuns}
        loading={false}
        error={null}
        selectedRunId={null}
        onSelectRun={() => {}}
        statusFilter={null}
        onStatusFilterChange={onStatusFilterChange}
      />
    )
    const select = screen.getByRole('combobox')
    fireEvent.change(select, { target: { value: 'RUNNING' } })
    expect(onStatusFilterChange).toHaveBeenCalledWith('RUNNING')
  })

  it('should filter by userId', async () => {
    const onUserIdFilterChange = vi.fn()
    render(
      <RunListPanel
        runs={mockRuns}
        loading={false}
        error={null}
        selectedRunId={null}
        onSelectRun={() => {}}
        userIdFilter={null}
        onUserIdFilterChange={onUserIdFilterChange}
      />
    )
    const input = screen.getByPlaceholderText('Filter by userId')
    fireEvent.change(input, { target: { value: 'alice' } })
    expect(onUserIdFilterChange).toHaveBeenCalledWith('alice')
  })

  it('should call onSelectRun when run is clicked', () => {
    const onSelectRun = vi.fn()
    render(
      <RunListPanel
        runs={mockRuns}
        loading={false}
        error={null}
        selectedRunId={null}
        onSelectRun={onSelectRun}
      />
    )
    fireEvent.click(screen.getByText('run-001-...'))
    expect(onSelectRun).toHaveBeenCalledWith('run-001-abc123def456')
  })
})