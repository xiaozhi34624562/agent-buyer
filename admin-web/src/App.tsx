import { useState, useEffect, useCallback } from 'react'
import { ConsoleShell } from './components/console/ConsoleShell'
import { Panel } from './components/ui/Panel'
import { RunListPanel } from './components/runs/RunListPanel'
import { TimelinePanel } from './components/timeline/TimelinePanel'
import { DebugDrawer } from './components/debug/DebugDrawer'
import { useRunList } from './hooks/useRunList'
import { useRunDetail } from './hooks/useRunDetail'
import { useRuntimeState } from './hooks/useRuntimeState'

function App() {
  const [userId, setUserId] = useState(() => localStorage.getItem('userId') || 'demo-user')
  const [adminToken, setAdminToken] = useState(() => localStorage.getItem('adminToken') || '')
  const [debug, setDebug] = useState(() => localStorage.getItem('debug') === 'true')

  // Persist to localStorage
  useEffect(() => {
    localStorage.setItem('userId', userId)
  }, [userId])

  useEffect(() => {
    localStorage.setItem('adminToken', adminToken)
  }, [adminToken])

  useEffect(() => {
    localStorage.setItem('debug', String(debug))
  }, [debug])

  // Hooks for data
  const runList = useRunList({ adminToken })
  const runDetail = useRunDetail({ userId })
  const runtimeState = useRuntimeState({ adminToken })

  // Handle run selection
  const handleSelectRun = useCallback((runId: string) => {
    runList.setSelectedRunId(runId)
    runDetail.fetchTrajectory(runId)
    runtimeState.fetchRuntimeState(runId)
  }, [runList, runDetail, runtimeState])

  // Refresh handler
  const handleRefresh = useCallback(() => {
    runList.refresh()
    if (runList.selectedRunId) {
      runDetail.fetchTrajectory(runList.selectedRunId)
      runtimeState.fetchRuntimeState(runList.selectedRunId)
    }
  }, [runList, runDetail, runtimeState])

  // Close debug drawer
  const handleCloseDebug = useCallback(() => {
    setDebug(false)
  }, [])

  // Build panels
  const runsPanel = (
    <Panel title="Runs">
      <RunListPanel
        runs={runList.runs}
        loading={runList.loading}
        error={runList.error}
        selectedRunId={runList.selectedRunId}
        onSelectRun={handleSelectRun}
        statusFilter={runList.statusFilter}
        userIdFilter={runList.userIdFilter}
        onStatusFilterChange={runList.setStatusFilter}
        onUserIdFilterChange={runList.setUserIdFilter}
      />
    </Panel>
  )

  const timelinePanel = (
    <Panel title="Timeline">
      <TimelinePanel
        nodes={runDetail.nodes}
        loading={runDetail.loading}
        error={runDetail.error}
      />
    </Panel>
  )

  const chatPanel = (
    <Panel title="Chat">
      <div className="text-gray-500">Chat loading...</div>
    </Panel>
  )

  return (
    <>
      <ConsoleShell
        userId={userId}
        adminToken={adminToken}
        onUserIdChange={setUserId}
        onAdminTokenChange={setAdminToken}
        onRefresh={handleRefresh}
        loading={runList.loading}
        debug={debug}
        onDebugChange={setDebug}
        runsPanel={runsPanel}
        timelinePanel={timelinePanel}
        chatPanel={chatPanel}
      />
      {debug && runList.selectedRunId && (
        <DebugDrawer
          runtimeState={runtimeState.runtimeState}
          loading={runtimeState.loading}
          error={runtimeState.error}
          onClose={handleCloseDebug}
        />
      )}
    </>
  )
}

export default App