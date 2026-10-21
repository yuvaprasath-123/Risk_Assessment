package com.ofss.service;

import com.ofss.event.PaymentSubmittedEvent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaymentValidationServiceTest {

    private PaymentValidationService
            paymentValidationService;

    @BeforeEach
    void setUp() {

        paymentValidationService =
                new PaymentValidationService();
    }

    @Test
    void validPaymentShouldPassValidation() {

        PaymentSubmittedEvent event =
                createValidEvent();

        ValidationResult result =
                paymentValidationService
                        .validate(
                                event
                        );

        assertTrue(
                result.valid()
        );

        assertNull(
                result.reason()
        );
    }

    @Test
    void zeroAmountShouldFailValidation() {

        PaymentSubmittedEvent event =
                createValidEvent();

        event.setPaymentAmount(
                BigDecimal.ZERO
        );

        ValidationResult result =
                paymentValidationService
                        .validate(
                                event
                        );

        assertFalse(
                result.valid()
        );

        assertEquals(
                "Payment amount must be greater than zero",
                result.reason()
        );
    }

    @Test
    void negativeAmountShouldFailValidation() {

        PaymentSubmittedEvent event =
                createValidEvent();

        event.setPaymentAmount(
                new BigDecimal(
                        "-100.00"
                )
        );

        ValidationResult result =
                paymentValidationService
                        .validate(
                                event
                        );

        assertFalse(
                result.valid()
        );

        assertEquals(
                "Payment amount must be greater than zero",
                result.reason()
        );
    }

    @Test
    void missingPaymentIdShouldFailValidation() {

        PaymentSubmittedEvent event =
                createValidEvent();

        event.setPaymentId(
                null
        );

        ValidationResult result =
                paymentValidationService
                        .validate(
                                event
                        );

        assertFalse(
                result.valid()
        );

        assertEquals(
                "Payment ID must be present and greater than zero",
                result.reason()
        );
    }

    @Test
    void missingInvoiceIdShouldFailValidation() {

        PaymentSubmittedEvent event =
                createValidEvent();

        event.setInvoiceId(
                null
        );

        ValidationResult result =
                paymentValidationService
                        .validate(
                                event
                        );

        assertFalse(
                result.valid()
        );

        assertEquals(
                "Invoice ID must be present and greater than zero",
                result.reason()
        );
    }

    @Test
    void wrongStatusShouldFailValidation() {

        PaymentSubmittedEvent event =
                createValidEvent();

        event.setStatus(
                "DRAFT"
        );

        ValidationResult result =
                paymentValidationService
                        .validate(
                                event
                        );

        assertFalse(
                result.valid()
        );

        assertEquals(
                "Payment must be in PENDING_VALIDATION status",
                result.reason()
        );
    }

    @Test
    void missingSubmittedByShouldFailValidation() {

        PaymentSubmittedEvent event =
                createValidEvent();

        event.setSubmittedBy(
                null
        );

        ValidationResult result =
                paymentValidationService
                        .validate(
                                event
                        );

        assertFalse(
                result.valid()
        );

        assertEquals(
                "Submitted by user ID must be present",
                result.reason()
        );
    }

    @Test
    void missingSubmittedAtShouldFailValidation() {

        PaymentSubmittedEvent event =
                createValidEvent();

        event.setSubmittedAt(
                null
        );

        ValidationResult result =
                paymentValidationService
                        .validate(
                                event
                        );

        assertFalse(
                result.valid()
        );

        assertEquals(
                "Submitted time must be present",
                result.reason()
        );
    }

    private PaymentSubmittedEvent createValidEvent() {

        return new PaymentSubmittedEvent(
                100L,
                200L,
                new BigDecimal(
                        "50000.00"
                ),
                "PENDING_VALIDATION",
                1L,
                LocalDateTime.now()
        );
    }
}