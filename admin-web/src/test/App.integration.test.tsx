import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import App from '../App'
import {
  fixtureRunSummaries,
  fixtureTrajectoryNodes,
  fixtureInactiveRuntimeState,
} from './fixtures'

// Mock fetch for all API calls
const mockFetch = vi.fn()
vi.stubGlobal('fetch', mockFetch)

// Helper to render App
const renderApp = () => {
  return render(<App />)
}

// URL patterns for API endpoints
const RUNS_LIST_URL = '/api/admin/console/runs'

describe('App Integration', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    // Clear localStorage to prevent state carry-over
    localStorage.clear()
    // Default: empty responses
    mockFetch.mockImplementation(() =>
      Promise.resolve({
        ok: true,
        json: () => Promise.resolve([]),
      })
    )
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  describe('Run List Flow', () => {
    it('should load and display run list', async () => {
      mockFetch.mockImplementation((url: string) => {
        if (url.startsWith(RUNS_LIST_URL)) {
          return Promise.resolve({
            ok: true,
            json: () => Promise.resolve(fixtureRunSummaries),
          })
        }
        return Promise.resolve({ ok: true, json: () => Promise.resolve([]) })
      })

      renderApp()

      await waitFor(() => {
        expect(screen.getByText('Agent Buyer Console')).toBeInTheDocument()
      })

      // Wait for runs to load - any run-cons text should appear
      await waitFor(() => {
        const runElements = screen.getAllByText(/run-cons/)
        expect(runElements.length).toBeGreaterThan(0)
      }, { timeout: 3000 })
    })
  })

  describe('Timeline Display', () => {
    it('should display timeline nodes after selecting a run', async () => {
      mockFetch.mockImplementation((url: string) => {
        if (url.startsWith(RUNS_LIST_URL)) {
          return Promise.resolve({
            ok: true,
            json: () => Promise.resolve(fixtureRunSummaries),
          })
        }
        if (url.includes('/api/agent/runs/') && url.endsWith('/runtime-state') === false && !url.includes('/messages')) {
          return Promise.resolve({
            ok: true,
            json: () => Promise.resolve({
              runId: 'run-console-demo-001',
              nodes: fixtureTrajectoryNodes,
            }),
          })
        }
        if (url.includes('/runtime-state')) {
          return Promise.resolve({
            ok: true,
            json: () => Promise.resolve(fixtureInactiveRuntimeState),
          })
        }
        return Promise.resolve({ ok: true, json: () => Promise.resolve([]) })
      })

      renderApp()

      await waitFor(() => {
        expect(screen.getByTestId('run-item-run-console-demo-001')).toBeInTheDocument()
      }, { timeout: 3000 })

      // Click on the run to select it
      const runItem = screen.getByTestId('run-item-run-console-demo-001')
      fireEvent.click(runItem)

      // Wait for timeline to show user message
      await waitFor(() => {
        expect(screen.getByText('取消我昨天的订单')).toBeInTheDocument()
      }, { timeout: 3000 })
    })
  })

  describe('Debug Drawer', () => {
    it('should show debug drawer when debug button clicked after selecting run', async () => {
      mockFetch.mockImplementation((url: string) => {
        if (url.startsWith(RUNS_LIST_URL)) {
          return Promise.resolve({
            ok: true,
            json: () => Promise.resolve(fixtureRunSummaries),
          })
        }
        if (url.includes('/api/agent/runs/') && !url.includes('/messages') && !url.includes('/runtime-state')) {
          return Promise.resolve({
            ok: true,
            json: () => Promise.resolve({
              runId: 'run-console-demo-001',
              nodes: fixtureTrajectoryNodes,
            }),
          })
        }
        if (url.includes('/runtime-state')) {
          return Promise.resolve({
            ok: true,
            json: () => Promise.resolve(fixtureInactiveRuntimeState),
          })
        }
        return Promise.resolve({ ok: true, json: () => Promise.resolve([]) })
      })

      renderApp()

      // Wait for runs to load
      await waitFor(() => {
        expect(screen.getByTestId('run-item-run-console-demo-001')).toBeInTheDocument()
      }, { timeout: 3000 })

      // Select a run first
      fireEvent.click(screen.getByTestId('run-item-run-console-demo-001'))

      // Wait for trajectory to load (indicates run is selected)
      await waitFor(() => {
        expect(screen.getByText('取消我昨天的订单')).toBeInTheDocument()
      }, { timeout: 3000 })

      // Click Debug button
      const debugButton = screen.getByRole('button', { name: /Debug/ })
      fireEvent.click(debugButton)

      // Debug View drawer should appear
      await waitFor(() => {
        expect(screen.getByText('Debug View')).toBeInTheDocument()
      }, { timeout: 3000 })
    })
  })

  describe('Chat Input', () => {
    it('should show chat input after selecting a run', async () => {
      mockFetch.mockImplementation((url: string) => {
        if (url.startsWith(RUNS_LIST_URL)) {
          return Promise.resolve({
            ok: true,
            json: () => Promise.resolve(fixtureRunSummaries),
          })
        }
        if (url.includes('/api/agent/runs/') && !url.includes('/messages') && !url.includes('/runtime-state')) {
          return Promise.resolve({
            ok: true,
            json: () => Promise.resolve({
              runId: 'run-console-demo-001',
              nodes: fixtureTrajectoryNodes,
            }),
          })
        }
        if (url.includes('/runtime-state')) {
          return Promise.resolve({
            ok: true,
            json: () => Promise.resolve(fixtureInactiveRuntimeState),
          })
        }
        return Promise.resolve({ ok: true, json: () => Promise.resolve([]) })
      })

      renderApp()

      await waitFor(() => {
        expect(screen.getByTestId('run-item-run-console-demo-001')).toBeInTheDocument()
      }, { timeout: 3000 })

      fireEvent.click(screen.getByTestId('run-item-run-console-demo-001'))

      // Chat input should be visible
      await waitFor(() => {
        expect(screen.getByPlaceholderText(/输入消息/)).toBeInTheDocument()
      }, { timeout: 3000 })
    })
  })

  describe('Error Handling', () => {
    it('should show error state on fetch failure', async () => {
      mockFetch.mockImplementation((url: string) => {
        if (url.startsWith(RUNS_LIST_URL)) {
          return Promise.resolve({
            ok: false,
            status: 500,
            statusText: 'Internal Server Error',
          })
        }
        return Promise.resolve({ ok: true, json: () => Promise.resolve([]) })
      })

      renderApp()

      await waitFor(() => {
        expect(screen.getByText('Agent Buyer Console')).toBeInTheDocument()
      })

      // Should show error state in runs panel
      await waitFor(() => {
        expect(screen.getByText(/listRuns failed: 500/i)).toBeInTheDocument()
      }, { timeout: 3000 })
    })
  })
})