package com.banking.paymentservice.client;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * account-service's final answer to "was payment X debited?" (Phase 13).
 * VOIDED also means it never will be: a late debit request is refused.
 */
public record DebitResolution(UUID paymentId, Outcome outcome, UUID accountId, BigDecimal amount) {

    public enum Outcome { APPLIED, VOIDED }

    public boolean applied() {
        return outcome == Outcome.APPLIED;
    }
}
