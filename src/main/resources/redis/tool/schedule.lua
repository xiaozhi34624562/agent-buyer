if redis.call('HEXISTS', KEYS[4], 'abort_requested') == 1
  or redis.call('HEXISTS', KEYS[4], 'interrupt_requested') == 1 then
  return {}
end
local ids = redis.call('ZRANGE', KEYS[1], 0, tonumber(ARGV[4]) - 1)
local running = 0
local runningUnsafe = false
for _, id in ipairs(ids) do
  local raw = redis.call('HGET', KEYS[2], id)
  if raw then
    local state = cjson.decode(raw)
    if state['status'] == 'RUNNING' then
      running = running + 1
      if state['call']['isConcurrent'] == false then
        runningUnsafe = true
      end
    end
  end
end
if runningUnsafe then
  return {}
end
local capacity = tonumber(ARGV[3]) - running
if capacity <= 0 then
  return {}
end
local started = {}
local startedCount = 0
local now = tonumber(ARGV[1])
for _, id in ipairs(ids) do
  local raw = redis.call('HGET', KEYS[2], id)
  if raw then
    local state = cjson.decode(raw)
    local status = state['status']
    if status == 'WAITING' then
      local leaseMs = tonumber(ARGV[2])
      local rawCallTimeoutMs = state['call']['timeoutMs']
      local callTimeoutMs = 0
      if rawCallTimeoutMs ~= nil and rawCallTimeoutMs ~= cjson.null then
        callTimeoutMs = tonumber(rawCallTimeoutMs) or 0
      end
      if callTimeoutMs > leaseMs then
        leaseMs = callTimeoutMs
      end
      local leaseUntil = now + leaseMs
      local safe = state['call']['isConcurrent']
      if safe == false then
        if running == 0 and startedCount == 0 then
          state['status'] = 'RUNNING'
          state['attempt'] = (state['attempt'] or 0) + 1
          state['leaseToken'] = ARGV[6] .. ':' .. id .. ':' .. tostring(state['attempt'])
          state['leaseUntil'] = leaseUntil
          state['workerId'] = ARGV[5]
          local encoded = cjson.encode(state)
          redis.call('HSET', KEYS[2], id, encoded)
          redis.call('ZADD', KEYS[3], leaseUntil, id)
          table.insert(started, encoded)
        end
        return started
      else
        if capacity > 0 then
          state['status'] = 'RUNNING'
          state['attempt'] = (state['attempt'] or 0) + 1
          state['leaseToken'] = ARGV[6] .. ':' .. id .. ':' .. tostring(state['attempt'])
          state['leaseUntil'] = leaseUntil
          state['workerId'] = ARGV[5]
          local encoded = cjson.encode(state)
          redis.call('HSET', KEYS[2], id, encoded)
          redis.call('ZADD', KEYS[3], leaseUntil, id)
          table.insert(started, encoded)
          startedCount = startedCount + 1
          capacity = capacity - 1
        else
          return started
        end
      end
    end
  end
end
return started
