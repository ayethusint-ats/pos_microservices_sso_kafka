package com.banking.transactionservice.controller;

import com.banking.transactionservice.dto.PageResponse;
import com.banking.transactionservice.dto.TransactionResponse;
import com.banking.transactionservice.security.Authorization.StaffReadAccess;
import com.banking.transactionservice.service.TransactionHistoryService;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Back office: any customer's or account's movements (read-only for SUPPORT and ADMIN). */
@RestController
@RequestMapping("/api/admin/transactions")
@StaffReadAccess
public class AdminTransactionController {

    private final TransactionHistoryService history;

    public AdminTransactionController(TransactionHistoryService history) {
        this.history = history;
    }

    @GetMapping
    public PageResponse<TransactionResponse> list(
            @RequestParam(required = false) String ownerId,
            @RequestParam(required = false) UUID accountId,
            @PageableDefault(size = 20, sort = "occurredAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return PageResponse.from(history.forStaff(ownerId, accountId, pageable));
    }
}
