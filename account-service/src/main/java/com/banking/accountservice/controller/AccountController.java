package com.banking.accountservice.controller;

import com.banking.accountservice.dto.AccountResponse;
import com.banking.accountservice.dto.BalanceResponse;
import com.banking.accountservice.dto.MoneyRequest;
import com.banking.accountservice.dto.OpenAccountRequest;
import com.banking.accountservice.security.Authorization.CurrentUserId;
import com.banking.accountservice.security.Authorization.CustomerOnly;
import com.banking.accountservice.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Self-service API: a CUSTOMER working with THEIR OWN accounts.
 *
 * <p>The owner is always {@code @CurrentUserId} (the token's {@code sub}). There's no ownerId in
 * any URL or body. Asking for someone else's account ID gives 404, exactly like an unknown ID.
 *
 * <p>Staff (SUPPORT, ADMIN) use {@link AdminAccountController}. They can't deposit or withdraw:
 * moving a customer's money isn't an admin task (segregation of duties).
 */
@RestController
@RequestMapping("/api/accounts")
@CustomerOnly
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    public ResponseEntity<AccountResponse> open(@CurrentUserId String userId,
                                                @Valid @RequestBody OpenAccountRequest request) {
        AccountResponse created = accountService.open(userId, request.type(), request.currency(), userId);
        // Relative on purpose: behind the gateway an absolute URL built from THIS request would
        // point at the internal service address. The client resolves it against the URL it called.
        URI location = UriComponentsBuilder.fromPath("/api/accounts/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @GetMapping
    public List<AccountResponse> myAccounts(@CurrentUserId String userId) {
        return accountService.listOwnedBy(userId);
    }

    @GetMapping("/{id}")
    public AccountResponse getById(@CurrentUserId String userId, @PathVariable UUID id) {
        return accountService.getOwned(id, userId);
    }

    @GetMapping("/{id}/balance")
    public BalanceResponse getBalance(@CurrentUserId String userId, @PathVariable UUID id) {
        return accountService.getOwnedBalance(id, userId);
    }

    @PostMapping("/{id}/deposit")
    public AccountResponse deposit(@CurrentUserId String userId, @PathVariable UUID id,
                                   @Valid @RequestBody MoneyRequest request) {
        return accountService.deposit(id, userId, request.amount());
    }

    @PostMapping("/{id}/withdraw")
    public AccountResponse withdraw(@CurrentUserId String userId, @PathVariable UUID id,
                                    @Valid @RequestBody MoneyRequest request) {
        return accountService.withdraw(id, userId, request.amount());
    }
}
