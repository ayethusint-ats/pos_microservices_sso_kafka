package com.banking.accountservice.dto;

import com.banking.accountservice.entity.AccountStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** ADMIN changes an account's status, e.g. freezing it during a fraud investigation. */
public record ChangeStatusRequest(
        @NotNull AccountStatus status,
        @Size(max = 200) String reason
) {
}
