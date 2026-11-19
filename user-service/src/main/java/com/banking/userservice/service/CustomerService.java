package com.banking.userservice.service;

import com.banking.userservice.dto.CreateCustomerRequest;
import com.banking.userservice.dto.CustomerResponse;
import com.banking.userservice.dto.PageResponse;
import com.banking.userservice.dto.UpdateCustomerRequest;
import com.banking.userservice.entity.Customer;
import com.banking.userservice.exception.DuplicateResourceException;
import com.banking.userservice.exception.ResourceNotFoundException;
import com.banking.userservice.mapper.CustomerMapper;
import com.banking.userservice.repository.CustomerRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

/**
 * Business logic for customer profiles. Controllers stay thin and repositories stay dumb.
 *
 * <p>Class-level {@code @Transactional(readOnly = true)} is the default. Methods that write
 * override it with a plain {@code @Transactional}.
 */
@Service
@Transactional(readOnly = true)
public class CustomerService {

    private static final String RESOURCE = "Customer";

    private final CustomerRepository customerRepository;
    private final CustomerMapper customerMapper;

    // Constructor injection: dependencies are explicit, final, and easy to mock in tests.
    public CustomerService(CustomerRepository customerRepository, CustomerMapper customerMapper) {
        this.customerRepository = customerRepository;
        this.customerMapper = customerMapper;
    }

    @Transactional
    public CustomerResponse create(CreateCustomerRequest request) {
        String email = normalizeEmail(request.email());

        if (customerRepository.existsByKeycloakUserId(request.keycloakUserId())) {
            throw new DuplicateResourceException(
                    "A customer profile already exists for identity '%s'".formatted(request.keycloakUserId()));
        }
        if (customerRepository.existsByEmail(email)) {
            throw new DuplicateResourceException("Email '%s' is already in use".formatted(email));
        }

        Customer customer = new Customer(
                request.keycloakUserId(),
                request.firstName().trim(),
                request.lastName().trim(),
                email,
                request.phoneNumber(),
                customerMapper.toEntity(request.address()));

        // saveAndFlush sends the INSERT now, so DB constraint errors and generated
        // timestamps are available here rather than at commit time.
        return customerMapper.toResponse(customerRepository.saveAndFlush(customer));
    }

    public CustomerResponse getById(UUID id) {
        return customerMapper.toResponse(findOrThrow(id));
    }

    public CustomerResponse getByKeycloakUserId(String keycloakUserId) {
        return customerRepository.findByKeycloakUserId(keycloakUserId)
                .map(customerMapper::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE, keycloakUserId));
    }

    public PageResponse<CustomerResponse> list(Pageable pageable) {
        return PageResponse.from(customerRepository.findAll(pageable).map(customerMapper::toResponse));
    }

    @Transactional
    public CustomerResponse update(UUID id, UpdateCustomerRequest request) {
        Customer customer = findOrThrow(id);
        String email = normalizeEmail(request.email());

        if (customerRepository.existsByEmailAndIdNot(email, id)) {
            throw new DuplicateResourceException("Email '%s' is already in use".formatted(email));
        }

        // No explicit save() needed: the entity is "managed", so JPA dirty checking
        // detects the changes and issues an UPDATE on flush.
        customer.updateProfile(
                request.firstName().trim(),
                request.lastName().trim(),
                email,
                request.phoneNumber(),
                customerMapper.toEntity(request.address()));
        customerRepository.flush();
        return customerMapper.toResponse(customer);
    }

    @Transactional
    public void delete(UUID id) {
        customerRepository.delete(findOrThrow(id));
    }

    private Customer findOrThrow(UUID id) {
        return customerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE, id));
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
