package com.banking.userservice.dto;

import java.time.Instant;
import java.util.UUID;

/** What the API returns. Internal fields (e.g. the JPA version) stay hidden. */
public record CustomerResponse(
        UUID id,
        String keycloakUserId,
        String firstName,
        String lastName,
        String email,
        String phoneNumber,
        AddressDto address,
        Instant createdAt,
        Instant updatedAt
) {
}
