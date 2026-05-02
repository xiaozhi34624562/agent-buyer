import type { ReactNode } from 'react'
import { useState, useEffect } from 'react'
import { Toolbar } from './Toolbar'
import { Button } from '../ui/Button'

interface ConsoleShellProps {
  userId: string
  adminToken: string
  onUserIdChange: (userId: string) => void
  onAdminTokenChange: (token: string) => void
  onRefresh?: () => void
  loading?: boolean
  debug?: boolean
  onDebugChange?: (debug: boolean) => void
  runsPanel: ReactNode
  timelinePanel: ReactNode
  chatPanel: ReactNode
}

export function ConsoleShell({
  userId,
  adminToken,
  onUserIdChange,
  onAdminTokenChange,
  onRefresh,
  loading,
  debug,
  onDebugChange,
  runsPanel,
  timelinePanel,
  chatPanel,
}: ConsoleShellProps) {
  const [mobileTab, setMobileTab] = useState<'runs' | 'timeline' | 'chat'>('runs')
  const [isMobile, setIsMobile] = useState(false)

  useEffect(() => {
    const checkMobile = () => setIsMobile(window.innerWidth < 768)
    checkMobile()
    window.addEventListener('resize', checkMobile)
    return () => window.removeEventListener('resize', checkMobile)
  }, [])

  const tabs = [
    { id: 'runs' as const, label: 'Runs' },
    { id: 'timeline' as const, label: 'Timeline' },
    { id: 'chat' as const, label: 'Chat' },
  ]

  return (
    <div className="min-h-screen bg-gray-50 flex flex-col">
      {/* Header */}
      <header className="bg-blue-600 text-white px-4 py-3 flex items-center justify-between shadow-sm">
        <h1 className="text-lg font-bold">Agent Buyer Console</h1>
        <Toolbar
          userId={userId}
          adminToken={adminToken}
          onUserIdChange={onUserIdChange}
          onAdminTokenChange={onAdminTokenChange}
          onRefresh={onRefresh}
          loading={loading}
          debug={debug}
          onDebugChange={onDebugChange}
        />
      </header>

      {/* Mobile tabs */}
      {isMobile && (
        <div className="bg-gray-200 px-2 py-2 flex gap-2">
          {tabs.map(tab => (
            <Button
              key={tab.id}
              variant={mobileTab === tab.id ? 'primary' : 'ghost'}
              size="sm"
              onClick={() => setMobileTab(tab.id)}
            >
              {tab.label}
            </Button>
          ))}
        </div>
      )}

      {/* Main content */}
      <main className="flex-1 p-4">
        {isMobile ? (
          // Mobile: single panel with tabs
          <div className="h-full">
            {mobileTab === 'runs' && runsPanel}
            {mobileTab === 'timeline' && timelinePanel}
            {mobileTab === 'chat' && chatPanel}
          </div>
        ) : (
          // Desktop: three-column layout
          <div className="h-full grid grid-cols-12 gap-4">
            <div className="col-span-3">{runsPanel}</div>
            <div className="col-span-5">{timelinePanel}</div>
            <div className="col-span-4">{chatPanel}</div>
          </div>
        )}
      </main>
    </div>
  )
}