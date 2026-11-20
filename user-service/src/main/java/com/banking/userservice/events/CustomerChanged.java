package com.banking.userservice.events;

/**
 * In-process event raised inside the service transaction.
 *
 * @param state the new profile, or {@code null} when the profile was deleted (becomes a Kafka TOMBSTONE)
 */
public record CustomerChanged(String keycloakUserId, CustomerEvent state) {
}
