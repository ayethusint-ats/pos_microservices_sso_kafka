package com.banking.accountservice.dto;

import com.banking.accountservice.entity.AccountStatus;

import java.util.UUID;

/**
 * What another SERVICE may know about any account: enough to address and credit it.
 * No owner, no balance: payment-service has no need to know them (data minimization).
 */
public record AccountSummary(
        UUID id,
        String accountNumber,
        String currency,
        AccountStatus status
) {
}
