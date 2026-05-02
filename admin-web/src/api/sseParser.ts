import type { SseEvent, SsePingEvent, SseErrorEvent } from '../types'

export interface SseParseResult {
  events: SseEvent[]
  errors: { line: string; error: string }[]
}

/**
 * Parse SSE data lines into events.
 * Handles split chunks, multiple events per chunk, ping, and malformed JSON.
 * Normalizes toolName field (handles legacy 'name' field).
 */
export function parseSseData(data: string): SseEvent | null {
  // Empty data means nothing to parse
  if (data === '') {
    return null
  }

  // Ping is a special case
  if (data === 'ping') {
    return { type: 'ping' } as SsePingEvent
  }

  try {
    const raw = JSON.parse(data) as Record<string, unknown>

    // Normalize toolName field
    if (raw.type === 'tool_use' && 'name' in raw && !('toolName' in raw)) {
      raw.toolName = raw.name
      delete raw.name
    }

    return raw as unknown as SseEvent
  } catch (e) {
    // Malformed JSON - return error event
    return {
      type: 'error',
      error: `parse error: ${(e as Error).message}`,
    } as SseErrorEvent
  }
}

/**
 * Parse a complete SSE chunk (may contain multiple lines).
 */
export function parseSseChunk(chunk: string, buffer: string = ''): { events: SseEvent[]; buffer: string } {
  const combined = buffer + chunk
  const lines = combined.split('\n')

  // Last element may be incomplete - keep as buffer
  const newBuffer = lines.pop() ?? ''

  const events: SseEvent[] = []

  for (const line of lines) {
    if (line.startsWith('data:')) {
      const data = line.slice(5).trim()
      if (data === '') continue

      const event = parseSseData(data)
      if (event) {
        events.push(event)
      }
    }
  }

  return { events, buffer: newBuffer }
}

/**
 * Parse multiple chunks in sequence (handles split events).
 */
export function parseSseChunks(chunks: string[]): SseParseResult {
  const events: SseEvent[] = []
  const errors: { line: string; error: string }[] = []
  let buffer = ''

  for (const chunk of chunks) {
    const result = parseSseChunk(chunk, buffer)
    buffer = result.buffer

    for (const event of result.events) {
      if (event.type === 'error') {
        errors.push({ line: '', error: event.error ?? 'unknown' })
      } else {
        events.push(event)
      }
    }
  }

  // Process remaining buffer if any
  if (buffer.startsWith('data:')) {
    const data = buffer.slice(5).trim()
    if (data !== '') {
      const event = parseSseData(data)
      if (event) {
        if (event.type === 'error') {
          errors.push({ line: buffer, error: event.error ?? 'unknown' })
        } else {
          events.push(event)
        }
      }
    }
  }

  return { events, errors }
}

/**
 * Read SSE events from a fetch Response stream.
 * Unified reader for all SSE streams - handles split chunks, ping, and malformed JSON.
 * Normalizes toolName field (handles legacy 'name' field).
 */
const MAX_BUFFER_SIZE = 1024 * 1024 // 1MB limit to prevent memory exhaustion

export async function* readSseStream(response: Response): AsyncIterable<SseEvent> {
  const reader = response.body?.getReader()
  if (!reader) {
    throw new Error('No response body')
  }

  const decoder = new TextDecoder()
  let buffer = ''

  try {
    while (true) {
      const { done, value } = await reader.read()
      if (done) break

      buffer += decoder.decode(value, { stream: true })

      // Guard against unbounded buffer growth (malformed stream without newlines)
      if (buffer.length > MAX_BUFFER_SIZE) {
        throw new Error(`SSE buffer exceeded ${MAX_BUFFER_SIZE} bytes - possible malformed stream`)
      }

      const lines = buffer.split('\n')
      buffer = lines.pop() ?? ''

      for (const line of lines) {
        if (line.startsWith('data:')) {
          const data = line.slice(5).trim()
          if (data === '') continue

          const event = parseSseData(data)
          if (event) {
            yield event
          }
        }
      }
    }

    // Process remaining buffer if any
    if (buffer.startsWith('data:')) {
      const data = buffer.slice(5).trim()
      if (data !== '') {
        const event = parseSseData(data)
        if (event) {
          yield event
        }
      }
    }
  } finally {
    // Ensure reader is released
    reader.releaseLock()
  }
}