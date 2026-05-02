import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { RunControls } from '../components/runs/RunControls'

describe('RunControls', () => {
  const defaultProps = {
    selectedRunId: null,
    runStatus: null,
    isStreaming: false,
    onNewChat: vi.fn(),
    onRefreshRun: vi.fn(),
    onInterrupt: vi.fn(),
    onAbort: vi.fn(),
  }

  it('should render all control buttons', () => {
    render(<RunControls {...defaultProps} />)
    expect(screen.getByText('New Chat')).toBeInTheDocument()
    expect(screen.getByText('Refresh')).toBeInTheDocument()
    expect(screen.getByText('Interrupt')).toBeInTheDocument()
    expect(screen.getByText('Abort')).toBeInTheDocument()
  })

  it('should disable New Chat when streaming', () => {
    render(<RunControls {...defaultProps} isStreaming={true} />)
    expect(screen.getByText('New Chat')).toBeDisabled()
  })

  it('should enable New Chat when not streaming', () => {
    render(<RunControls {...defaultProps} />)
    expect(screen.getByText('New Chat')).not.toBeDisabled()
  })

  it('should call onNewChat when clicked', () => {
    const onNewChat = vi.fn()
    render(<RunControls {...defaultProps} onNewChat={onNewChat} />)
    fireEvent.click(screen.getByText('New Chat'))
    expect(onNewChat).toHaveBeenCalled()
  })

  it('should disable Refresh when no run selected', () => {
    render(<RunControls {...defaultProps} selectedRunId={null} />)
    expect(screen.getByText('Refresh')).toBeDisabled()
  })

  it('should enable Refresh when run selected', () => {
    render(<RunControls {...defaultProps} selectedRunId="run-001" />)
    expect(screen.getByText('Refresh')).not.toBeDisabled()
  })

  it('should disable Refresh when streaming', () => {
    render(<RunControls {...defaultProps} selectedRunId="run-001" isStreaming={true} />)
    expect(screen.getByText('Refresh')).toBeDisabled()
  })

  it('should call onRefreshRun when clicked', () => {
    const onRefreshRun = vi.fn()
    render(<RunControls {...defaultProps} selectedRunId="run-001" onRefreshRun={onRefreshRun} />)
    fireEvent.click(screen.getByText('Refresh'))
    expect(onRefreshRun).toHaveBeenCalled()
  })

  it('should disable Interrupt for terminal status SUCCEEDED', () => {
    render(<RunControls {...defaultProps} selectedRunId="run-001" runStatus="SUCCEEDED" />)
    expect(screen.getByText('Interrupt')).toBeDisabled()
  })

  it('should disable Interrupt for terminal status FAILED', () => {
    render(<RunControls {...defaultProps} selectedRunId="run-001" runStatus="FAILED" />)
    expect(screen.getByText('Interrupt')).toBeDisabled()
  })

  it('should disable Interrupt for terminal status CANCELLED', () => {
    render(<RunControls {...defaultProps} selectedRunId="run-001" runStatus="CANCELLED" />)
    expect(screen.getByText('Interrupt')).toBeDisabled()
  })

  it('should disable Interrupt for terminal status TIMEOUT', () => {
    render(<RunControls {...defaultProps} selectedRunId="run-001" runStatus="TIMEOUT" />)
    expect(screen.getByText('Interrupt')).toBeDisabled()
  })

  it('should enable Interrupt for RUNNING status', () => {
    render(<RunControls {...defaultProps} selectedRunId="run-001" runStatus="RUNNING" />)
    expect(screen.getByText('Interrupt')).not.toBeDisabled()
  })

  it('should enable Interrupt for PAUSED status', () => {
    render(<RunControls {...defaultProps} selectedRunId="run-001" runStatus="PAUSED" />)
    expect(screen.getByText('Interrupt')).not.toBeDisabled()
  })

  it('should enable Interrupt for WAITING_USER_CONFIRMATION status', () => {
    render(<RunControls {...defaultProps} selectedRunId="run-001" runStatus="WAITING_USER_CONFIRMATION" />)
    expect(screen.getByText('Interrupt')).not.toBeDisabled()
  })

  it('should disable Interrupt when streaming', () => {
    render(<RunControls {...defaultProps} selectedRunId="run-001" runStatus="RUNNING" isStreaming={true} />)
    expect(screen.getByText('Interrupt')).toBeDisabled()
  })

  it('should disable Interrupt when no run selected', () => {
    render(<RunControls {...defaultProps} selectedRunId={null} />)
    expect(screen.getByText('Interrupt')).toBeDisabled()
  })

  it('should call onInterrupt when clicked', () => {
    const onInterrupt = vi.fn()
    render(<RunControls {...defaultProps} selectedRunId="run-001" runStatus="RUNNING" onInterrupt={onInterrupt} />)
    fireEvent.click(screen.getByText('Interrupt'))
    expect(onInterrupt).toHaveBeenCalled()
  })

  it('should disable Abort for terminal statuses', () => {
    const terminalStatuses = ['SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMEOUT']
    for (const status of terminalStatuses) {
      const onAbort = vi.fn()
      const { getByText, unmount } = render(
        <RunControls {...defaultProps} selectedRunId="run-001" runStatus={status} onAbort={onAbort} />
      )
      expect(getByText('Abort')).toBeDisabled()
      unmount()
    }
  })

  it('should enable Abort for active status', () => {
    render(<RunControls {...defaultProps} selectedRunId="run-001" runStatus="RUNNING" />)
    expect(screen.getByText('Abort')).not.toBeDisabled()
  })

  it('should disable Abort when streaming', () => {
    render(<RunControls {...defaultProps} selectedRunId="run-001" runStatus="RUNNING" isStreaming={true} />)
    expect(screen.getByText('Abort')).toBeDisabled()
  })

  it('should disable Abort when no run selected', () => {
    render(<RunControls {...defaultProps} selectedRunId={null} />)
    expect(screen.getByText('Abort')).toBeDisabled()
  })

  it('should call onAbort when clicked', () => {
    const onAbort = vi.fn()
    render(<RunControls {...defaultProps} selectedRunId="run-001" runStatus="RUNNING" onAbort={onAbort} />)
    fireEvent.click(screen.getByText('Abort'))
    expect(onAbort).toHaveBeenCalled()
  })
})