package com.banking.userservice.repository;

import com.banking.userservice.entity.Customer;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data generates the implementation at startup. The SQL is derived from method names,
 * for example {@code existsByEmailAndIdNot} becomes
 * {@code select ... where email = ? and id <> ?}.
 */
public interface CustomerRepository extends JpaRepository<Customer, UUID> {

    Optional<Customer> findByKeycloakUserId(String keycloakUserId);

    boolean existsByKeycloakUserId(String keycloakUserId);

    boolean existsByEmail(String email);

    boolean existsByEmailAndIdNot(String email, UUID id);
}
