local ids = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1])
local terminals = {}
for _, id in ipairs(ids) do
  local raw = redis.call('HGET', KEYS[2], id)
  if raw then
    local state = cjson.decode(raw)
    local leaseUntil = tonumber(state['leaseUntil'] or '0')
    if state['status'] == 'RUNNING' and leaseUntil <= tonumber(ARGV[1]) then
      redis.call('ZREM', KEYS[1], id)
      if state['call']['idempotent'] == true then
        state['status'] = 'WAITING'
        state['leaseToken'] = cjson.null
        state['leaseUntil'] = cjson.null
        state['workerId'] = cjson.null
        redis.call('HSET', KEYS[2], id, cjson.encode(state))
      else
        state['status'] = 'CANCELLED'
        state['cancelReason'] = 'TOOL_TIMEOUT'
        state['errorJson'] = ARGV[2]
        redis.call('HSET', KEYS[2], id, cjson.encode(state))
        table.insert(terminals, cjson.encode({
          toolCallId = id,
          status = 'CANCELLED',
          resultJson = cjson.null,
          errorJson = ARGV[2],
          cancelReason = 'TOOL_TIMEOUT',
          synthetic = true
        }))
      end
    else
      redis.call('ZREM', KEYS[1], id)
    end
  else
    redis.call('ZREM', KEYS[1], id)
  end
end
return terminals
