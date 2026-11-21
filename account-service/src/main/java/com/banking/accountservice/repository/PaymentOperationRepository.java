package com.banking.accountservice.repository;

import com.banking.accountservice.entity.PaymentLeg;
import com.banking.accountservice.entity.PaymentOperation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PaymentOperationRepository extends JpaRepository<PaymentOperation, PaymentOperation.Key> {

    default Optional<PaymentOperation> find(UUID paymentId, PaymentLeg leg) {
        return findById(new PaymentOperation.Key(paymentId, leg));
    }
}
