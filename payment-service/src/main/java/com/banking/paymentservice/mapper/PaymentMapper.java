package com.banking.paymentservice.mapper;

import com.banking.paymentservice.dto.PaymentResponse;
import com.banking.paymentservice.entity.Payment;

/** Entity → DTO. The API never exposes the JPA entity (no version, no lazy-loading surprises). */
public final class PaymentMapper {

    private PaymentMapper() {
    }

    public static PaymentResponse toResponse(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getPayerId(),
                payment.getSourceAccountId(),
                payment.getTargetAccountId(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getDescription(),
                payment.getStatus(),
                payment.getFailureCode(),
                payment.getFailureReason(),
                payment.getCreatedAt(),
                payment.getUpdatedAt());
    }
}
