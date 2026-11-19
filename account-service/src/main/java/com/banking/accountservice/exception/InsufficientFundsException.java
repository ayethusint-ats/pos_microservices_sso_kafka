package com.banking.accountservice.exception;

import java.math.BigDecimal;
import java.util.UUID;

public class InsufficientFundsException extends BusinessRuleException {

    public InsufficientFundsException(UUID accountId, BigDecimal balance, BigDecimal requested) {
        super("INSUFFICIENT_FUNDS",
                "Account '%s' has insufficient funds: balance %s, requested %s"
                        .formatted(accountId, balance.toPlainString(), requested.toPlainString()));
    }
}
