package com.banking.gateway.ratelimit;

/**
 * The classic token-bucket algorithm.
 *
 * <pre>
 *   capacity = 20 tokens, refill = 10 tokens/second, each request costs 1 token
 *   idle user      → bucket full (20)      → can burst 20 requests at once
 *   sustained use  → about 10 requests/second allowed
 *   empty bucket   → request rejected (429) until tokens refill
 * </pre>
 * Refill is computed lazily from elapsed time, so no background timer is needed.
 * Methods are synchronized: one bucket per caller, so contention only happens on one user's
 * own concurrent requests.
 */
final class TokenBucket {

    private final double capacity;
    private final double refillPerNano;
    private double tokens;
    private long lastRefillNanos;
    private long lastUsedNanos;

    TokenBucket(int capacity, int refillPerSecond, long nowNanos) {
        this.capacity = capacity;
        this.refillPerNano = refillPerSecond / 1_000_000_000.0;
        this.tokens = capacity;
        this.lastRefillNanos = nowNanos;
        this.lastUsedNanos = nowNanos;
    }

    synchronized boolean tryConsume(long nowNanos) {
        refill(nowNanos);
        lastUsedNanos = nowNanos;
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return true;
        }
        return false;
    }

    synchronized long remaining() {
        return (long) Math.floor(tokens);
    }

    synchronized boolean idleSince(long thresholdNanos) {
        return lastUsedNanos < thresholdNanos;
    }

    private void refill(long nowNanos) {
        long elapsed = nowNanos - lastRefillNanos;
        if (elapsed > 0) {
            tokens = Math.min(capacity, tokens + elapsed * refillPerNano);
            lastRefillNanos = nowNanos;
        }
    }
}
