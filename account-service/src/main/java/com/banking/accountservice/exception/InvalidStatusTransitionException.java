package com.banking.accountservice.exception;

import com.banking.accountservice.entity.AccountStatus;

import java.util.UUID;

public class InvalidStatusTransitionException extends BusinessRuleException {

    public InvalidStatusTransitionException(UUID accountId, AccountStatus from, AccountStatus to) {
        super("INVALID_STATUS_TRANSITION",
                "Account '%s' cannot change from %s to %s".formatted(accountId, from, to));
    }
}
