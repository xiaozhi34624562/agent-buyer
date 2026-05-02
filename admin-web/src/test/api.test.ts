import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { createAgentApi } from '../api/agentApi'
import { createAdminApi } from '../api/adminApi'

describe('agentApi', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('should include X-User-Id header for all requests', async () => {
    const mockFetch = vi.mocked(fetch)
    mockFetch.mockResolvedValue(new Response('{}', { status: 200 }))

    const api = createAgentApi({ userId: 'test-user' })
    await api.getTrajectory('run-123')

    expect(mockFetch).toHaveBeenCalled()
    const [url, options] = mockFetch.mock.calls[0]
    expect(url).toContain('/api/agent/runs/run-123')
    expect(options?.headers).toHaveProperty('X-User-Id', 'test-user')
  })

  it('should include X-User-Id header for createRun', async () => {
    const mockFetch = vi.mocked(fetch)
    mockFetch.mockResolvedValue(new Response(null, { status: 200 }))

    const api = createAgentApi({ userId: 'test-user' })
    await api.createRun({
      messages: [{ role: 'user', content: 'hello' }],
      allowedToolNames: ['query_order'],
      llmParams: { model: 'deepseek-reasoner', temperature: 0.2, maxTokens: 256 },
    })

    const options = mockFetch.mock.calls[0][1]
    expect(options?.headers).toHaveProperty('X-User-Id', 'test-user')
  })
})

describe('adminApi', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('should not include X-Admin-Token header when token is empty', async () => {
    const mockFetch = vi.mocked(fetch)
    mockFetch.mockResolvedValue(new Response('[]', { status: 200 }))

    const api = createAdminApi({ adminToken: '' })
    await api.listRuns()

    const options = mockFetch.mock.calls[0][1]
    expect(options?.headers).not.toHaveProperty('X-Admin-Token')
  })

  it('should not include X-Admin-Token header when token is undefined', async () => {
    const mockFetch = vi.mocked(fetch)
    mockFetch.mockResolvedValue(new Response('[]', { status: 200 }))

    const api = createAdminApi({ adminToken: undefined })
    await api.listRuns()

    const options = mockFetch.mock.calls[0][1]
    expect(options?.headers).not.toHaveProperty('X-Admin-Token')
  })

  it('should include X-Admin-Token header when token is non-empty', async () => {
    const mockFetch = vi.mocked(fetch)
    mockFetch.mockResolvedValue(new Response('[]', { status: 200 }))

    const api = createAdminApi({ adminToken: 'secret-token' })
    await api.listRuns()

    const options = mockFetch.mock.calls[0][1]
    expect(options?.headers).toHaveProperty('X-Admin-Token', 'secret-token')
  })

  it('should not expose admin token in error message', async () => {
    const mockFetch = vi.mocked(fetch)
    mockFetch.mockResolvedValue(new Response('Forbidden', { status: 403 }))

    const api = createAdminApi({ adminToken: 'secret-token' })

    try {
      await api.listRuns()
      expect.fail('should have thrown')
    } catch (e) {
      const message = (e as Error).message
      expect(message).not.toContain('secret-token')
      expect(message).toContain('403')
    }
  })
})