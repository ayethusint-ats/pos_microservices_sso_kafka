package com.banking.paymentservice.client;

/**
 * account-service gave no usable answer: unreachable, timed out, or an unexpected error.
 *
 * <p>The important question for a WRITE (withdraw/deposit) is {@link #isOutcomeUnknown()}:
 * <ul>
 *   <li>{@code false}: the request certainly did NOT take effect (the connection was never opened,
 *       or the server refused it with a 4xx). Safe to report "nothing happened".</li>
 *   <li>{@code true}: the request reached the service, and we don't know whether it committed
 *       (response timeout, connection dropped, 5xx). The money may or may not have moved.</li>
 * </ul>
 * For reads (GET) the distinction doesn't matter: a read changes nothing.
 */
public class AccountServiceUnavailableException extends RuntimeException {

    private final boolean outcomeUnknown;

    public AccountServiceUnavailableException(String message, boolean outcomeUnknown, Throwable cause) {
        super(message, cause);
        this.outcomeUnknown = outcomeUnknown;
    }

    public boolean isOutcomeUnknown() {
        return outcomeUnknown;
    }
}
