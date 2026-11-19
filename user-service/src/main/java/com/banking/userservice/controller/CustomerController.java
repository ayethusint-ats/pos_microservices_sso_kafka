package com.banking.userservice.controller;

import com.banking.userservice.dto.CreateCustomerRequest;
import com.banking.userservice.dto.CustomerResponse;
import com.banking.userservice.dto.PageResponse;
import com.banking.userservice.dto.UpdateCustomerRequest;
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
 * HTTP layer only: maps URLs to service calls and sets status codes. No business logic.
 *
 * <p>Security arrives in Phases 5 and 8. Until then every endpoint is open.
 */
@RestController
@RequestMapping("/api/customers")
public class CustomerController {

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    /** 201 Created plus a (relative) Location header pointing to the new resource. */
    @PostMapping
    public ResponseEntity<CustomerResponse> create(@Valid @RequestBody CreateCustomerRequest request) {
        CustomerResponse created = customerService.create(request);
        // Relative on purpose: behind the gateway an absolute URL built from THIS request would
        // point at the internal service address. The client resolves it against the URL it called.
        URI location = UriComponentsBuilder.fromPath("/api/customers/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    @GetMapping("/{id}")
    public CustomerResponse getById(@PathVariable UUID id) {
        return customerService.getById(id);
    }

    /** Look up the profile that belongs to a Keycloak identity (JWT "sub"). */
    @GetMapping("/by-identity/{keycloakUserId}")
    public CustomerResponse getByKeycloakUserId(@PathVariable String keycloakUserId) {
        return customerService.getByKeycloakUserId(keycloakUserId);
    }

    /** Example: GET /api/customers?page=0&size=20&sort=lastName,asc */
    @GetMapping
    public PageResponse<CustomerResponse> list(
            @PageableDefault(size = 20, sort = "lastName", direction = Sort.Direction.ASC) Pageable pageable) {
        return customerService.list(pageable);
    }

    @PutMapping("/{id}")
    public CustomerResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateCustomerRequest request) {
        return customerService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        customerService.delete(id);
    }
}
