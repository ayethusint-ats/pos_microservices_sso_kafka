package com.banking.paymentservice.client;

import java.math.BigDecimal;

/** Body of account-service's deposit/withdraw endpoints. */
public record MoneyCommand(BigDecimal amount) {
}
