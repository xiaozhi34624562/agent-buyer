import { useState, useEffect } from 'react'
import { ConsoleShell } from './components/console/ConsoleShell'
import { Panel } from './components/ui/Panel'

function App() {
  const [userId, setUserId] = useState(() => localStorage.getItem('userId') || 'demo-user')
  const [adminToken, setAdminToken] = useState(() => localStorage.getItem('adminToken') || '')

  // Persist to localStorage
  useEffect(() => {
    localStorage.setItem('userId', userId)
  }, [userId])

  useEffect(() => {
    localStorage.setItem('adminToken', adminToken)
  }, [adminToken])

  return (
    <ConsoleShell
      userId={userId}
      adminToken={adminToken}
      onUserIdChange={setUserId}
      onAdminTokenChange={setAdminToken}
      runsPanel={<Panel title="Runs">Run list loading...</Panel>}
      timelinePanel={<Panel title="Timeline">Timeline loading...</Panel>}
      chatPanel={<Panel title="Chat">Chat loading...</Panel>}
    />
  )
}

export default App