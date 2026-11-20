package com.banking.accountservice.controller;

import com.banking.accountservice.dto.AccountSummary;
import com.banking.accountservice.dto.CreditRequest;
import com.banking.accountservice.security.Authorization.CallingClient;
import com.banking.accountservice.security.Authorization.InternalTransfers;
import com.banking.accountservice.service.AccountService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Machine-to-machine API for payment-service (Client Credentials token).
 *
 * <p>Deliberately NARROW: find a recipient by account number, and credit an account.
 * There is no internal "withdraw": taking money out still requires the customer's own token
 * on {@code /api/accounts/{id}/withdraw}, so even a compromised payment-service can't
 * empty an account on its own.
 *
 * <p>Two independent guards: the URL rule {@code /internal/**} in SecurityConfig, and
 * {@code @InternalTransfers} here. Plus a network one: the gateway never routes {@code /internal}.
 */
@RestController
@RequestMapping("/internal/accounts")
@InternalTransfers
public class InternalAccountController {

    private final AccountService accountService;

    public InternalAccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping("/by-number/{accountNumber}")
    public AccountSummary findRecipient(@PathVariable @Pattern(regexp = "\\d{10}") String accountNumber) {
        return accountService.findRecipient(accountNumber);
    }

    @PostMapping("/{id}/credit")
    public AccountSummary credit(@CallingClient String clientId, @PathVariable UUID id,
                                 @Valid @RequestBody CreditRequest request) {
        return accountService.creditForPayment(id, request.amount(), request.paymentId(), request.refund(), clientId);
    }
}
