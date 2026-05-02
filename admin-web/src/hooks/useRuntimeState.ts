import { useState, useCallback } from 'react'
import type { RuntimeState } from '../types'
import { createAdminApi } from '../api/adminApi'

export interface UseRuntimeStateOptions {
  adminToken?: string
}

export interface UseRuntimeStateResult {
  runtimeState: RuntimeState | null
  loading: boolean
  error: string | null
  fetchRuntimeState: (runId: string) => void
  clearRuntimeState: () => void
}

export function useRuntimeState(options: UseRuntimeStateOptions = {}): UseRuntimeStateResult {
  const { adminToken } = options

  const [runtimeState, setRuntimeState] = useState<RuntimeState | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const fetchRuntimeState = useCallback(async (runId: string) => {
    setLoading(true)
    setError(null)

    try {
      const api = createAdminApi({ adminToken })
      const result = await api.getRuntimeState(runId)
      setRuntimeState(result)
    } catch (e) {
      setError((e as Error).message)
      setRuntimeState(null)
    } finally {
      setLoading(false)
    }
  }, [adminToken])

  const clearRuntimeState = useCallback(() => {
    setRuntimeState(null)
    setError(null)
  }, [])

  return {
    runtimeState,
    loading,
    error,
    fetchRuntimeState,
    clearRuntimeState,
  }
}