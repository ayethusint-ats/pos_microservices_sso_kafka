package com.banking.transactionservice.controller;

import com.banking.transactionservice.dto.PageResponse;
import com.banking.transactionservice.dto.TransactionResponse;
import com.banking.transactionservice.security.Authorization.CurrentUserId;
import com.banking.transactionservice.security.Authorization.CustomerOnly;
import com.banking.transactionservice.service.TransactionHistoryService;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * A customer's statement: every movement on THEIR accounts, incoming and outgoing.
 * Served from transaction-service's own database: no call to account-service, so it keeps
 * working even while account-service is down (it's just possibly a few events behind).
 */
@RestController
@RequestMapping("/api/transactions")
@CustomerOnly
public class TransactionController {

    private final TransactionHistoryService history;

    public TransactionController(TransactionHistoryService history) {
        this.history = history;
    }

    @GetMapping
    public PageResponse<TransactionResponse> myTransactions(
            @CurrentUserId String userId,
            @RequestParam(required = false) UUID accountId,
            @PageableDefault(size = 20, sort = "occurredAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return PageResponse.from(history.forOwner(userId, accountId, pageable));
    }
}
