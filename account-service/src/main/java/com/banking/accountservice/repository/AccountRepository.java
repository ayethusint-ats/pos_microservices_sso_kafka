package com.banking.accountservice.repository;

import com.banking.accountservice.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    List<Account> findByOwnerIdOrderByCreatedAtAsc(String ownerId);

    /**
     * Ownership check in the query itself: {@code WHERE id = ? AND owner_id = ?}.
     * Someone else's account and a non-existent account look identical (empty), so the API can't
     * be used to discover which account IDs exist.
     */
    Optional<Account> findByIdAndOwnerId(UUID id, String ownerId);

    boolean existsByAccountNumber(String accountNumber);

    /** Phase 10: payment recipients are addressed by account number, like in real banking. */
    Optional<Account> findByAccountNumber(String accountNumber);
}
