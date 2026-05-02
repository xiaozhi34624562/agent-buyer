import { useState, useEffect } from 'react'
import { ConsoleShell } from './components/console/ConsoleShell'
import { Panel } from './components/ui/Panel'

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

  return (
    <ConsoleShell
      userId={userId}
      adminToken={adminToken}
      onUserIdChange={setUserId}
      onAdminTokenChange={setAdminToken}
      debug={debug}
      onDebugChange={setDebug}
      runsPanel={<Panel title="Runs">Run list loading...</Panel>}
      timelinePanel={<Panel title="Timeline">Timeline loading...</Panel>}
      chatPanel={<Panel title="Chat">Chat loading...</Panel>}
    />
  )
}

export default App