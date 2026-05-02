import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { DebugDrawer } from '../components/debug/DebugDrawer'
import type { RuntimeState } from '../types'

// Mock runtime state matching backend AdminRuntimeStateDto format
// Backend returns entries with short keys: meta, queue, tools, leases, children, todos, todo-reminder
const mockRuntimeState: RuntimeState = {
  runId: 'run-001-abc',
  activeRun: true,
  entries: {
    meta: { status: 'RUNNING', userId: 'demo' },
    queue: { 'tc-001': '1700000000', 'tc-002': '1700000001' },
    tools: { 'tc-001': '{"status":"WAITING","toolName":"query_order"}', 'tc-002': '{"status":"PENDING"}' },
    leases: { 'tc-001': '1700000000' },
    children: { 'run-002-sub': '{"ref":"sub-agent"}' },
    todos: { 'step_1': '{"title":"Query order"}', 'step_2': '{"title":"Cancel order"}' },
    'todo-reminder': '{"turnNo":6,"steps":[]}',
    // Sensitive field in meta that should be redacted
    other: { confirmToken: 'secret-confirm-token-abc123' },
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
    // EntryGroup renders key with colon suffix
    expect(screen.getByText(/status:/)).toBeInTheDocument()
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

  it('should display Children group', () => {
    render(<DebugDrawer runtimeState={mockRuntimeState} loading={false} error={null} onClose={() => {}} />)
    expect(screen.getByText('Children')).toBeInTheDocument()
  })

  it('should display Todos group', () => {
    render(<DebugDrawer runtimeState={mockRuntimeState} loading={false} error={null} onClose={() => {}} />)
    expect(screen.getByText('Todos')).toBeInTheDocument()
    expect(screen.getByText('Todo Reminder')).toBeInTheDocument()
  })

  it('should NOT display confirmToken in Other group (redacted)', () => {
    render(<DebugDrawer runtimeState={mockRuntimeState} loading={false} error={null} onClose={() => {}} />)
    // confirmToken in other group should be redacted
    expect(screen.queryByText('secret-confirm-token-abc123')).not.toBeInTheDocument()
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