package com.banking.paymentservice.entity;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle of a transfer. Each value answers one question for support staff:
 * "did money move, and is that a problem?"
 *
 * <pre>
 *   PENDING ──validation/debit refused──────────────► REJECTED              nothing moved
 *      │    ──account-service unreachable───────────► FAILED                nothing moved
 *      │    ──debit outcome unknown (timeout, 5xx)──► NEEDS_RECONCILIATION  maybe debited
 *      ▼ debit OK
 *   DEBITED ──credit OK─────────────────────────────► COMPLETED             moved
 *           ──credit refused, refund OK─────────────► REVERSED              moved and returned
 *           ──credit unknown, or refund failed──────► NEEDS_RECONCILIATION  a human must check
 * </pre>
 *
 * DEBITED is persisted on its own: if payment-service crashes between debit and credit, the
 * record stays DEBITED, so the stuck money is visible instead of silently lost.
 */
public enum PaymentStatus {
    PENDING,
    DEBITED,
    COMPLETED,
    REJECTED,
    FAILED,
    REVERSED,
    NEEDS_RECONCILIATION;

    public boolean isFinal() {
        return this != PENDING && this != DEBITED;
    }

    boolean canMoveTo(PaymentStatus next) {
        return allowedNext().contains(next);
    }

    private Set<PaymentStatus> allowedNext() {
        return switch (this) {
            case PENDING -> EnumSet.of(DEBITED, REJECTED, FAILED, NEEDS_RECONCILIATION);
            case DEBITED -> EnumSet.of(COMPLETED, REVERSED, NEEDS_RECONCILIATION);
            // Final states. Resolving NEEDS_RECONCILIATION is a back-office process (Phase 13).
            case COMPLETED, REJECTED, FAILED, REVERSED, NEEDS_RECONCILIATION -> EnumSet.noneOf(PaymentStatus.class);
        };
    }
}
