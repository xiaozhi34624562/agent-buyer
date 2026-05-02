import { useState, useEffect, useCallback } from 'react'
import type { RunSummary } from '../types'
import { createAdminApi, type ListRunsParams } from '../api/adminApi'

export interface UseRunListOptions {
  adminToken?: string
  initialPage?: number
  initialPageSize?: number
}

export interface UseRunListResult {
  runs: RunSummary[]
  loading: boolean
  error: string | null
  page: number
  pageSize: number
  statusFilter: string | null
  userIdFilter: string | null
  selectedRunId: string | null
  setSelectedRunId: (runId: string | null) => void
  setPage: (page: number) => void
  setStatusFilter: (status: string | null) => void
  setUserIdFilter: (userId: string | null) => void
  refresh: () => void
}

const MIN_PAGE_SIZE = 1
const MAX_PAGE_SIZE = 100
const DEFAULT_PAGE_SIZE = 20

function clampPageSize(size: number): number {
  return Math.max(MIN_PAGE_SIZE, Math.min(MAX_PAGE_SIZE, size))
}

export function useRunList(options: UseRunListOptions = {}): UseRunListResult {
  const { adminToken, initialPage = 1, initialPageSize = DEFAULT_PAGE_SIZE } = options

  const [runs, setRuns] = useState<RunSummary[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [page, setPage] = useState(initialPage)
  const pageSize = clampPageSize(initialPageSize) // Fixed pageSize, not dynamically changed
  const [statusFilter, setStatusFilter] = useState<string | null>(null)
  const [userIdFilter, setUserIdFilter] = useState<string | null>(null)
  const [selectedRunId, setSelectedRunId] = useState<string | null>(null)

  const fetchRuns = useCallback(async () => {
    setLoading(true)
    setError(null)

    try {
      const api = createAdminApi({ adminToken })
      const params: ListRunsParams = {
        page,
        pageSize: clampPageSize(pageSize),
      }
      if (statusFilter) params.status = statusFilter
      if (userIdFilter) params.userId = userIdFilter

      const result = await api.listRuns(params)
      setRuns(result)
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setLoading(false)
    }
  }, [adminToken, page, pageSize, statusFilter, userIdFilter])

  useEffect(() => {
    fetchRuns()
  }, [fetchRuns])

  const refresh = useCallback(() => {
    fetchRuns()
  }, [fetchRuns])

  return {
    runs,
    loading,
    error,
    page,
    pageSize,
    statusFilter,
    userIdFilter,
    selectedRunId,
    setSelectedRunId,
    setPage,
    setStatusFilter,
    setUserIdFilter,
    refresh,
  }
}