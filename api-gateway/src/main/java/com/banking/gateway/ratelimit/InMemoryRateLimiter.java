package com.banking.gateway.ratelimit;

import com.banking.gateway.config.GatewayProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * A {@link RateLimiter} for Spring Cloud Gateway's RequestRateLimiter filter, with buckets kept in memory.
 *
 * <p><b>Trade-off, made consciously:</b> each gateway instance counts on its own. With 3 replicas
 * a user effectively gets 3× the limit. That's acceptable as abuse protection for this project and
 * needs no extra infrastructure. For an exact limit across replicas, use the built-in
 * {@code RedisRateLimiter} (shared counters in Redis). It's a config change in application.yml
 * ({@code rate-limiter: "#{@redisRateLimiter}"}) plus a Redis server.
 *
 * <p>One budget per caller across all routes (the {@code routeId} is ignored).
 */
@Component("inMemoryRateLimiter")
public class InMemoryRateLimiter implements RateLimiter<Object> {

    private static final int SWEEP_THRESHOLD = 10_000;
    private static final long IDLE_EVICTION_NANOS = Duration.ofMinutes(10).toNanos();

    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();
    private final int replenishRate;
    private final int burstCapacity;
    private final LongSupplier clock;

    /** Two constructors exist (the other is for tests with a fake clock), so tell Spring which one to use. */
    @Autowired
    public InMemoryRateLimiter(GatewayProperties properties) {
        this(properties.rateLimit().replenishRate(), properties.rateLimit().burstCapacity(), System::nanoTime);
    }

    InMemoryRateLimiter(int replenishRate, int burstCapacity, LongSupplier clock) {
        this.replenishRate = replenishRate;
        this.burstCapacity = burstCapacity;
        this.clock = clock;
    }

    @Override
    public Mono<Response> isAllowed(String routeId, String key) {
        long now = clock.getAsLong();
        evictIdleBucketsIfLarge(now);

        TokenBucket bucket = buckets.computeIfAbsent(key, k -> new TokenBucket(burstCapacity, replenishRate, now));
        boolean allowed = bucket.tryConsume(now);

        // These headers are copied onto the HTTP response by the RequestRateLimiter filter.
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("X-RateLimit-Remaining", String.valueOf(bucket.remaining()));
        headers.put("X-RateLimit-Burst-Capacity", String.valueOf(burstCapacity));
        headers.put("X-RateLimit-Replenish-Rate", String.valueOf(replenishRate));
        if (!allowed) {
            headers.put("Retry-After", "1");
        }
        return Mono.just(new Response(allowed, headers));
    }

    /** Keeps memory bounded: forget callers that have been idle for 10 minutes (their bucket would be full anyway). */
    private void evictIdleBucketsIfLarge(long now) {
        if (buckets.size() > SWEEP_THRESHOLD) {
            long threshold = now - IDLE_EVICTION_NANOS;
            buckets.entrySet().removeIf(entry -> entry.getValue().idleSince(threshold));
        }
    }

    // RateLimiter is also "StatefulConfigurable" (per-route config). We use one global config instead.
    @Override
    public Map<String, Object> getConfig() {
        return Map.of();
    }

    @Override
    public Class<Object> getConfigClass() {
        return Object.class;
    }

    @Override
    public Object newConfig() {
        return new Object();
    }
}
