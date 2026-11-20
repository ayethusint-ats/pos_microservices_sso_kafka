package com.banking.transactionservice.events;

/** The message is readable JSON but not a valid event. Retrying can't help: straight to the dead-letter topic. */
public class InvalidEventException extends RuntimeException {

    public InvalidEventException(String message) {
        super(message);
    }
}
