package com.banking.gateway.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/** Gateway-specific settings, bound from {@code banking.gateway.*}. Invalid values stop startup. */
@Validated
@ConfigurationProperties(prefix = "banking.gateway")
public record GatewayProperties(@Valid Cors cors, @Valid RateLimit rateLimit) {

    public record Cors(@NotEmpty List<String> allowedOrigins) {
    }

    /**
     * Token bucket per caller: the bucket holds at most {@code burstCapacity} tokens and refills
     * at {@code replenishRate} tokens per second. Each request takes one token.
     */
    public record RateLimit(@Positive int replenishRate, @Positive int burstCapacity) {
    }
}
