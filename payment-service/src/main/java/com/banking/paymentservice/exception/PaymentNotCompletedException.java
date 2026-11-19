package com.banking.paymentservice.exception;

import com.banking.paymentservice.dto.PaymentResponse;

/**
 * The transfer did not happen (REJECTED, FAILED or REVERSED). The payment IS recorded, so
 * the error response includes its ID and the customer can look it up later.
 *
 * @param retryable true when the cause was account-service being unavailable, so trying again
 *                  later may succeed (HTTP 503); false for business refusals (HTTP 422)
 */
public class PaymentNotCompletedException extends RuntimeException {

    private final transient PaymentResponse payment;
    private final boolean retryable;

    public PaymentNotCompletedException(PaymentResponse payment, boolean retryable) {
        super(payment.failureReason());
        this.payment = payment;
        this.retryable = retryable;
    }

    public PaymentResponse getPayment() {
        return payment;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
