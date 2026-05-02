import type { ReactNode } from 'react'

interface PanelProps {
  title?: string
  children: ReactNode
  className?: string
  actions?: ReactNode
}

export function Panel({ title, children, className = '', actions }: PanelProps) {
  return (
    <div
      className={`overflow-hidden ${className}`}
      style={{
        background: 'var(--color-bg-card)',
        border: '1px solid var(--color-border)',
        borderRadius: 'var(--radius-md)',
      }}
    >
      {title && (
        <div
          className="px-5 py-4 flex items-center justify-between"
          style={{
            borderBottom: '1px solid var(--color-border-subtle)',
          }}
        >
          <h2
            style={{
              fontFamily: 'var(--font-sans)',
              fontSize: '0.875rem',
              fontWeight: 600,
              color: 'var(--color-text)',
              letterSpacing: '-0.01em',
            }}
          >
            {title}
          </h2>
          {actions && (
            <div className="flex items-center gap-2">
              {actions}
            </div>
          )}
        </div>
      )}
      <div className="p-4">
        {children}
      </div>
    </div>
  )
}