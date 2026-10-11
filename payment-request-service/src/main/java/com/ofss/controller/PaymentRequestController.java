package com.ofss.controller;

import com.ofss.dto.*;
import com.ofss.entity.PaymentStatus;
import com.ofss.service.PaymentRequestService;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api/v1/payment-requests")
public class PaymentRequestController {

    private final PaymentRequestService
            paymentRequestService;

    public PaymentRequestController(
            PaymentRequestService paymentRequestService
    ) {
        this.paymentRequestService =
                paymentRequestService;
    }

    @PostMapping
    @PreAuthorize("hasRole('PAYMENT_REQUESTER')")
    public ResponseEntity<PaymentRequestResponse>
    createPayment(
            @Valid
            @RequestBody
            CreatePaymentRequest request
    ) {

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(
                        paymentRequestService
                                .createPayment(
                                        request
                                )
                );
    }

    @GetMapping("/{paymentId}")
    @PreAuthorize("hasRole('PAYMENT_REQUESTER')")
    public ResponseEntity<PaymentRequestResponse>
    getPayment(
            @PathVariable
            Long paymentId
    ) {

        return ResponseEntity.ok(
                paymentRequestService
                        .getPayment(
                                paymentId
                        )
        );
    }

    @GetMapping
    @PreAuthorize("hasRole('PAYMENT_REQUESTER')")
    public ResponseEntity<List<PaymentRequestResponse>>
    getPayments(

            @RequestParam(required = false)
            Long supplierId,

            @RequestParam(required = false)
            PaymentStatus status
    ) {

        return ResponseEntity.ok(
                paymentRequestService
                        .getPayments(
                                supplierId,
                                status
                        )
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
    public ResponseEntity<SubmitPaymentResponse>
    submitPayment(
            @PathVariable
            Long paymentId
    ) {

        return ResponseEntity.ok(
                paymentRequestService
                        .submitPayment(
                                paymentId
                        )
        );
    }

    @GetMapping("/{paymentId}/timeline")
    @PreAuthorize("hasRole('PAYMENT_REQUESTER')")
    public ResponseEntity<
            List<PaymentStatusHistoryResponse>
            >
    getTimeline(
            @PathVariable
            Long paymentId
    ) {

        return ResponseEntity.ok(
                paymentRequestService
                        .getTimeline(
                                paymentId
                        )
        );
    }
}