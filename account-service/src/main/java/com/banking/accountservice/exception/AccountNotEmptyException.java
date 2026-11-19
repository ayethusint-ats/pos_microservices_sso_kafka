package com.banking.accountservice.exception;

import java.math.BigDecimal;
import java.util.UUID;

public class AccountNotEmptyException extends BusinessRuleException {

    public AccountNotEmptyException(UUID accountId, BigDecimal balance) {
        super("BALANCE_NOT_ZERO",
                "Account '%s' cannot be closed while it holds %s".formatted(accountId, balance.toPlainString()));
    }
}
