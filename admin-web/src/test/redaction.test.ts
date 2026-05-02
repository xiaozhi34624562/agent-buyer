import { describe, it, expect } from 'vitest'
import {
  redactString,
  redactObject,
  containsSensitiveValue,
  SENSITIVE_FIELDS,
} from '../utils/redaction'

describe('redaction', () => {
  describe('redactString', () => {
    it('should redact sk- prefixed API keys', () => {
      const input = 'API key: sk-cf80413b8e5a4650aed6214c1a80e4dc'
      const result = redactString(input)
      expect(result).toBe('API key: [REDACTED]')
      expect(result).not.toContain('sk-cf80413b8e5a4650aed6214c1a80e4dc')
    })

    it('should redact confirmToken', () => {
      const input = 'confirmToken: abc123-def456-ghi789'
      const result = redactString(input)
      expect(result).toContain('[REDACTED]')
      expect(result).not.toContain('abc123-def456-ghi789')
    })

    it('should redact adminToken', () => {
      const input = '{"adminToken":"secret-admin-token"}'
      const result = redactString(input)
      expect(result).toContain('[REDACTED]')
      expect(result).not.toContain('secret-admin-token')
    })

    it('should redact X-Admin-Token header', () => {
      const input = 'X-Admin-Token: my-admin-secret-123'
      const result = redactString(input)
      expect(result).toContain('[REDACTED]')
      expect(result).not.toContain('my-admin-secret-123')
    })

    it('should not redact non-sensitive strings', () => {
      const input = 'This is a normal message without secrets'
      const result = redactString(input)
      expect(result).toBe(input)
    })

    it('should handle empty string', () => {
      expect(redactString('')).toBe('')
    })

    it('should redact multiple sensitive values', () => {
      const input = 'Key: sk-abc123def456789012 and token: confirmToken: xyz789abc123'
      const result = redactString(input)
      expect(result).toContain('[REDACTED]')
      expect(result).not.toContain('sk-abc123def456789012')
      expect(result).not.toContain('xyz789abc123')
    })
  })

  describe('redactObject', () => {
    it('should redact confirmToken field', () => {
      const input = { confirmToken: 'abc-123-def', status: 'PENDING' }
      const result = redactObject(input)
      expect(result.confirmToken).toBe('[REDACTED]')
      expect(result.status).toBe('PENDING')
    })

    it('should redact adminToken field', () => {
      const input = { adminToken: 'secret-token', userId: 'demo' }
      const result = redactObject(input)
      expect(result.adminToken).toBe('[REDACTED]')
      expect(result.userId).toBe('demo')
    })

    it('should redact apiKey field', () => {
      const input = { apiKey: 'sk-test-key-12345678901234567890', provider: 'deepseek' }
      const result = redactObject(input)
      expect(result.apiKey).toBe('[REDACTED]')
      expect(result.provider).toBe('deepseek')
    })

    it('should redact nested objects', () => {
      const input = {
        run: {
          confirmToken: 'nested-token',
          data: {
            apiKey: 'sk-nested-key-12345678901234567890',
          },
        },
      }
      const result = redactObject(input)
      expect(result.run.confirmToken).toBe('[REDACTED]')
      expect(result.run.data.apiKey).toBe('[REDACTED]')
    })

    it('should redact arrays', () => {
      const input = [
        { confirmToken: 'token-1' },
        { confirmToken: 'token-2' },
      ]
      const result = redactObject(input)
      expect(result[0].confirmToken).toBe('[REDACTED]')
      expect(result[1].confirmToken).toBe('[REDACTED]')
    })

    it('should redact string values in arrays', () => {
      const input = ['normal', 'sk-test-key-12345678901234567890', 'another']
      const result = redactObject(input)
      expect(result[0]).toBe('normal')
      expect(result[1]).toBe('[REDACTED]')
      expect(result[2]).toBe('another')
    })

    it('should handle null and undefined', () => {
      expect(redactObject(null)).toBeNull()
      expect(redactObject(undefined)).toBeUndefined()
    })

    it('should handle numbers and booleans', () => {
      expect(redactObject(42)).toBe(42)
      expect(redactObject(true)).toBe(true)
    })
  })

  describe('containsSensitiveValue', () => {
    it('should detect API key', () => {
      expect(containsSensitiveValue('sk-abc123def456789012')).toBe(true)
    })

    it('should detect confirmToken', () => {
      expect(containsSensitiveValue('confirmToken: abc-123-def')).toBe(true)
    })

    it('should return false for non-sensitive', () => {
      expect(containsSensitiveValue('normal text')).toBe(false)
    })

    it('should return false for empty string', () => {
      expect(containsSensitiveValue('')).toBe(false)
    })
  })

  describe('SENSITIVE_FIELDS', () => {
    it('should include confirmToken', () => {
      expect(SENSITIVE_FIELDS).toContain('confirmToken')
    })

    it('should include adminToken', () => {
      expect(SENSITIVE_FIELDS).toContain('adminToken')
    })

    it('should include apiKey', () => {
      expect(SENSITIVE_FIELDS).toContain('apiKey')
    })
  })
})