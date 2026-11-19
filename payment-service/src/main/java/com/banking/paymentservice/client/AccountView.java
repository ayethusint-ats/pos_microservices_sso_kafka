package com.banking.paymentservice.client;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The fields of account-service's AccountResponse that payment-service actually uses.
 *
 * <p>"Tolerant reader": unknown JSON fields are ignored (Spring Boot's default ObjectMapper
 * has FAIL_ON_UNKNOWN_PROPERTIES off), so account-service may add fields freely.
 */
public record AccountView(
        UUID id,
        String ownerId,
        String currency,
        BigDecimal balance,
        String status
) {
    public boolean isActive() {
        return "ACTIVE".equals(status);
    }
}
