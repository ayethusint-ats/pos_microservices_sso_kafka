package com.banking.accountservice.exception;

import com.banking.accountservice.entity.AccountStatus;

import java.util.UUID;

public class AccountNotActiveException extends BusinessRuleException {

    public AccountNotActiveException(UUID accountId, AccountStatus status) {
        super("ACCOUNT_NOT_ACTIVE", "Account '%s' is %s".formatted(accountId, status));
    }
}
