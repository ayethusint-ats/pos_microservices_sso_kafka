package com.banking.transactionservice.repository;

import com.banking.transactionservice.entity.AccountTransaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AccountTransactionRepository extends JpaRepository<AccountTransaction, UUID> {

    Page<AccountTransaction> findByOwnerId(String ownerId, Pageable pageable);

    /** Ownership in the query: someone else's account simply has no transactions for you. */
    Page<AccountTransaction> findByOwnerIdAndAccountId(String ownerId, UUID accountId, Pageable pageable);

    Page<AccountTransaction> findByAccountId(UUID accountId, Pageable pageable);
}
