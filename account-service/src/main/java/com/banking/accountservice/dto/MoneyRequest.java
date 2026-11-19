package com.banking.accountservice.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/** Body for deposit and withdraw. BigDecimal keeps "0.10" exact; a double would not. */
public record MoneyRequest(
        @NotNull @Positive @Digits(integer = 15, fraction = 2) BigDecimal amount
) {
}
