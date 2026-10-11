package com.ofss.repository;

import com.ofss.entity.PaymentRequest;
import com.ofss.entity.PaymentStatus;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaymentRequestRepository
        extends JpaRepository<PaymentRequest, Long> {

    boolean existsByInvoiceId(
            Long invoiceId
    );

    List<PaymentRequest> findByRequesterId(
            Long requesterId
    );

    List<PaymentRequest> findByRequesterIdAndSupplierId(
            Long requesterId,
            Long supplierId
    );

    List<PaymentRequest> findByRequesterIdAndStatus(
            Long requesterId,
            PaymentStatus status
    );

    List<PaymentRequest>
    findByRequesterIdAndSupplierIdAndStatus(
            Long requesterId,
            Long supplierId,
            PaymentStatus status
    );
}