import type { RunSummary, RuntimeState } from '../types'

const API_BASE = '/api/admin/console'

export interface AdminApiConfig {
  adminToken?: string
}

export interface ListRunsParams {
  page?: number
  pageSize?: number
  status?: string
  userId?: string
}

export function createAdminApi(config: AdminApiConfig) {
  const headers = () => {
    const h: Record<string, string> = {
      'Content-Type': 'application/json',
    }
    // Only include admin token if it's non-empty
    if (config.adminToken && config.adminToken.trim() !== '') {
      h['X-Admin-Token'] = config.adminToken
    }
    return h
  }

  async function listRuns(params: ListRunsParams = {}): Promise<RunSummary[]> {
    const query = new URLSearchParams()
    if (params.page) query.set('page', String(params.page))
    if (params.pageSize) query.set('pageSize', String(params.pageSize))
    if (params.status) query.set('status', params.status)
    if (params.userId) query.set('userId', params.userId)

    const url = `${API_BASE}/runs?${query.toString()}`
    const response = await fetch(url, {
      method: 'GET',
      headers: headers(),
    })

    if (!response.ok) {
      // Never expose admin token in error
      throw new Error(`listRuns failed: ${response.status}`)
    }

    return response.json() as Promise<RunSummary[]>
  }

  async function getRuntimeState(runId: string): Promise<RuntimeState> {
    const response = await fetch(`${API_BASE}/runs/${runId}/runtime-state`, {
      method: 'GET',
      headers: headers(),
    })

    if (!response.ok) {
      throw new Error(`getRuntimeState failed: ${response.status}`)
    }

    return response.json() as Promise<RuntimeState>
  }

  return {
    listRuns,
    getRuntimeState,
    headers,
  }
}