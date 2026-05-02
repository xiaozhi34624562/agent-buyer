import { useState, useCallback } from 'react'
import type { Trajectory, TrajectoryNode } from '../types'
import { createAgentApi } from '../api/agentApi'

export interface UseRunDetailOptions {
  userId?: string
}

export interface UseRunDetailResult {
  trajectory: Trajectory | null
  nodes: TrajectoryNode[]
  loading: boolean
  error: string | null
  fetchTrajectory: (runId: string) => void
  clearTrajectory: () => void
}

// Sort nodes by timestamp, null timestamps go last
function sortNodes(nodes: TrajectoryNode[]): TrajectoryNode[] {
  return [...nodes].sort((a, b) => {
    if (!a.timestamp && !b.timestamp) return 0
    if (!a.timestamp) return 1
    if (!b.timestamp) return -1
    return new Date(a.timestamp).getTime() - new Date(b.timestamp).getTime()
  })
}

export function useRunDetail(options: UseRunDetailOptions = {}): UseRunDetailResult {
  const { userId } = options

  const [trajectory, setTrajectory] = useState<Trajectory | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const fetchTrajectory = useCallback(async (runId: string) => {
    if (!userId) {
      setError('userId is required')
      return
    }

    setLoading(true)
    setError(null)

    try {
      const api = createAgentApi({ userId })
      const result = await api.getTrajectory(runId)
      setTrajectory(result)
    } catch (e) {
      setError((e as Error).message)
      setTrajectory(null)
    } finally {
      setLoading(false)
    }
  }, [userId])

  const clearTrajectory = useCallback(() => {
    setTrajectory(null)
    setError(null)
  }, [])

  const nodes = trajectory ? sortNodes(trajectory.nodes) : []

  return {
    trajectory,
    nodes,
    loading,
    error,
    fetchTrajectory,
    clearTrajectory,
  }
}