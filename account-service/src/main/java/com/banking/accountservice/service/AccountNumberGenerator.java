package com.banking.accountservice.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Generates random 10-digit account numbers.
 *
 * <p>SecureRandom makes numbers unguessable, so an attacker can't enumerate accounts.
 * Real banks also add a check digit (e.g. IBAN mod-97) to catch typos. A separate bean
 * keeps the service testable: tests can replace it with a predictable generator.
 */
@Component
public class AccountNumberGenerator {

    private static final long UPPER_BOUND = 10_000_000_000L;
    private final SecureRandom random = new SecureRandom();

    public String next() {
        return String.format("%010d", random.nextLong(UPPER_BOUND));
    }
}
