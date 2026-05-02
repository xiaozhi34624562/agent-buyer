import type { ButtonHTMLAttributes, ReactNode } from 'react'
import { Loader2 } from 'lucide-react'

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: 'primary' | 'secondary' | 'danger' | 'ghost' | 'accent'
  size?: 'sm' | 'md' | 'lg'
  loading?: boolean
  icon?: ReactNode
}

export function Button({
  variant = 'primary',
  size = 'md',
  loading = false,
  icon,
  children,
  disabled,
  className = '',
  ...props
}: ButtonProps) {
  const getVariantStyles = () => {
    switch (variant) {
      case 'primary':
        return {
          background: 'var(--color-text)',
          color: 'var(--color-bg)',
          border: '1px solid var(--color-text)',
        }
      case 'secondary':
        return {
          background: 'transparent',
          color: 'var(--color-text)',
          border: '1px solid var(--color-border)',
        }
      case 'accent':
        return {
          background: 'var(--color-accent)',
          color: 'var(--color-bg)',
          border: '1px solid var(--color-accent)',
        }
      case 'danger':
        return {
          background: 'var(--color-error)',
          color: 'var(--color-bg)',
          border: '1px solid var(--color-error)',
        }
      case 'ghost':
        return {
          background: 'transparent',
          color: 'var(--color-text-secondary)',
          border: '1px solid transparent',
        }
      default:
        return {}
    }
  }

  const sizeStyles = {
    sm: 'px-2.5 py-1.5 text-xs',
    md: 'px-3.5 py-2 text-sm',
    lg: 'px-4 py-2.5 text-base',
  }

  const baseStyles: React.CSSProperties = {
    fontFamily: 'var(--font-sans)',
    fontWeight: 500,
    borderRadius: 'var(--radius-sm)',
    transition: 'all 150ms ease',
    cursor: disabled || loading ? 'not-allowed' : 'pointer',
    opacity: disabled || loading ? 0.5 : 1,
    display: 'inline-flex',
    alignItems: 'center',
    justifyContent: 'center',
    gap: '0.5rem',
  }

  return (
    <button
      className={`${sizeStyles[size]} ${className}`}
      style={{ ...baseStyles, ...getVariantStyles() }}
      disabled={disabled || loading}
      {...props}
    >
      {loading ? (
        <Loader2 className="w-3.5 h-3.5 animate-spin" />
      ) : icon ? (
        icon
      ) : null}
      {children}
    </button>
  )
}