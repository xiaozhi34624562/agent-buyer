import { useState, useEffect, useCallback, useRef } from 'react'
import { ConsoleShell } from './components/console/ConsoleShell'
import { RunListPanel } from './components/runs/RunListPanel'
import { TimelinePanel } from './components/timeline/TimelinePanel'
import { DebugDrawer } from './components/debug/DebugDrawer'
import { ChatPanel } from './components/chat/ChatPanel'
import { useRunList } from './hooks/useRunList'
import { useRunDetail } from './hooks/useRunDetail'
import { useRuntimeState } from './hooks/useRuntimeState'
import { useChatStream } from './hooks/useChatStream'
import { useChatMessages } from './hooks/useChatMessages'
import { readSseStream } from './api/sseParser'
import { DEFAULT_ALLOWED_TOOL_NAMES, DEFAULT_LLM_PARAMS } from './api/agentApi'
import type { SseEvent } from './types'

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
  const chatStream = useChatStream()

  // Handle SSE events from chat messages
  const handleSseEvent = useCallback((event: SseEvent) => {
    chatStream.processEvent(event)

    // On tool events, refresh runtime state
    if (event.type === 'tool_use' || event.type === 'tool_result' || event.type === 'tool_progress') {
      if (chatStream.state.runId) {
        runtimeState.fetchRuntimeState(chatStream.state.runId)
      }
    }

    // On final, refresh run list and trajectory
    if (event.type === 'final') {
      runList.refresh()
      if (chatStream.state.runId) {
        runDetail.fetchTrajectory(chatStream.state.runId)
        runtimeState.fetchRuntimeState(chatStream.state.runId)
      }
    }
  }, [chatStream, runtimeState, runList, runDetail])

  const chatMessages = useChatMessages({ userId, onEvent: handleSseEvent })

  // Track which run's trajectory has been processed to avoid re-processing
  const processedRunIdRef = useRef<string | null>(null)

  // When trajectory loads for a terminal run, build chat messages from it
  useEffect(() => {
    if (runDetail.nodes.length > 0 && runList.selectedRunId && !runDetail.loading) {
      // Only process if this run hasn't been processed yet
      if (processedRunIdRef.current !== runList.selectedRunId) {
        const run = runList.runs.find(r => r.runId === runList.selectedRunId)
        if (run && TERMINAL_STATUSES.includes(run.status)) {
          processedRunIdRef.current = runList.selectedRunId
          chatMessages.setMessagesFromTrajectory(runDetail.nodes)
        }
      }
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [runDetail.nodes, runDetail.loading, runList.selectedRunId, runList.runs])

  // Create new run
  const handleCreateRun = useCallback(async (prompt: string) => {
    // Reset processed run tracking for new run
    processedRunIdRef.current = null

    // Add user message to chat history immediately
    chatMessages.addUserMessage(prompt)

    try {
      const response = await fetch('/api/agent/runs', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-User-Id': userId,
        },
        body: JSON.stringify({
          messages: [{ role: 'user', content: prompt }],
          allowedToolNames: DEFAULT_ALLOWED_TOOL_NAMES,
          llmParams: DEFAULT_LLM_PARAMS,
        }),
      })

      if (!response.ok) {
        throw new Error(`createRun failed: ${response.status}`)
      }

      // Start streaming - use local variable to track runId since React state is async
      let currentRunId: string | null = null
      chatStream.startStream('pending')

      // Use unified SSE stream reader
      for await (const event of readSseStream(response)) {
        handleSseEvent(event)

        // Set runId on first event - use local variable to avoid stale closure
        if (event.runId && !currentRunId && event.runId !== 'pending') {
          currentRunId = event.runId
          chatStream.startStream(event.runId)
          runList.setSelectedRunId(event.runId)
          runList.refresh()
        }
      }
    } catch (error) {
      handleSseEvent({ type: 'error', error: (error as Error).message })
    }
  }, [userId, chatStream, runList, handleSseEvent])

  // Terminal statuses that don't need streaming
  const TERMINAL_STATUSES = ['SUCCEEDED', 'FAILED', 'CANCELLED', 'TIMEOUT']

  // Handle run selection
  const handleSelectRun = useCallback((runId: string) => {
    // Reset processed run tracking
    processedRunIdRef.current = null

    runList.setSelectedRunId(runId)
    runDetail.fetchTrajectory(runId)
    runtimeState.fetchRuntimeState(runId)
    chatStream.resetState()
    chatMessages.clearMessages()

    // Check if run is already completed - don't start streaming for terminal runs
    const run = runList.runs.find(r => r.runId === runId)
    if (run && TERMINAL_STATUSES.includes(run.status)) {
      // Set status directly without streaming
      chatStream.setRunStatus(runId, run.status)
    } else {
      // For active runs, start streaming
      chatStream.startStream(runId)
    }
  }, [runList, runDetail, runtimeState, chatStream, chatMessages])

  // Refresh handler
  const handleRefresh = useCallback(() => {
    runList.refresh()
    if (runList.selectedRunId) {
      runDetail.fetchTrajectory(runList.selectedRunId)
      runtimeState.fetchRuntimeState(runList.selectedRunId)
    }
  }, [runList, runDetail, runtimeState])

  // New Chat handler
  const handleNewChat = useCallback(() => {
    chatStream.resetState()
    chatMessages.clearMessages()
    runList.setSelectedRunId(null)
  }, [chatStream, chatMessages, runList])

  // Interrupt handler
  const handleInterrupt = useCallback(async () => {
    if (!runList.selectedRunId) return
    await fetch(`/api/agent/runs/${runList.selectedRunId}/interrupt`, {
      method: 'POST',
      headers: { 'X-User-Id': userId },
    })
    runList.refresh()
  }, [userId, runList])

  // Abort handler
  const handleAbort = useCallback(async () => {
    if (!runList.selectedRunId) return
    await fetch(`/api/agent/runs/${runList.selectedRunId}/abort`, {
      method: 'POST',
      headers: { 'X-User-Id': userId },
    })
    runList.refresh()
  }, [userId, runList])

  // Confirm handler
  const handleConfirm = useCallback(() => {
    if (chatStream.state.runId) {
      chatMessages.sendConfirmation(chatStream.state.runId, true)
    }
  }, [chatStream.state.runId, chatMessages])

  // Reject handler
  const handleReject = useCallback(() => {
    if (chatStream.state.runId) {
      chatMessages.sendConfirmation(chatStream.state.runId, false)
    }
  }, [chatStream.state.runId, chatMessages])

  // Send message handler (for PAUSED or new prompt)
  const handleSendMessage = useCallback((content: string) => {
    if (chatStream.state.runId && chatStream.state.runId !== 'pending') {
      chatMessages.sendMessage(chatStream.state.runId, content)
    } else {
      handleCreateRun(content)
    }
  }, [chatStream.state.runId, chatMessages, handleCreateRun])

  // Close debug drawer
  const handleCloseDebug = useCallback(() => {
    setDebug(false)
  }, [])

  // Build panels - NO extra Panel wrapper, components handle their own Panel
  const runsPanel = (
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
  )

  const timelinePanel = (
    <TimelinePanel
      nodes={runDetail.nodes}
      loading={runDetail.loading}
      error={runDetail.error}
    />
  )

  const chatPanel = (
    <ChatPanel
      assistantDraft={chatStream.state.assistantDraft}
      toolCards={chatStream.state.toolCards}
      isStreaming={chatStream.state.isStreaming}
      runStatus={chatStream.state.runStatus}
      nextActionRequired={chatStream.state.nextActionRequired}
      messages={chatMessages.messages}
      onSendMessage={handleSendMessage}
      onConfirm={handleConfirm}
      onReject={handleReject}
      onStopStream={chatStream.stopStream}
      // Pass controls to ChatPanel
      selectedRunId={runList.selectedRunId}
      onNewChat={handleNewChat}
      onRefreshRun={handleRefresh}
      onInterrupt={handleInterrupt}
      onAbort={handleAbort}
    />
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