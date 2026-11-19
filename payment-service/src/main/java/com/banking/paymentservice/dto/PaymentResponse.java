package com.banking.paymentservice.dto;

import com.banking.paymentservice.entity.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
        UUID id,
        String payerId,
        UUID sourceAccountId,
        UUID targetAccountId,
        String targetAccountNumber,
        BigDecimal amount,
        String currency,
        String description,
        PaymentStatus status,
        String failureCode,
        String failureReason,
        Instant createdAt,
        Instant updatedAt
) {
}
