package com.banking.paymentservice.client;

/**
 * account-service answered with a definite "no" (404 or 422).
 * The request was processed and refused, so nothing changed on its side.
 */
public class AccountRejectedException extends RuntimeException {

    private final int httpStatus;
    private final String code;

    public AccountRejectedException(int httpStatus, String code, String detail) {
        super(detail);
        this.httpStatus = httpStatus;
        this.code = code;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    /** e.g. INSUFFICIENT_FUNDS, ACCOUNT_NOT_ACTIVE, ACCOUNT_NOT_FOUND. */
    public String getCode() {
        return code;
    }

    public boolean isNotFound() {
        return httpStatus == 404;
    }
}
