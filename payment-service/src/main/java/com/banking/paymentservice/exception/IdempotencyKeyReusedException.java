package com.banking.paymentservice.exception;

/**
 * The Idempotency-Key was already used for a DIFFERENT request. Replaying the old payment would
 * hide the client's bug; executing the new one would break the key's promise. So: 422, and nothing happens.
 */
public class IdempotencyKeyReusedException extends RuntimeException {

    public static final String CODE = "IDEMPOTENCY_KEY_REUSED";

    public IdempotencyKeyReusedException(String key) {
        super("Idempotency-Key '%s' was already used for a different request".formatted(key));
    }
}
