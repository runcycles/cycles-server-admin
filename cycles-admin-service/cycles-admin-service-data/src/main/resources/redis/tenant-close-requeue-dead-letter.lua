if redis.call('SREM', KEYS[1], ARGV[1]) == 1 then
  redis.call('SADD', KEYS[2], ARGV[1])
  redis.call('HDEL', KEYS[3], ARGV[1])
  redis.call('ZADD', KEYS[4], ARGV[3], ARGV[2])
  return 1
end
return 0
