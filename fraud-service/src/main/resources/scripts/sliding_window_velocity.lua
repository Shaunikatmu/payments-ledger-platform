-- Sliding-window-log velocity counter, same technique used for LinkPulse's
-- rate limiter: a Redis sorted set keyed per account, scored by timestamp.
-- The member is the event's own id (not the timestamp) so two events in
-- the same millisecond don't collide -- and re-running this for the same
-- eventId is naturally a no-op (ZADD with an unchanged score/member),
-- which makes velocity tracking idempotent under Kafka redelivery for
-- free.
-- KEYS[1] = velocity:{accountId}
-- ARGV[1] = current timestamp (ms), used as the score
-- ARGV[2] = window start timestamp (ms) -- entries older than this are dropped
-- ARGV[3] = key TTL in seconds (keeps idle accounts' keys from lingering forever)
-- ARGV[4] = this event's unique id, used as the sorted-set member
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[2])
redis.call('ZADD', KEYS[1], ARGV[1], ARGV[4])
redis.call('EXPIRE', KEYS[1], ARGV[3])
return redis.call('ZCARD', KEYS[1])
