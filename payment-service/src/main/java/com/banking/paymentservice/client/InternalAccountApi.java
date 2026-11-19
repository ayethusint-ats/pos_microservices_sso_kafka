package com.banking.paymentservice.client;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

import java.util.UUID;

/**
 * account-service's machine-to-machine API, called with payment-service's OWN token
 * (Client Credentials). Requires the "internal-transfers" permission.
 *
 * <p>Compare with {@link AccountApi}, called with the CUSTOMER's relayed token:
 * <pre>
 *   AccountApi          (Alice's token)     read own source account, withdraw   → proves Alice consents
 *   InternalAccountApi  (service's token)   find recipient by number, credit    → works for ANY recipient
 * </pre>
 */
@HttpExchange("/internal/accounts")
public interface InternalAccountApi {

    /** Minimal view (id, number, currency, status): no owner, no balance. */
    @GetExchange("/by-number/{accountNumber}")
    AccountView findByNumber(@PathVariable String accountNumber);

    @PostExchange("/{id}/credit")
    AccountView credit(@PathVariable UUID id, @RequestBody CreditCommand command);
}
