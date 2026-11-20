package com.banking.transactionservice.service;

import com.banking.transactionservice.dto.TransactionResponse;
import com.banking.transactionservice.entity.AccountTransaction;
import com.banking.transactionservice.events.AccountTransactionEvent;
import com.banking.transactionservice.events.InvalidEventException;
import com.banking.transactionservice.repository.AccountTransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class TransactionHistoryService {

    private static final Logger log = LoggerFactory.getLogger(TransactionHistoryService.class);

    private final AccountTransactionRepository repository;

    public TransactionHistoryService(AccountTransactionRepository repository) {
        this.repository = repository;
    }

    /**
     * Idempotent: recording the same event twice leaves exactly one row.
     *
     * @return true if stored, false if it was a duplicate
     */
    public boolean record(AccountTransactionEvent event) {
        validate(event);
        if (repository.existsById(event.eventId())) {
            log.info("Duplicate event {} ignored (at-least-once delivery)", event.eventId());
            return false;
        }
        try {
            repository.saveAndFlush(new AccountTransaction(event.eventId(), event.accountId(), event.accountNumber(),
                    event.ownerId(), event.kind(), event.direction(), event.amount(), event.currency(),
                    event.balanceAfter(), event.paymentId(), event.occurredAt()));
            return true;
        } catch (DataIntegrityViolationException raceWithAnotherInsert) {
            // Two deliveries of the same event at the same moment: the primary key let only one win.
            log.info("Duplicate event {} ignored (concurrent insert)", event.eventId());
            return false;
        }
    }

    private static void validate(AccountTransactionEvent e) {
        if (e.eventId() == null || e.accountId() == null || e.ownerId() == null || e.amount() == null
                || e.kind() == null || e.direction() == null || e.occurredAt() == null || e.balanceAfter() == null) {
            throw new InvalidEventException("Incomplete account transaction event: " + e);
        }
    }

    // ------------------------------------------------------------------ queries

    @Transactional(readOnly = true)
    public Page<TransactionResponse> forOwner(String ownerId, UUID accountId, Pageable pageable) {
        Page<AccountTransaction> page = accountId == null
                ? repository.findByOwnerId(ownerId, pageable)
                : repository.findByOwnerIdAndAccountId(ownerId, accountId, pageable);
        return page.map(TransactionResponse::from);
    }

    @Transactional(readOnly = true)
    public Page<TransactionResponse> forStaff(String ownerId, UUID accountId, Pageable pageable) {
        if (accountId != null) {
            return repository.findByAccountId(accountId, pageable).map(TransactionResponse::from);
        }
        if (ownerId != null) {
            return repository.findByOwnerId(ownerId, pageable).map(TransactionResponse::from);
        }
        return repository.findAll(pageable).map(TransactionResponse::from);
    }
}
