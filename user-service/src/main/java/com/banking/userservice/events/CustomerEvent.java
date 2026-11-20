package com.banking.userservice.events;

import com.banking.userservice.entity.Customer;

import java.time.Instant;

/**
 * The CURRENT state of a customer profile, published to the compacted topic {@code banking.customer-events}.
 * Key = keycloakUserId. Each message replaces the previous one for that key ("event-carried state
 * transfer"): consumers keep a local copy and never need to call user-service.
 *
 * <p>Deliberately small: only what other services need (name and e-mail for notifications).
 * No phone, no address. Don't broadcast personal data nobody needs.
 */
public record CustomerEvent(
        String eventType,
        int schemaVersion,
        Instant occurredAt,
        String keycloakUserId,
        String firstName,
        String lastName,
        String email
) {
    public static final String UPSERTED = "CUSTOMER_UPSERTED";
    public static final int VERSION = 1;

    public static CustomerEvent of(Customer customer) {
        return new CustomerEvent(UPSERTED, VERSION, Instant.now(), customer.getKeycloakUserId(),
                customer.getFirstName(), customer.getLastName(), customer.getEmail());
    }
}
