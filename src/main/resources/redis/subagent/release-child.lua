local childField = 'child:' .. ARGV[1]
local raw = redis.call('HGET', KEYS[1], childField)
if not raw then
  redis.call('EXPIRE', KEYS[1], tonumber(ARGV[3]))
  return 0
end
local child = cjson.decode(raw)
if child['state'] ~= 'IN_FLIGHT' then
  redis.call('EXPIRE', KEYS[1], tonumber(ARGV[3]))
  return 0
end
local inFlight = tonumber(redis.call('HGET', KEYS[1], 'in_flight') or '0')
if inFlight > 0 then
  redis.call('HINCRBY', KEYS[1], 'in_flight', -1)
end
child['state'] = 'RELEASED'
child['releaseReason'] = ARGV[2]
child['parentLinkStatus'] = ARGV[4]
child['releasedAtEpochMs'] = tonumber(ARGV[5])
redis.call('HSET', KEYS[1], childField, cjson.encode(child))
redis.call('EXPIRE', KEYS[1], tonumber(ARGV[3]))
return 1
