package com.banking.userservice.service;

import com.banking.userservice.dto.AddressDto;
import com.banking.userservice.dto.CreateCustomerRequest;
import com.banking.userservice.dto.CustomerProfileRequest;
import com.banking.userservice.dto.CustomerResponse;
import com.banking.userservice.dto.PageResponse;
import com.banking.userservice.entity.Customer;
import com.banking.userservice.exception.DuplicateResourceException;
import com.banking.userservice.exception.ResourceNotFoundException;
import com.banking.userservice.mapper.CustomerMapper;
import com.banking.userservice.repository.CustomerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.banking.userservice.events.CustomerChanged;
import com.banking.userservice.events.CustomerEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

/**
 * Customer profile use cases. Self-service methods are keyed by the caller's identity
 * ({@code keycloakUserId} = token {@code sub}). Back-office methods are keyed by profile ID and
 * record the acting admin in the audit log.
 */
@Service
@Transactional(readOnly = true)
public class CustomerService {

    private static final Logger audit = LoggerFactory.getLogger("AUDIT");
    private static final String RESOURCE = "Customer";

    private final CustomerRepository customerRepository;
    private final CustomerMapper customerMapper;
    private final ApplicationEventPublisher events;

    public CustomerService(CustomerRepository customerRepository, CustomerMapper customerMapper,
                           ApplicationEventPublisher events) {
        this.customerRepository = customerRepository;
        this.customerMapper = customerMapper;
        this.events = events;
    }

    // ---------------------------------------------------------------- self-service

    /** A customer creates THEIR profile. The identity comes from the token, never from the body. */
    @Transactional
    public CustomerResponse createForIdentity(String keycloakUserId, CustomerProfileRequest profile) {
        return create(keycloakUserId, profile.firstName(), profile.lastName(), profile.email(),
                profile.phoneNumber(), profile.address(), keycloakUserId);
    }

    public CustomerResponse getByKeycloakUserId(String keycloakUserId) {
        return customerMapper.toResponse(findByIdentityOrThrow(keycloakUserId));
    }

    @Transactional
    public CustomerResponse updateByKeycloakUserId(String keycloakUserId, CustomerProfileRequest profile) {
        return applyUpdate(findByIdentityOrThrow(keycloakUserId), profile, keycloakUserId);
    }

    // ---------------------------------------------------------------- back office

    /** An ADMIN onboards a customer (e.g. after branch KYC) for an existing Keycloak identity. */
    @Transactional
    public CustomerResponse createOnBehalf(CreateCustomerRequest request, String actorId) {
        return create(request.keycloakUserId(), request.firstName(), request.lastName(), request.email(),
                request.phoneNumber(), request.address(), actorId);
    }

    public CustomerResponse getById(UUID id) {
        return customerMapper.toResponse(findOrThrow(id));
    }

    public PageResponse<CustomerResponse> list(Pageable pageable) {
        return PageResponse.from(customerRepository.findAll(pageable).map(customerMapper::toResponse));
    }

    @Transactional
    public CustomerResponse update(UUID id, CustomerProfileRequest profile, String actorId) {
        return applyUpdate(findOrThrow(id), profile, actorId);
    }

    /** Hard delete keeps the demo simple. Real banks deactivate and retain records (regulation). */
    @Transactional
    public void delete(UUID id, String actorId) {
        Customer customer = findOrThrow(id);
        customerRepository.delete(customer);
        audit.info("action=DELETE_CUSTOMER actor={} customer={} identity={}", actorId, id, customer.getKeycloakUserId());
        events.publishEvent(new CustomerChanged(customer.getKeycloakUserId(), null));   // tombstone
    }

    // ---------------------------------------------------------------- shared logic

    private CustomerResponse create(String keycloakUserId, String firstName, String lastName, String email,
                                    String phoneNumber, AddressDto address, String actorId) {
        String normalizedEmail = normalizeEmail(email);
        if (customerRepository.existsByKeycloakUserId(keycloakUserId)) {
            throw new DuplicateResourceException(
                    "A customer profile already exists for identity '%s'".formatted(keycloakUserId));
        }
        if (customerRepository.existsByEmail(normalizedEmail)) {
            throw new DuplicateResourceException("Email '%s' is already in use".formatted(normalizedEmail));
        }

        Customer customer = customerRepository.saveAndFlush(new Customer(
                keycloakUserId, firstName.trim(), lastName.trim(), normalizedEmail, phoneNumber,
                customerMapper.toEntity(address)));
        audit.info("action=CREATE_CUSTOMER actor={} customer={} identity={}", actorId, customer.getId(), keycloakUserId);
        announce(customer);
        return customerMapper.toResponse(customer);
    }

    private CustomerResponse applyUpdate(Customer customer, CustomerProfileRequest profile, String actorId) {
        String email = normalizeEmail(profile.email());
        if (customerRepository.existsByEmailAndIdNot(email, customer.getId())) {
            throw new DuplicateResourceException("Email '%s' is already in use".formatted(email));
        }
        // No save() needed: the entity is managed, so dirty checking issues the UPDATE on flush.
        customer.updateProfile(profile.firstName().trim(), profile.lastName().trim(), email,
                profile.phoneNumber(), customerMapper.toEntity(profile.address()));
        customerRepository.flush();
        audit.info("action=UPDATE_CUSTOMER actor={} customer={}", actorId, customer.getId());
        announce(customer);
        return customerMapper.toResponse(customer);
    }

    /** Sent to Kafka by CustomerEventPublisher only after this transaction commits. */
    private void announce(Customer customer) {
        events.publishEvent(new CustomerChanged(customer.getKeycloakUserId(), CustomerEvent.of(customer)));
    }

    private Customer findOrThrow(UUID id) {
        return customerRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException(RESOURCE, id));
    }

    private Customer findByIdentityOrThrow(String keycloakUserId) {
        return customerRepository.findByKeycloakUserId(keycloakUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer profile for identity", keycloakUserId));
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
