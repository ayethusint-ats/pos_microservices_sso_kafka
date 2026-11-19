package com.banking.paymentservice.client;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The fields of account-service's AccountResponse that payment-service actually uses.
 *
 * <p>"Tolerant reader": unknown JSON fields are ignored (Spring Boot's default ObjectMapper
 * has FAIL_ON_UNKNOWN_PROPERTIES off), so account-service may add fields freely. Fields the
 * response doesn't contain stay null: the internal API returns no ownerId and no balance.
 */
public record AccountView(
        UUID id,
        String accountNumber,
        String ownerId,
        String currency,
        BigDecimal balance,
        String status
) {
    public boolean isActive() {
        return "ACTIVE".equals(status);
    }
}
