
package com.ofss.controller;

import com.ofss.dto.*;
import com.ofss.entity.PaymentStatus;
import com.ofss.service.PaymentRequestService;
import com.ofss.service.PaymentProcessingDispatchService;

import jakarta.validation.Valid;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/payment-requests")
public class PaymentRequestController {

    private final PaymentRequestService paymentRequestService;
    private final PaymentProcessingDispatchService processingDispatchService;

    public PaymentRequestController(
            PaymentRequestService paymentRequestService,
            PaymentProcessingDispatchService processingDispatchService
    ) {
        this.paymentRequestService = paymentRequestService;
        this.processingDispatchService = processingDispatchService;
    }

    @PostMapping
    @PreAuthorize("hasRole('PAYMENT_REQUESTER')")
    public ResponseEntity<PaymentRequestResponse> createPayment(
            @Valid @RequestBody CreatePaymentRequest request
    ) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(paymentRequestService.createPayment(request));
    }

    @GetMapping("/{paymentId}")
    @PreAuthorize("hasRole('PAYMENT_REQUESTER')")
    public ResponseEntity<PaymentRequestResponse> getPayment(
            @PathVariable Long paymentId
    ) {
        return ResponseEntity.ok(
                paymentRequestService.getPayment(paymentId)
        );
    }

    @GetMapping
    @PreAuthorize("hasRole('PAYMENT_REQUESTER')")
    public ResponseEntity<List<PaymentRequestResponse>> getPayments(
            @RequestParam(required = false) Long supplierId,
            @RequestParam(required = false) PaymentStatus status
    ) {
        return ResponseEntity.ok(
                paymentRequestService.getPayments(supplierId, status)
        );
    }

    @PutMapping("/{paymentId}")
    @PreAuthorize("hasRole('PAYMENT_REQUESTER')")
    public ResponseEntity<PaymentRequestResponse> updatePayment(
            @PathVariable Long paymentId,
            @Valid @RequestBody UpdatePaymentRequest request
    ) {
        return ResponseEntity.ok(
                paymentRequestService.updateDraftPayment(
                        paymentId,
                        request
                )
        );
    }

    @PostMapping("/{paymentId}/submit")
    @PreAuthorize("hasRole('PAYMENT_REQUESTER')")
    public ResponseEntity<SubmitPaymentResponse> submitPayment(
            @PathVariable Long paymentId
    ) {
        return ResponseEntity.ok(
                paymentRequestService.submitPayment(paymentId)
        );
    }

    @GetMapping("/{paymentId}/timeline")
    @PreAuthorize("hasRole('PAYMENT_REQUESTER')")
    public ResponseEntity<List<PaymentStatusHistoryResponse>> getTimeline(
            @PathVariable Long paymentId
    ) {
        return ResponseEntity.ok(
                paymentRequestService.getTimeline(paymentId)
        );
    }

    /*
     * RECOVERY API
     *
     * Restricted to FINANCE_MANAGER.
     *
     * Reuses the existing transactional dispatch service.
     *
     * The service:
     * 1. Locks the payment row.
     * 2. Requires APPROVED status.
     * 3. Verifies the persisted approval decision.
     * 4. Changes status to PROCESSING.
     * 5. Creates payment.approved in Oracle outbox.
     *
     * A repeated request after successful dispatch
     * is rejected because the payment is no longer APPROVED.
     *
     * This endpoint does not call Stripe directly.
     */
    @PostMapping("/{paymentId}/recover-processing")
    @PreAuthorize("hasRole('FINANCE_MANAGER')")
    public ResponseEntity<
            PaymentProcessingDispatchService.DispatchResult
    > recoverApprovedPayment(
            @PathVariable Long paymentId
    ) {
        return ResponseEntity.ok(
                processingDispatchService.prepareForProcessing(
                        paymentId
                )
        );
    }
}
