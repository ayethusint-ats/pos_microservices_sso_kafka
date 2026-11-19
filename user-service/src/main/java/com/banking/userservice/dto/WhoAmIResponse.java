package com.banking.userservice.dto;

import java.time.Instant;
import java.util.List;

/** What this service knows about the caller after validating the token. For learning and debugging. */
public record WhoAmIResponse(
        String subject,
        String username,
        String clientId,
        List<String> audience,
        List<String> authorities,
        Instant issuedAt,
        Instant expiresAt
) {
}
