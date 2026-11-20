package com.banking.paymentservice.client;

import java.math.BigDecimal;
import java.util.UUID;

/** Body of account-service's withdraw endpoint. The payment ID labels the debit as TRANSFER_OUT (Phase 11). */
public record MoneyCommand(BigDecimal amount, UUID paymentId) {
}
