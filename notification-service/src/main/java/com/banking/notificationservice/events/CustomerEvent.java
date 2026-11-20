package com.banking.notificationservice.events;

/** notification-service's own reading of user-service's contract (banking.customer-events, compacted). */
public record CustomerEvent(
        String keycloakUserId,
        String firstName,
        String lastName,
        String email
) {
}
