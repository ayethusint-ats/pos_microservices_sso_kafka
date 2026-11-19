package com.banking.paymentservice.repository;

import com.banking.paymentservice.entity.Payment;
import com.banking.paymentservice.entity.PaymentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    /** Ownership built into the query: someone else's payment is simply "not found". */
    Optional<Payment> findByIdAndPayerId(UUID id, String payerId);

    Page<Payment> findByPayerId(String payerId, Pageable pageable);

    Page<Payment> findByStatus(PaymentStatus status, Pageable pageable);
}
