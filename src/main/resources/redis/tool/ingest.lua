if redis.call('HSETNX', KEYS[3], ARGV[1], ARGV[2]) == 0 then
  return 0
end
local state = cjson.decode(ARGV[4])
if redis.call('HEXISTS', KEYS[4], 'abort_requested') == 1 then
  state['status'] = 'CANCELLED'
  state['cancelReason'] = 'RUN_ABORTED'
  state['errorJson'] = ARGV[5]
  redis.call('HSET', KEYS[2], ARGV[2], cjson.encode(state))
  return 1
end
if redis.call('HEXISTS', KEYS[4], 'interrupt_requested') == 1 then
  state['status'] = 'CANCELLED'
  state['cancelReason'] = 'INTERRUPTED'
  state['errorJson'] = ARGV[6]
  redis.call('HSET', KEYS[2], ARGV[2], cjson.encode(state))
  return 1
end
redis.call('ZADD', KEYS[1], ARGV[3], ARGV[2])
redis.call('HSET', KEYS[2], ARGV[2], ARGV[4])
return 1
