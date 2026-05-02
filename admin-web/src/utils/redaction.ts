/**
 * Redact sensitive values from strings and objects.
 * Handles confirmToken, adminToken, provider API keys (sk-...).
 */

// Patterns to detect sensitive values
const SENSITIVE_PATTERNS = [
  // Provider API keys (sk- prefix common for OpenAI/DeepSeek style, at least 12 chars after prefix)
  /sk-[a-zA-Z0-9_-]{12,}/g,
  // confirmToken format (UUID-like or alphanumeric)
  /confirmToken["']?\s*[:=]\s*["']?[a-zA-Z0-9_-]{8,}/gi,
  // adminToken format (alphanumeric, at least 8 chars)
  /adminToken["']?\s*[:=]\s*["']?[a-zA-Z0-9_-]{8,}/gi,
  // X-Admin-Token header
  /X-Admin-Token["']?\s*[:=]\s*["']?[a-zA-Z0-9_-]{8,}/gi,
]

const REDACTION_PLACEHOLDER = '[REDACTED]'

/**
 * Redact sensitive values from a string.
 */
export function redactString(value: string): string {
  let result = value
  for (const pattern of SENSITIVE_PATTERNS) {
    // Reset lastIndex for each pattern (since they're reused)
    pattern.lastIndex = 0
    result = result.replace(pattern, REDACTION_PLACEHOLDER)
  }
  return result
}

/**
 * Redact sensitive values from an object (deep scan).
 */
export function redactObject<T>(obj: T): T {
  if (obj === null || obj === undefined) {
    return obj
  }

  if (typeof obj === 'string') {
    return redactString(obj) as T
  }

  if (typeof obj === 'number' || typeof obj === 'boolean') {
    return obj
  }

  if (Array.isArray(obj)) {
    return obj.map(item => redactObject(item)) as T
  }

  if (typeof obj === 'object') {
    const result: Record<string, unknown> = {}
    for (const [key, value] of Object.entries(obj as Record<string, unknown>)) {
      // Redact known sensitive field names
      if (
        key === 'confirmToken' ||
        key === 'adminToken' ||
        key === 'X-Admin-Token' ||
        key === 'apiKey' ||
        key === 'api_key' ||
        key === 'secret' ||
        key === 'password'
      ) {
        result[key] = REDACTION_PLACEHOLDER
      } else {
        result[key] = redactObject(value)
      }
    }
    return result as T
  }

  return obj
}

/**
 * Check if a string contains sensitive values.
 */
export function containsSensitiveValue(value: string): boolean {
  for (const pattern of SENSITIVE_PATTERNS) {
    if (pattern.test(value)) {
      return true
    }
  }
  return false
}

/**
 * Known sensitive field names that should always be redacted.
 */
export const SENSITIVE_FIELDS = [
  'confirmToken',
  'adminToken',
  'X-Admin-Token',
  'apiKey',
  'api_key',
  'secret',
  'password',
]