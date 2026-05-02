import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { DebugDrawer } from '../components/debug/DebugDrawer'
import type { RuntimeState } from '../types'

// Mock runtime state with various entry types
const mockRuntimeState: RuntimeState = {
  runId: 'run-001-abc',
  activeRun: true,
  entries: {
    'agent:run:run-001-abc:meta': { status: 'RUNNING', userId: 'demo' },
    'agent:run:run-001-abc:queue': ['tc-001', 'tc-002'],
    'agent:run:run-001-abc:tools:tc-001': { status: 'WAITING', toolName: 'query_order' },
    'agent:run:run-001-abc:lease:tc-001': { leaseHolder: 'instance-1', expireAt: 1234567890 },
    'agent:run:run-001-abc:control': { abort_requested: false },
    'abort_requested': false,
    'interrupt_requested': true,
    'agent:run:run-001-abc:children': ['run-002-sub'],
    'agent:run:run-001-abc:todos': ['todo-001', 'todo-002'],
    'todo-reminder': 'Check pending todos',
    // Should NOT appear in full set
    'agent:active-runs': 'run-001,run-002,run-003',
    // Sensitive field that should be redacted
    'confirmToken': 'secret-confirm-token-abc123',
  },
}

const inactiveRuntimeState: RuntimeState = {
  runId: 'run-002-xyz',
  activeRun: false,
  entries: {},
}

describe('DebugDrawer', () => {
  it('should not render when no state, loading, or error', () => {
    render(<DebugDrawer runtimeState={null} loading={false} error={null} onClose={() => {}} />)
    expect(screen.queryByText('Debug View')).not.toBeInTheDocument()
  })

  it('should display loading state', () => {
    render(<DebugDrawer runtimeState={null} loading={true} error={null} onClose={() => {}} />)
    expect(screen.getByText('Loading...')).toBeInTheDocument()
  })

  it('should display error state', () => {
    render(<DebugDrawer runtimeState={null} loading={false} error="Failed to fetch" onClose={() => {}} />)
    expect(screen.getByText('Failed to fetch')).toBeInTheDocument()
  })

  it('should display active run indicator', () => {
    render(<DebugDrawer runtimeState={mockRuntimeState} loading={false} error={null} onClose={() => {}} />)
    expect(screen.getByText('● Active Run')).toBeInTheDocument()
  })

  it('should display inactive run indicator', () => {
    render(<DebugDrawer runtimeState={inactiveRuntimeState} loading={false} error={null} onClose={() => {}} />)
    expect(screen.getByText('○ Not Active')).toBeInTheDocument()
  })

  it('should display runId in footer', () => {
    render(<DebugDrawer runtimeState={mockRuntimeState} loading={false} error={null} onClose={() => {}} />)
    expect(screen.getByText(/Run: run-001-abc/)).toBeInTheDocument()
  })

  it('should display Meta group', () => {
    render(<DebugDrawer runtimeState={mockRuntimeState} loading={false} error={null} onClose={() => {}} />)
    expect(screen.getByText('Meta')).toBeInTheDocument()
    expect(screen.getByText(/agent:run:run-001-abc:meta/)).toBeInTheDocument()
  })

  it('should display Queue group', () => {
    render(<DebugDrawer runtimeState={mockRuntimeState} loading={false} error={null} onClose={() => {}} />)
    expect(screen.getByText('Queue')).toBeInTheDocument()
  })

  it('should display Tools group', () => {
    render(<DebugDrawer runtimeState={mockRuntimeState} loading={false} error={null} onClose={() => {}} />)
    expect(screen.getByText('Tools')).toBeInTheDocument()
  })

  it('should display Leases group', () => {
    render(<DebugDrawer runtimeState={mockRuntimeState} loading={false} error={null} onClose={() => {}} />)
    expect(screen.getByText('Leases')).toBeInTheDocument()
  })

  it('should display Control group', () => {
    render(<DebugDrawer runtimeState={mockRuntimeState} loading={false} error={null} onClose={() => {}} />)
    expect(screen.getByText('Control')).toBeInTheDocument()
  })

  it('should display Children group', () => {
    render(<DebugDrawer runtimeState={mockRuntimeState} loading={false} error={null} onClose={() => {}} />)
    expect(screen.getByText('Children')).toBeInTheDocument()
  })

  it('should display Todos group', () => {
    render(<DebugDrawer runtimeState={mockRuntimeState} loading={false} error={null} onClose={() => {}} />)
    expect(screen.getByText('Todos')).toBeInTheDocument()
    expect(screen.getByText(/todo-reminder/)).toBeInTheDocument()
  })

  it('should NOT display full agent:active-runs set', () => {
    render(<DebugDrawer runtimeState={mockRuntimeState} loading={false} error={null} onClose={() => {}} />)
    // The agent:active-runs entry exists but should not be displayed (it's not grouped)
    expect(screen.queryByText(/run-001,run-002,run-003/)).not.toBeInTheDocument()
  })

  it('should not display confirmToken (ungrouped sensitive field)', () => {
    render(<DebugDrawer runtimeState={mockRuntimeState} loading={false} error={null} onClose={() => {}} />)
    // confirmToken doesn't match any group pattern, so it's not displayed at all
    expect(screen.queryByText('secret-confirm-token-abc123')).not.toBeInTheDocument()
    expect(screen.queryByText('confirmToken')).not.toBeInTheDocument()
  })

  it('should call onClose when close button clicked', () => {
    const onClose = vi.fn()
    render(<DebugDrawer runtimeState={mockRuntimeState} loading={false} error={null} onClose={onClose} />)
    fireEvent.click(screen.getByText('Close'))
    expect(onClose).toHaveBeenCalled()
  })

  it('should handle missing entries gracefully', () => {
    const emptyState: RuntimeState = {
      runId: 'run-empty',
      activeRun: false,
      entries: {},
    }
    render(<DebugDrawer runtimeState={emptyState} loading={false} error={null} onClose={() => {}} />)
    // Should render without errors, just showing the runId footer
    expect(screen.getByText(/Run: run-empty/)).toBeInTheDocument()
  })
})