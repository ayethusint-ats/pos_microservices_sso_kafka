package com.banking.userservice.controller;

import com.banking.userservice.dto.CreateCustomerRequest;
import com.banking.userservice.dto.CustomerProfileRequest;
import com.banking.userservice.dto.CustomerResponse;
import com.banking.userservice.dto.PageResponse;
import com.banking.userservice.security.Authorization.AdminOnly;
import com.banking.userservice.security.Authorization.CurrentUserId;
import com.banking.userservice.security.Authorization.StaffReadAccess;
import com.banking.userservice.service.CustomerService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

/**
 * Back-office customer management. SUPPORT can view customer information (for helping callers),
 * ADMIN can also create, edit and delete profiles.
 */
@RestController
@RequestMapping("/api/admin/customers")
public class AdminCustomerController {

    private final CustomerService customerService;

    public AdminCustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    /** Example: GET /api/admin/customers?page=0&size=20&sort=lastName,asc */
    @GetMapping
    @StaffReadAccess
    public PageResponse<CustomerResponse> list(
            @PageableDefault(size = 20, sort = "lastName", direction = Sort.Direction.ASC) Pageable pageable) {
        return customerService.list(pageable);
    }

    @GetMapping("/{id}")
    @StaffReadAccess
    public CustomerResponse getById(@PathVariable UUID id) {
        return customerService.getById(id);
    }

    @GetMapping("/by-identity/{keycloakUserId}")
    @StaffReadAccess
    public CustomerResponse getByKeycloakUserId(@PathVariable String keycloakUserId) {
        return customerService.getByKeycloakUserId(keycloakUserId);
    }

    @PostMapping
    @AdminOnly
    public ResponseEntity<CustomerResponse> create(@CurrentUserId String adminId,
                                                   @Valid @RequestBody CreateCustomerRequest request) {
        CustomerResponse created = customerService.createOnBehalf(request, adminId);
        URI location = UriComponentsBuilder.fromPath("/api/admin/customers/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/{id}")
    @AdminOnly
    public CustomerResponse update(@CurrentUserId String adminId, @PathVariable UUID id,
                                   @Valid @RequestBody CustomerProfileRequest profile) {
        return customerService.update(id, profile, adminId);
    }

    @DeleteMapping("/{id}")
    @AdminOnly
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@CurrentUserId String adminId, @PathVariable UUID id) {
        customerService.delete(id, adminId);
    }
}
