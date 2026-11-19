package com.banking.accountservice.exception;

/**
 * A request that is well-formed but breaks a business rule (e.g. not enough money).
 * Mapped to HTTP 422 with a machine-readable {@code code}, so clients like payment-service
 * can react to the specific reason without parsing the message text.
 */
public abstract class BusinessRuleException extends RuntimeException {

    private final String code;

    protected BusinessRuleException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
