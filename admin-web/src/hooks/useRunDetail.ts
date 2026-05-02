import { useState, useCallback } from 'react'
import type { Trajectory, TrajectoryNode } from '../types'
import { createAgentApi } from '../api/agentApi'
import { transformTrajectory } from '../utils/trajectoryTransform'

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
  if (!Array.isArray(nodes)) return []
  return [...nodes].sort((a, b) => {
    if (!a.timestamp && !b.timestamp) return 0
    if (!a.timestamp) return 1
    if (!b.timestamp) return -1
    return new Date(a.timestamp).getTime() - new Date(b.timestamp).getTime()
  })
}

/**
 * Parse trajectory response - handles both backend DTO format and mock format.
 */
function parseTrajectoryResponse(data: unknown): Trajectory {
  // Backend format: { run, messages, llmAttempts, toolCalls, ... }
  if (data && typeof data === 'object' && 'run' in data) {
    const run = (data as Record<string, unknown>).run as { runId: string }
    const nodes = transformTrajectory(data as unknown as Parameters<typeof transformTrajectory>[0])
    return { runId: run.runId, nodes }
  }

  // Mock/test format: { runId, nodes }
  if (data && typeof data === 'object' && 'runId' in data && 'nodes' in data) {
    return data as Trajectory
  }

  // Unknown format
  throw new Error('Unknown trajectory response format')
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
      const parsed = parseTrajectoryResponse(result)
      setTrajectory(parsed)
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