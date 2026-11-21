package com.banking.accountservice.exception;

import java.util.UUID;

/**
 * A payment operation that contradicts what this payment already did here (422 with a code).
 * These are the rules that make payments safe to retry, and impossible to abuse.
 */
public class PaymentOperationConflictException extends BusinessRuleException {

    /** The same payment ID was sent again with a different account or amount. */
    public static final String IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT";
    /** payment-service fenced off this payment's debit; a late debit request must not execute. */
    public static final String PAYMENT_VOIDED = "PAYMENT_VOIDED";
    /** Credit or refund without a recorded debit: would create money out of nothing. */
    public static final String PAYMENT_NOT_DEBITED = "PAYMENT_NOT_DEBITED";
    /** The payment was already credited (so it can't be refunded), or refunded (so it can't be credited). */
    public static final String PAYMENT_ALREADY_SETTLED = "PAYMENT_ALREADY_SETTLED";

    public PaymentOperationConflictException(String code, UUID paymentId, String message) {
        super(code, "Payment %s: %s".formatted(paymentId, message));
    }
}
