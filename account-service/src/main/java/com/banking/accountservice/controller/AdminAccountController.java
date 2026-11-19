package com.banking.accountservice.controller;

import com.banking.accountservice.dto.AccountResponse;
import com.banking.accountservice.dto.AdminOpenAccountRequest;
import com.banking.accountservice.dto.ChangeStatusRequest;
import com.banking.accountservice.security.Authorization.AdminOnly;
import com.banking.accountservice.security.Authorization.CurrentUserId;
import com.banking.accountservice.security.Authorization.StaffReadAccess;
import com.banking.accountservice.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
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
 * Back-office API. SUPPORT can look, ADMIN can also manage. Nobody here can move money.
 *
 * <p>Two layers protect every method: the URL rule {@code /api/admin/** → SUPPORT or ADMIN}
 * in SecurityConfig, and the method annotation, which narrows writes to ADMIN.
 */
@RestController
@RequestMapping("/api/admin/accounts")
public class AdminAccountController {

    private final AccountService accountService;

    public AdminAccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @GetMapping
    @StaffReadAccess
    public List<AccountResponse> accountsOfCustomer(@RequestParam String ownerId) {
        return accountService.listOwnedBy(ownerId);
    }

    @GetMapping("/{id}")
    @StaffReadAccess
    public AccountResponse getById(@PathVariable UUID id) {
        return accountService.getById(id);
    }

    @PostMapping
    @AdminOnly
    public ResponseEntity<AccountResponse> openForCustomer(@CurrentUserId String adminId,
                                                           @Valid @RequestBody AdminOpenAccountRequest request) {
        AccountResponse created = accountService.open(request.ownerId(), request.type(), request.currency(), adminId);
        URI location = UriComponentsBuilder.fromPath("/api/admin/accounts/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    /** PATCH: changes one aspect (the status) of the account, not the whole resource. */
    @PatchMapping("/{id}/status")
    @AdminOnly
    public AccountResponse changeStatus(@CurrentUserId String adminId, @PathVariable UUID id,
                                        @Valid @RequestBody ChangeStatusRequest request) {
        return accountService.changeStatus(id, request.status(), request.reason(), adminId);
    }
}
