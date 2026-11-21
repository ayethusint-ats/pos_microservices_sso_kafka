package com.banking.accountservice.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Answer to "did payment X debit its source?", and a promise that the answer can't change anymore.
 *
 * @param outcome APPLIED (the money left the source account) or VOIDED (it never will)
 * @param accountId the debited account (APPLIED only)
 * @param amount    the debited amount (APPLIED only)
 */
public record DebitResolution(UUID paymentId, Outcome outcome, UUID accountId, BigDecimal amount) {

    public enum Outcome { APPLIED, VOIDED }
}
