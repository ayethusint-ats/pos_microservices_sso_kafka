package com.banking.paymentservice.exception;

import com.banking.paymentservice.dto.PaymentResponse;

/**
 * The transfer did not happen (REJECTED, FAILED or REVERSED). The payment IS recorded, so
 * the error response includes its ID and the customer can look it up later.
 *
 * @param retryable true when the cause was account-service being unavailable, so trying again
 *                  later may succeed (HTTP 503); false for business refusals (HTTP 422)
 * @param replayed  the stored outcome of an earlier request with the same Idempotency-Key
 */
public class PaymentNotCompletedException extends RuntimeException {

    private final transient PaymentResponse payment;
    private final boolean retryable;
    private final boolean replayed;

    public PaymentNotCompletedException(PaymentResponse payment, boolean retryable, boolean replayed) {
        super(payment.failureReason());
        this.payment = payment;
        this.retryable = retryable;
        this.replayed = replayed;
    }

    public boolean isReplayed() {
        return replayed;
    }

    public PaymentResponse getPayment() {
        return payment;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
