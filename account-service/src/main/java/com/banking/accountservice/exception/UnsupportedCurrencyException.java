package com.banking.accountservice.exception;

public class UnsupportedCurrencyException extends BusinessRuleException {

    public UnsupportedCurrencyException(String currency) {
        super("UNSUPPORTED_CURRENCY", "Currency '%s' is not a valid ISO 4217 code".formatted(currency));
    }
}
