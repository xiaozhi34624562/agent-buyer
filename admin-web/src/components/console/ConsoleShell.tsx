import type { ReactNode } from 'react'
import { useState, useEffect } from 'react'
import { Toolbar } from './Toolbar'

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
  const [mounted, setMounted] = useState(false)

  useEffect(() => {
    setMounted(true)
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
    <div
      className={`min-h-screen flex flex-col transition-opacity duration-400 ${mounted ? 'opacity-100' : 'opacity-0'}`}
      style={{ background: 'var(--color-bg)' }}
    >
      {/* Header - Anthropic dark style */}
      <header
        className="px-6 py-4 flex items-center justify-between animate-slide-up"
        style={{
          background: 'var(--color-text)',
          color: 'var(--color-bg)',
        }}
      >
        <div className="flex items-center gap-4">
          {/* Logo/Title - serif font for warmth */}
          <h1
            className="text-xl tracking-tight"
            style={{ fontFamily: 'var(--font-serif)', fontWeight: 500 }}
          >
            Agent Console
          </h1>
          {/* Subtle separator */}
          <div
            className="h-4 w-px opacity-30"
            style={{ background: 'var(--color-bg)' }}
          />
          {/* Version/status */}
          <span
            className="text-sm opacity-60"
            style={{ fontFamily: 'var(--font-sans)' }}
          >
            Buyer
          </span>
        </div>

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

      {/* Mobile tabs - warm background */}
      {isMobile && (
        <div
          className="px-4 py-2 flex gap-1 animate-slide-up stagger-1"
          style={{ background: 'var(--color-bg-subtle)', borderBottom: '1px solid var(--color-border)' }}
        >
          {tabs.map(tab => (
            <button
              key={tab.id}
              onClick={() => setMobileTab(tab.id)}
              className="px-4 py-2 rounded text-sm font-medium transition-all duration-150"
              style={{
                fontFamily: 'var(--font-sans)',
                background: mobileTab === tab.id ? 'var(--color-bg)' : 'transparent',
                color: mobileTab === tab.id ? 'var(--color-text)' : 'var(--color-text-secondary)',
              }}
            >
              {tab.label}
            </button>
          ))}
        </div>
      )}

      {/* Main content */}
      <main className="flex-1 p-6 animate-slide-up stagger-2">
        {isMobile ? (
          /* Mobile: single panel with smooth transitions */
          <div
            className="h-full transition-opacity duration-200"
            key={mobileTab}
          >
            {mobileTab === 'runs' && runsPanel}
            {mobileTab === 'timeline' && timelinePanel}
            {mobileTab === 'chat' && chatPanel}
          </div>
        ) : (
          /* Desktop: three-column layout with elegant spacing */
          <div className="h-full grid grid-cols-12 gap-6">
            {/* Runs panel - narrower, list view */}
            <div
              className="col-span-3 animate-slide-up stagger-3"
              style={{ opacity: 0, animationFillMode: 'forwards' }}
            >
              {runsPanel}
            </div>

            {/* Timeline panel - wider, detail view */}
            <div
              className="col-span-5 animate-slide-up stagger-4"
              style={{ opacity: 0, animationFillMode: 'forwards' }}
            >
              {timelinePanel}
            </div>

            {/* Chat panel - medium, conversation */}
            <div
              className="col-span-4 animate-slide-up stagger-5"
              style={{ opacity: 0, animationFillMode: 'forwards' }}
            >
              {chatPanel}
            </div>
          </div>
        )}
      </main>

      {/* Footer - minimal, warm */}
      <footer
        className="px-6 py-3 text-center animate-slide-up"
        style={{
          borderTop: '1px solid var(--color-border-subtle)',
          color: 'var(--color-text-muted)',
          fontFamily: 'var(--font-sans)',
          fontSize: '0.75rem',
        }}
      >
        <span style={{ fontStyle: 'italic' }}>
          Press Enter to send · Ctrl+K for commands
        </span>
      </footer>
    </div>
  )
}