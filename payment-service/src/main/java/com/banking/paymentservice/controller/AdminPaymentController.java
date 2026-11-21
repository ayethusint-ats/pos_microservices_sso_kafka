package com.banking.paymentservice.controller;

import com.banking.paymentservice.dto.PageResponse;
import com.banking.paymentservice.dto.PaymentResponse;
import com.banking.paymentservice.entity.PaymentStatus;
import com.banking.paymentservice.security.Authorization.StaffReadAccess;
import com.banking.paymentservice.service.PaymentService;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Back office: every payment, and above all the work queue {@code ?status=NEEDS_RECONCILIATION}:
 * payments the recovery job (Phase 13) handed over to people. {@code PENDING}, {@code DEBITED} and
 * {@code COMPENSATING} are in progress and normally finish by themselves.
 * Read-only for both SUPPORT and ADMIN: nobody "fixes" a payment by editing a row.
 */
@RestController
@RequestMapping("/api/admin/payments")
@StaffReadAccess
public class AdminPaymentController {

    private final PaymentService paymentService;

    public AdminPaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @GetMapping
    public PageResponse<PaymentResponse> list(
            @RequestParam(required = false) PaymentStatus status,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return PageResponse.from(paymentService.list(status, pageable));
    }

    @GetMapping("/{id}")
    public PaymentResponse getById(@PathVariable UUID id) {
        return paymentService.getById(id);
    }
}
