local ids = redis.call('ZRANGE', KEYS[1], 0, -1)
local terminals = {}
for _, id in ipairs(ids) do
  local raw = redis.call('HGET', KEYS[2], id)
  if raw then
    local state = cjson.decode(raw)
    if state['status'] == 'WAITING' then
      state['status'] = 'CANCELLED'
      state['cancelReason'] = ARGV[1]
      state['errorJson'] = ARGV[2]
      redis.call('HSET', KEYS[2], id, cjson.encode(state))
      table.insert(terminals, cjson.encode({
        toolCallId = id,
        status = 'CANCELLED',
        resultJson = cjson.null,
        errorJson = ARGV[2],
        cancelReason = ARGV[1],
        synthetic = true
      }))
    end
  end
end
return terminals
