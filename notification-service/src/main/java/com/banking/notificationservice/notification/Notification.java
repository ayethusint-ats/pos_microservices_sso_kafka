package com.banking.notificationservice.notification;

import java.time.Instant;
import java.util.UUID;

/**
 * One (simulated) e-mail.
 *
 * @param to            the address, or null if the customer's profile isn't known (yet)
 * @param sourceEventId the event that caused it: the same event twice must not mail twice
 */
public record Notification(
        UUID id,
        String userId,
        String to,
        String subject,
        String body,
        UUID sourceEventId,
        Instant createdAt
) {
}
