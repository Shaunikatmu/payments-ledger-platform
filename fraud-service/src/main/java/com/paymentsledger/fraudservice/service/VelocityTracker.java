package com.paymentsledger.fraudservice.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Counts how many transfers an account has sent within a rolling time
 * window, using the same sliding-window-log Lua script technique as
 * LinkPulse's rate limiter (see sliding_window_velocity.lua), applied here
 * to fraud scoring instead of request throttling.
 */
@Component
public class VelocityTracker {

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> script;
    private final long windowSeconds;

    public VelocityTracker(StringRedisTemplate redisTemplate,
                            @Value("${fraud.rules.velocity.window-seconds:60}") long windowSeconds) {
        this.redisTemplate = redisTemplate;
        this.windowSeconds = windowSeconds;

        this.script = new DefaultRedisScript<>();
        this.script.setLocation(new ClassPathResource("scripts/sliding_window_velocity.lua"));
        this.script.setResultType(Long.class);
    }

    /**
     * Records this event and returns the number of transfers from
     * {@code accountId} within the current window, including this one.
     */
    public int recordAndCountRecentTransfers(UUID accountId, UUID eventId, Instant occurredAt) {
        String key = "velocity:" + accountId;
        long nowMillis = occurredAt.toEpochMilli();
        long windowStartMillis = nowMillis - (windowSeconds * 1000);

        Long count = redisTemplate.execute(
                script,
                List.of(key),
                String.valueOf(nowMillis),
                String.valueOf(windowStartMillis),
                String.valueOf(windowSeconds),
                eventId.toString()
        );

        return count == null ? 0 : count.intValue();
    }
}
