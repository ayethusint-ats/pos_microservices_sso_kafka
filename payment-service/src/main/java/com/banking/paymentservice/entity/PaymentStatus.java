package com.banking.paymentservice.entity;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle of a transfer: a SAGA of local steps, each one recorded before the next starts.
 * Each value answers one question for support staff: "did money move, and is that a problem?"
 *
 * <pre>
 *   PENDING ──validation/debit refused──────────────► REJECTED              nothing moved
 *      │    ──account-service unreachable───────────► FAILED                nothing moved
 *      │    ──debit unknown, then VOIDED (fenced)───► FAILED                nothing moved
 *      ▼ debit OK (or confirmed later by the fence)
 *   DEBITED ──credit OK─────────────────────────────► COMPLETED             moved
 *      │    ──credit refused (e.g. target frozen)
 *      ▼
 *   COMPENSATING ──refund OK────────────────────────► REVERSED              moved and returned
 *                ──a late credit had landed─────────► COMPLETED             moved
 *
 *   any unfinished state ──recovery gave up / refund refused──► NEEDS_RECONCILIATION   a person must check
 * </pre>
 *
 * Phase 13: PENDING, DEBITED and COMPENSATING are no longer "stuck": if a step's outcome is
 * unknown, the payment stays there and PaymentRecoveryJob drives it forward. Every step is
 * idempotent, so repeating one is always safe.
 */
public enum PaymentStatus {
    PENDING,
    DEBITED,
    COMPENSATING,
    COMPLETED,
    REJECTED,
    FAILED,
    REVERSED,
    NEEDS_RECONCILIATION;

    /** Unfinished statuses: the saga (live request or recovery job) still has work to do. */
    public static final Set<PaymentStatus> IN_PROGRESS = EnumSet.of(PENDING, DEBITED, COMPENSATING);

    public boolean isFinal() {
        return !IN_PROGRESS.contains(this);
    }

    boolean canMoveTo(PaymentStatus next) {
        return allowedNext().contains(next);
    }

    private Set<PaymentStatus> allowedNext() {
        return switch (this) {
            case PENDING -> EnumSet.of(DEBITED, REJECTED, FAILED, NEEDS_RECONCILIATION);
            case DEBITED -> EnumSet.of(COMPLETED, COMPENSATING, NEEDS_RECONCILIATION);
            case COMPENSATING -> EnumSet.of(REVERSED, COMPLETED, NEEDS_RECONCILIATION);
            // Final. Nobody "fixes" a payment by changing its status: corrections are new payments.
            case COMPLETED, REJECTED, FAILED, REVERSED, NEEDS_RECONCILIATION -> EnumSet.noneOf(PaymentStatus.class);
        };
    }
}
