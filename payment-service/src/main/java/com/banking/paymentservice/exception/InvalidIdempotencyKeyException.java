package com.banking.paymentservice.exception;

/** The Idempotency-Key header is malformed (400). */
public class InvalidIdempotencyKeyException extends RuntimeException {

    public InvalidIdempotencyKeyException() {
        super("must be 8-100 characters: letters, digits, '-' or '_' (e.g. a UUID)");
    }
}
