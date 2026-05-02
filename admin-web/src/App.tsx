import { useState, useEffect, useCallback } from 'react'
import { ConsoleShell } from './components/console/ConsoleShell'
import { Panel } from './components/ui/Panel'
import { RunListPanel } from './components/runs/RunListPanel'
import { RunControls } from './components/runs/RunControls'
import { TimelinePanel } from './components/timeline/TimelinePanel'
import { DebugDrawer } from './components/debug/DebugDrawer'
import { ChatPanel } from './components/chat/ChatPanel'
import { useRunList } from './hooks/useRunList'
import { useRunDetail } from './hooks/useRunDetail'
import { useRuntimeState } from './hooks/useRuntimeState'
import { useChatStream } from './hooks/useChatStream'
import { useChatMessages } from './hooks/useChatMessages'
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

  // Create new run
  const handleCreateRun = useCallback(async (prompt: string) => {
    try {
      const response = await fetch('/api/agent/runs', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-User-Id': userId,
        },
        body: JSON.stringify({
          messages: [{ role: 'user', content: prompt }],
          allowedToolNames: ['query_order', 'cancel_order', 'skill_list', 'skill_view', 'agent_tool', 'todo_create', 'todo_write'],
          llmParams: {
            model: 'deepseek-reasoner',
            temperature: 0.2,
            maxTokens: 4096,
          },
        }),
      })

      if (!response.ok) {
        throw new Error(`createRun failed: ${response.status}`)
      }

      // Process SSE stream for create run
      const reader = response.body?.getReader()
      if (!reader) return

      const decoder = new TextDecoder()
      let buffer = ''

      // Start streaming
      chatStream.startStream('pending')

      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        buffer += decoder.decode(value, { stream: true })
        const lines = buffer.split('\n')
        buffer = lines.pop() ?? ''

        for (const line of lines) {
          if (line.startsWith('data:')) {
            const data = line.slice(5).trim()
            if (data === '' || data === 'ping') continue

            try {
              const event = JSON.parse(data) as SseEvent
              // Normalize toolName
              if (event.type === 'tool_use' && 'name' in event && !event.toolName) {
                event.toolName = (event as Record<string, unknown>).name as string
              }
              handleSseEvent(event)

              // Set runId on first event
              if (event.runId && chatStream.state.runId === 'pending') {
                chatStream.startStream(event.runId)
                runList.setSelectedRunId(event.runId)
                runList.refresh()
              }
            } catch {
              // Skip invalid JSON
            }
          }
        }
      }
    } catch (error) {
      handleSseEvent({ type: 'error', error: (error as Error).message })
    }
  }, [userId, chatStream, runList, handleSseEvent])

  // Handle run selection
  const handleSelectRun = useCallback((runId: string) => {
    runList.setSelectedRunId(runId)
    runDetail.fetchTrajectory(runId)
    runtimeState.fetchRuntimeState(runId)
    chatStream.resetState()
    chatStream.startStream(runId)
    chatMessages.clearMessages()
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
  }, [chatStream, chatMessages])

  // Interrupt handler
  const handleInterrupt = useCallback(async () => {
    if (!runList.selectedRunId) return
    await fetch(`/api/agent/runs/${runList.selectedRunId}/interrupt`, {
      method: 'POST',
      headers: { 'X-User-Id': userId },
    })
    runList.refresh()
  }, [runList.selectedRunId, userId, runList])

  // Abort handler
  const handleAbort = useCallback(async () => {
    if (!runList.selectedRunId) return
    await fetch(`/api/agent/runs/${runList.selectedRunId}/abort`, {
      method: 'POST',
      headers: { 'X-User-Id': userId },
    })
    runList.refresh()
  }, [runList.selectedRunId, userId, runList])

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

  // Build panels
  const runsPanel = (
    <Panel title="Runs">
      <RunControls
        selectedRunId={runList.selectedRunId}
        runStatus={chatStream.state.runStatus}
        isStreaming={chatStream.state.isStreaming}
        onNewChat={handleNewChat}
        onRefreshRun={handleRefresh}
        onInterrupt={handleInterrupt}
        onAbort={handleAbort}
      />
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
      />
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