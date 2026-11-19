package com.banking.gateway.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter.Response;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** Token bucket behavior with a fake clock: no sleeping, fully deterministic. */
class InMemoryRateLimiterTest {

    private static final long ONE_SECOND = 1_000_000_000L;

    private final AtomicLong now = new AtomicLong(0);
    private final InMemoryRateLimiter limiter = new InMemoryRateLimiter(2, 5, now::get);   // 2/s, burst 5

    @Test
    void allowsBurstUpToCapacityThenRejects() {
        for (int i = 0; i < 5; i++) {
            assertThat(allowed("alice")).isTrue();
        }
        Response rejected = limiter.isAllowed("route", "alice").block();
        assertThat(rejected.isAllowed()).isFalse();
        assertThat(rejected.getHeaders()).containsEntry("Retry-After", "1").containsEntry("X-RateLimit-Remaining", "0");
    }

    @Test
    void refillsOverTimeAtReplenishRate() {
        for (int i = 0; i < 5; i++) {
            allowed("bob");
        }
        assertThat(allowed("bob")).isFalse();

        now.addAndGet(ONE_SECOND);                 // +2 tokens
        assertThat(allowed("bob")).isTrue();
        assertThat(allowed("bob")).isTrue();
        assertThat(allowed("bob")).isFalse();
    }

    @Test
    void neverRefillsBeyondCapacity() {
        now.addAndGet(60 * ONE_SECOND);            // idle for a minute
        int granted = 0;
        while (allowed("carol")) {
            granted++;
        }
        assertThat(granted).isEqualTo(5);
    }

    @Test
    void eachCallerHasAnIndependentBudget() {
        for (int i = 0; i < 5; i++) {
            allowed("dave");
        }
        assertThat(allowed("dave")).isFalse();
        assertThat(allowed("erin")).isTrue();
    }

    private boolean allowed(String key) {
        return limiter.isAllowed("route", key).block().isAllowed();
    }
}
