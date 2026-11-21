package com.banking.paymentservice.service;

import com.banking.paymentservice.dto.PaymentResponse;

/**
 * @param replayed true when an earlier request with the same Idempotency-Key created this payment:
 *                 nothing was executed now, the stored result is returned (header Idempotent-Replayed)
 */
public record TransferResult(PaymentResponse payment, boolean replayed) {
}
