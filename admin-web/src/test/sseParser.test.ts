import { describe, it, expect } from 'vitest'
import { parseSseData, parseSseChunk, parseSseChunks } from '../api/sseParser'

describe('sseParser', () => {
  describe('parseSseData', () => {
    it('should parse single event in single chunk', () => {
      const data = '{"type":"text_delta","content":"Hello"}'
      const event = parseSseData(data)

      expect(event).not.toBeNull()
      expect(event?.type).toBe('text_delta')
      expect(event?.content).toBe('Hello')
    })

    it('should handle ping as special event', () => {
      const event = parseSseData('ping')

      expect(event).not.toBeNull()
      expect(event?.type).toBe('ping')
    })

    it('should handle empty data', () => {
      const event = parseSseData('')

      expect(event).toBeNull()
    })

    it('should generate error for malformed JSON', () => {
      const event = parseSseData('not-json')

      expect(event).not.toBeNull()
      expect(event?.type).toBe('error')
      expect(event?.error).toContain('parse error')
    })

    it('should normalize toolName field from legacy name', () => {
      const data = '{"type":"tool_use","name":"query_order","toolCallId":"tc-123"}'
      const event = parseSseData(data) as { type: string; toolName: string; toolCallId: string }

      expect(event.type).toBe('tool_use')
      expect(event.toolName).toBe('query_order')
      expect(event.toolCallId).toBe('tc-123')
    })

    it('should keep existing toolName field', () => {
      const data = '{"type":"tool_use","toolName":"cancel_order","toolCallId":"tc-456"}'
      const event = parseSseData(data) as { type: string; toolName: string; toolCallId: string }

      expect(event.toolName).toBe('cancel_order')
    })
  })

  describe('parseSseChunk', () => {
    it('should parse multiple events in single chunk', () => {
      const chunk = 'data:{"type":"text_delta","content":"A"}\ndata:{"type":"text_delta","content":"B"}\n'
      const result = parseSseChunk(chunk)

      expect(result.events).toHaveLength(2)
      expect(result.events[0].content).toBe('A')
      expect(result.events[1].content).toBe('B')
      expect(result.buffer).toBe('')
    })

    it('should handle split chunk (event across chunks)', () => {
      const chunk1 = 'data:{"type":"text_delta","conte'
      const chunk2 = 'nt":"Hello"}\n'

      const result1 = parseSseChunk(chunk1)
      expect(result1.events).toHaveLength(0)
      expect(result1.buffer).toBe('data:{"type":"text_delta","conte')

      const result2 = parseSseChunk(chunk2, result1.buffer)
      expect(result2.events).toHaveLength(1)
      expect(result2.events[0].content).toBe('Hello')
    })
  })

  describe('parseSseChunks', () => {
    it('should parse split event across multiple chunks', () => {
      const chunks = [
        'data:{"type":"tool_use","tool',
        'Name":"query_order","toolCallId":"tc-1"}\n',
      ]

      const result = parseSseChunks(chunks)

      expect(result.events).toHaveLength(1)
      expect(result.events[0].type).toBe('tool_use')
    })

    it('should preserve ping in events list', () => {
      const chunks = ['data:ping\n']

      const result = parseSseChunks(chunks)

      expect(result.events).toHaveLength(1)
      expect(result.events[0].type).toBe('ping')
    })

    it('should collect errors for invalid JSON', () => {
      const chunks = ['data:{"type":"text_delta","content":"valid"}\ndata:invalid\n']

      const result = parseSseChunks(chunks)

      expect(result.events).toHaveLength(1) // valid event
      expect(result.errors).toHaveLength(1) // invalid JSON error
    })
  })
})