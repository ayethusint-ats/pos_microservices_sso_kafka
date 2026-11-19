package com.banking.paymentservice.controller;

import com.banking.paymentservice.dto.PageResponse;
import com.banking.paymentservice.dto.PaymentResponse;
import com.banking.paymentservice.dto.TransferRequest;
import com.banking.paymentservice.entity.PaymentStatus;
import com.banking.paymentservice.security.Authorization.CurrentUserId;
import com.banking.paymentservice.security.Authorization.CustomerOnly;
import com.banking.paymentservice.service.PaymentService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

/**
 * A customer's own transfers.
 *
 * <pre>
 *   201 Created   COMPLETED: the money moved
 *   202 Accepted  NEEDS_RECONCILIATION: recorded, but the outcome isn't known yet; the bank resolves it
 *   422           REJECTED (or REVERSED for a business reason): nothing moved
 *   503           FAILED (or REVERSED) because account-service was unavailable: nothing moved, retry later
 * </pre>
 */
@RestController
@RequestMapping("/api/payments")
@CustomerOnly
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping
    public ResponseEntity<PaymentResponse> transfer(@CurrentUserId String userId,
                                                    @Valid @RequestBody TransferRequest request) {
        PaymentResponse payment = paymentService.transfer(userId, request);
        URI location = UriComponentsBuilder.fromPath("/api/payments/{id}").buildAndExpand(payment.id()).toUri();
        HttpStatus status = payment.status() == PaymentStatus.COMPLETED ? HttpStatus.CREATED : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).location(location).body(payment);
    }

    @GetMapping
    public PageResponse<PaymentResponse> myPayments(
            @CurrentUserId String userId,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return PageResponse.from(paymentService.listOwnedBy(userId, pageable));
    }

    @GetMapping("/{id}")
    public PaymentResponse getById(@CurrentUserId String userId, @PathVariable UUID id) {
        return paymentService.getOwned(id, userId);
    }
}
