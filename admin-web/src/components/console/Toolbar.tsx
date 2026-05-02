import { useState } from 'react'
import { RefreshCw, Settings } from 'lucide-react'
import { Button } from '../ui/Button'

interface ToolbarProps {
  userId: string
  adminToken: string
  onUserIdChange: (userId: string) => void
  onAdminTokenChange: (token: string) => void
  onRefresh?: () => void
  loading?: boolean
}

export function Toolbar({
  userId,
  adminToken,
  onUserIdChange,
  onAdminTokenChange,
  onRefresh,
  loading = false,
}: ToolbarProps) {
  const [showSettings, setShowSettings] = useState(false)

  return (
    <div className="flex items-center gap-2">
      {/* User ID display */}
      <span className="text-sm text-gray-300">
        User: {userId || 'unknown'}
      </span>

      {/* Refresh button */}
      {onRefresh && (
        <Button
          variant="ghost"
          size="sm"
          onClick={onRefresh}
          disabled={loading}
          loading={loading}
          icon={<RefreshCw className="w-4 h-4" />}
        >
          Refresh
        </Button>
      )}

      {/* Settings toggle */}
      <Button
        variant="ghost"
        size="sm"
        onClick={() => setShowSettings(!showSettings)}
        icon={<Settings className="w-4 h-4" />}
      >
        Settings
      </Button>

      {/* Settings panel */}
      {showSettings && (
        <div className="absolute right-4 top-12 bg-white rounded-lg shadow-lg p-4 z-50 w-64">
          <div className="space-y-4">
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">
                User ID
              </label>
              <input
                type="text"
                value={userId}
                onChange={(e) => onUserIdChange(e.target.value)}
                className="w-full px-2 py-1 border border-gray-300 rounded text-sm"
                placeholder="demo-user"
              />
            </div>
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">
                Admin Token
              </label>
              <input
                type="password"
                value={adminToken}
                onChange={(e) => onAdminTokenChange(e.target.value)}
                className="w-full px-2 py-1 border border-gray-300 rounded text-sm"
                placeholder="(optional for local)"
              />
              <p className="text-xs text-gray-500 mt-1">
                Token not displayed for security
              </p>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}