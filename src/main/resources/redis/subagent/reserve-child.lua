local childField = 'child:' .. ARGV[1]
local toolCallField = 'tool_call:' .. ARGV[3]
local existingChildId = redis.call('HGET', KEYS[1], toolCallField)
if existingChildId then
  redis.call('EXPIRE', KEYS[1], tonumber(ARGV[10]))
  return {1, existingChildId, '', 1}
end
local existing = redis.call('HGET', KEYS[1], childField)
if existing then
  redis.call('EXPIRE', KEYS[1], tonumber(ARGV[10]))
  return {1, ARGV[1], '', 1}
end

local spawned = tonumber(redis.call('HGET', KEYS[1], 'spawned_total') or '0')
local inFlight = tonumber(redis.call('HGET', KEYS[1], 'in_flight') or '0')
local turnField = 'turn:' .. ARGV[5] .. ':spawn_attempts'
local turnSpawned = tonumber(redis.call('HGET', KEYS[1], turnField) or '0')

if turnSpawned >= tonumber(ARGV[8]) then
  redis.call('EXPIRE', KEYS[1], tonumber(ARGV[10]))
  return {0, ARGV[1], 'SPAWN_BUDGET_PER_USER_TURN', 0}
end
if spawned >= tonumber(ARGV[6]) then
  redis.call('EXPIRE', KEYS[1], tonumber(ARGV[10]))
  return {0, ARGV[1], 'MAX_SPAWN_PER_RUN', 0}
end
if inFlight >= tonumber(ARGV[7]) then
  redis.call('EXPIRE', KEYS[1], tonumber(ARGV[10]))
  return {0, ARGV[1], 'MAX_CONCURRENT_PER_RUN', 0}
end

redis.call('HINCRBY', KEYS[1], 'spawned_total', 1)
redis.call('HINCRBY', KEYS[1], 'in_flight', 1)
redis.call('HINCRBY', KEYS[1], turnField, 1)
redis.call('HSET', KEYS[1], childField, ARGV[9])
redis.call('HSET', KEYS[1], toolCallField, ARGV[1])
redis.call('EXPIRE', KEYS[1], tonumber(ARGV[10]))
return {1, ARGV[1], '', 0}
