package com.banking.paymentservice.client;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Body of account-service's internal credit endpoint. The payment ID is the ledger reference;
 * {@code refund} marks the money returned by a reversed payment (Phase 11).
 */
public record CreditCommand(BigDecimal amount, UUID paymentId, boolean refund) {
}
