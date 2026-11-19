package com.banking.accountservice.exception;

public class InvalidAmountException extends BusinessRuleException {

    public InvalidAmountException(String message) {
        super("INVALID_AMOUNT", message);
    }
}
