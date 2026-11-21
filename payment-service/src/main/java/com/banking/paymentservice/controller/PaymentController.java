package com.banking.paymentservice.controller;

import com.banking.paymentservice.dto.PageResponse;
import com.banking.paymentservice.dto.PaymentResponse;
import com.banking.paymentservice.dto.TransferRequest;
import com.banking.paymentservice.exception.InvalidIdempotencyKeyException;
import com.banking.paymentservice.entity.PaymentStatus;
import com.banking.paymentservice.security.Authorization.CurrentUserId;
import com.banking.paymentservice.security.Authorization.CustomerOnly;
import com.banking.paymentservice.service.PaymentService;
import com.banking.paymentservice.service.TransferResult;
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
import org.springframework.web.bind.annotation.RequestHeader;
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
 *   202 Accepted  PENDING / DEBITED / COMPENSATING: accepted, being finished automatically (recovery job);
 *                 NEEDS_RECONCILIATION: a person resolves it. Poll GET /api/payments/{id}.
 *   422           REJECTED, or REVERSED (the credit was refused, the money was returned): nothing moved
 *   503           FAILED: account-service unavailable, nothing moved. Retry later with a NEW Idempotency-Key.
 * </pre>
 *
 * <p>Phase 13, {@code Idempotency-Key} header (optional, recommended): a retry with the same key
 * gets the stored outcome of the first request (same status code, header {@code Idempotent-Replayed: true})
 * instead of a second transfer.
 */
@RestController
@RequestMapping("/api/payments")
@CustomerOnly
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    public static final String IDEMPOTENT_REPLAYED = "Idempotent-Replayed";
    /** e.g. a UUID. Long enough to be unique per client, short enough to index. */
    private static final java.util.regex.Pattern VALID_KEY = java.util.regex.Pattern.compile("[A-Za-z0-9_-]{8,100}");

    @PostMapping
    public ResponseEntity<PaymentResponse> transfer(
            @CurrentUserId String userId,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {
        // Checked by hand: a constraint annotation here would switch the whole method to Spring's
        // method validation, which reports the body's field errors in a different JSON shape.
        if (idempotencyKey != null && !VALID_KEY.matcher(idempotencyKey).matches()) {
            throw new InvalidIdempotencyKeyException();
        }
        TransferResult result = paymentService.transfer(userId, idempotencyKey, request);
        PaymentResponse payment = result.payment();
        URI location = UriComponentsBuilder.fromPath("/api/payments/{id}").buildAndExpand(payment.id()).toUri();
        HttpStatus status = payment.status() == PaymentStatus.COMPLETED ? HttpStatus.CREATED : HttpStatus.ACCEPTED;
        ResponseEntity.BodyBuilder response = ResponseEntity.status(status).location(location);
        if (result.replayed()) {
            response.header(IDEMPOTENT_REPLAYED, "true");
        }
        return response.body(payment);
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
