package com.banking.userservice.controller;

import com.banking.userservice.dto.CustomerProfileRequest;
import com.banking.userservice.dto.CustomerResponse;
import com.banking.userservice.security.Authorization.CurrentUserId;
import com.banking.userservice.security.Authorization.CustomerOnly;
import com.banking.userservice.service.CustomerService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * "/me" self-service: a customer reads and edits THEIR OWN profile.
 *
 * <p>The URL has no ID at all. The profile is found by the caller's identity (token {@code sub}),
 * so there's nothing a client could change to reach someone else's data. That's the simplest
 * way to avoid IDOR (Insecure Direct Object Reference) bugs.
 */
@RestController
@RequestMapping("/api/customers/me")
@CustomerOnly
public class MyProfileController {

    private final CustomerService customerService;

    public MyProfileController(CustomerService customerService) {
        this.customerService = customerService;
    }

    /** 404 means "signed in, but no profile yet": the UI then shows the registration form. */
    @GetMapping
    public CustomerResponse myProfile(@CurrentUserId String userId) {
        return customerService.getByKeycloakUserId(userId);
    }

    @PostMapping
    public ResponseEntity<CustomerResponse> createMyProfile(@CurrentUserId String userId,
                                                            @Valid @RequestBody CustomerProfileRequest profile) {
        return ResponseEntity.created(URI.create("/api/customers/me"))
                .body(customerService.createForIdentity(userId, profile));
    }

    @PutMapping
    public CustomerResponse updateMyProfile(@CurrentUserId String userId,
                                            @Valid @RequestBody CustomerProfileRequest profile) {
        return customerService.updateByKeycloakUserId(userId, profile);
    }
}
