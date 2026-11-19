package com.banking.paymentservice.client;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

import java.util.UUID;

/**
 * account-service's HTTP API as payment-service sees it: a declarative HTTP interface.
 *
 * <p>Spring generates the implementation at startup ({@code HttpServiceProxyFactory}): each
 * method becomes an HTTP request, and the JSON response is converted into the return type.
 * It's the Spring Framework 6 built-in alternative to OpenFeign; the annotations mirror
 * {@code @GetMapping}/{@code @PostMapping} on the server side.
 *
 * <p>Only the three operations a transfer needs are declared. This interface is payment-service's
 * own view of the contract, not a shared library: account-service can add fields or endpoints
 * without breaking us.
 */
@HttpExchange("/api/accounts")
public interface AccountApi {

    /** 404 when the account doesn't exist OR doesn't belong to the caller (ownership check). */
    @GetExchange("/{id}")
    AccountView getAccount(@PathVariable UUID id);

    /** 422 with code INSUFFICIENT_FUNDS / ACCOUNT_NOT_ACTIVE when the business rules say no. */
    @PostExchange("/{id}/withdraw")
    AccountView withdraw(@PathVariable UUID id, @RequestBody MoneyCommand command);

    @PostExchange("/{id}/deposit")
    AccountView deposit(@PathVariable UUID id, @RequestBody MoneyCommand command);
}
