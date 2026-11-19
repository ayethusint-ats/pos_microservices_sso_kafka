package com.banking.paymentservice.exception;

import com.banking.paymentservice.dto.PaymentResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mapping.PropertyReferenceException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.LinkedHashMap;
import java.util.Map;

/** Maps exceptions to RFC 9457 Problem Details. Same approach as the other services. */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * The transfer didn't happen. The body still identifies the recorded payment:
     * <ul>
     *   <li>422: refused for a business reason (insufficient funds, wrong account, ...). Don't retry as is.</li>
     *   <li>503 + Retry-After: account-service was unavailable and nothing moved. Retrying later may work.</li>
     * </ul>
     */
    @ExceptionHandler(PaymentNotCompletedException.class)
    public ResponseEntity<ProblemDetail> handlePaymentNotCompleted(PaymentNotCompletedException ex) {
        PaymentResponse payment = ex.getPayment();
        HttpStatus status = ex.isRetryable() ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.UNPROCESSABLE_ENTITY;
        ProblemDetail problem = problem(status,
                ex.isRetryable() ? "Payment temporarily not possible" : "Payment rejected",
                payment.failureReason());
        problem.setProperty("code", payment.failureCode());
        problem.setProperty("paymentId", payment.id());
        problem.setProperty("paymentStatus", payment.status());

        ResponseEntity.BodyBuilder response = ResponseEntity.status(status);
        if (ex.isRetryable()) {
            response.header(HttpHeaders.RETRY_AFTER, "5");
        }
        return response.body(problem);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ProblemDetail handleNotFound(ResourceNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Resource not found", ex.getMessage());
    }

    /** e.g. {@code ?sort=doesNotExist}: the client's mistake, not a server error. */
    @ExceptionHandler(PropertyReferenceException.class)
    public ProblemDetail handleBadSort(PropertyReferenceException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid sort property", ex.getMessage());
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ProblemDetail handleOptimisticLock(ObjectOptimisticLockingFailureException ex) {
        return problem(HttpStatus.CONFLICT, "Concurrent modification",
                "The payment was modified by another request. Please reload.");
    }

    /** @PreAuthorize denials surface here, not in the security filter (see Phase 8). */
    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex) {
        return problem(HttpStatus.FORBIDDEN, "Forbidden", "You do not have permission to perform this operation");
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        log.error("Unexpected error", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error", "An unexpected error occurred");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            errors.putIfAbsent(error.getField(), error.getDefaultMessage());
        }
        ProblemDetail body = problem(HttpStatus.BAD_REQUEST, "Validation failed", "One or more fields are invalid");
        body.setProperty("errors", errors);
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
