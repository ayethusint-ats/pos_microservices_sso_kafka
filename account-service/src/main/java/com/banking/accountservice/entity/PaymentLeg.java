package com.banking.accountservice.entity;

/**
 * The two places where a payment touches balances. At most ONE operation per payment and leg
 * (the table's primary key), which makes every payment operation idempotent.
 */
public enum PaymentLeg {
    /** Taking the money from the source: TRANSFER_OUT, or VOID if payment-service fenced it off. */
    DEBIT,
    /** Where the money ends up: the target (TRANSFER_IN) OR back at the source (REFUND). Never both. */
    SETTLEMENT
}
