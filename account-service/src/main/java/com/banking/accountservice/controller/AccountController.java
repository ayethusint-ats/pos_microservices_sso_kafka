package com.banking.accountservice.controller;

import com.banking.accountservice.dto.AccountResponse;
import com.banking.accountservice.dto.BalanceResponse;
import com.banking.accountservice.dto.CreateAccountRequest;
import com.banking.accountservice.dto.MoneyRequest;
import com.banking.accountservice.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Deposit and withdraw are modeled as POST "actions" on an account. This is pragmatic:
 * they aren't idempotent and they change state. A stricter REST design would POST to an
 * /accounts/{id}/transactions collection.
 */
@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    public ResponseEntity<AccountResponse> open(@Valid @RequestBody CreateAccountRequest request) {
        AccountResponse created = accountService.open(request);
        // Relative on purpose: behind the gateway an absolute URL built from THIS request would
        // point at the internal service address. The client resolves it against the URL it called.
        URI location = UriComponentsBuilder.fromPath("/api/accounts/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @GetMapping("/{id}")
    public AccountResponse getById(@PathVariable UUID id) {
        return accountService.getById(id);
    }

    /** Phase 2: ownerId is a query parameter. Phase 8: a CUSTOMER gets their own accounts from the JWT. */
    @GetMapping
    public List<AccountResponse> getByOwner(@RequestParam String ownerId) {
        return accountService.getByOwner(ownerId);
    }

    @GetMapping("/{id}/balance")
    public BalanceResponse getBalance(@PathVariable UUID id) {
        return accountService.getBalance(id);
    }

    @PostMapping("/{id}/deposit")
    public AccountResponse deposit(@PathVariable UUID id, @Valid @RequestBody MoneyRequest request) {
        return accountService.deposit(id, request.amount());
    }

    @PostMapping("/{id}/withdraw")
    public AccountResponse withdraw(@PathVariable UUID id, @Valid @RequestBody MoneyRequest request) {
        return accountService.withdraw(id, request.amount());
    }
}
