local raw = redis.call('HGET', KEYS[1], ARGV[1])
if not raw then
  return 0
end
local state = cjson.decode(raw)
local terminal = cjson.decode(ARGV[4])
if (redis.call('HEXISTS', KEYS[3], 'abort_requested') == 1
  or redis.call('HEXISTS', KEYS[3], 'interrupt_requested') == 1)
  and terminal['status'] ~= 'CANCELLED'
  and state['call']['idempotent'] == true then
  return 0
end
if state['status'] ~= 'RUNNING' then
  return 0
end
if tostring(state['attempt']) ~= ARGV[2] then
  return 0
end
if state['leaseToken'] ~= ARGV[3] then
  return 0
end
state['status'] = terminal['status']
state['resultJson'] = terminal['resultJson']
state['errorJson'] = terminal['errorJson']
state['cancelReason'] = terminal['cancelReason']
redis.call('HSET', KEYS[1], ARGV[1], cjson.encode(state))
redis.call('ZREM', KEYS[2], ARGV[1])
return 1
