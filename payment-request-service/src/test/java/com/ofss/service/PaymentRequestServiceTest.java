
package com.ofss.service;

import com.ofss.client.InvoiceClientResponse;
import com.ofss.client.SupplierClientResponse;
import com.ofss.client.SupplierInvoiceClient;
import com.ofss.dto.CreatePaymentRequest;
import com.ofss.dto.PaymentRequestResponse;
import com.ofss.entity.PaymentRequest;
import com.ofss.entity.PaymentStatus;
import com.ofss.event.PaymentSubmittedEvent;
import com.ofss.exception.BadRequestException;
import com.ofss.kafka.PaymentEventProducer;
import com.ofss.repository.PaymentRequestRepository;
import com.ofss.repository.PaymentStatusHistoryRepository;
import com.ofss.security.AuthUtil;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentRequestServiceTest {

    @Mock
    private PaymentRequestRepository paymentRequestRepository;

    @Mock
    private PaymentStatusHistoryRepository paymentStatusHistoryRepository;

    @Mock
    private SupplierInvoiceClient supplierInvoiceClient;

    @Mock
    private AuthUtil authUtil;

    @Mock
    private PaymentEventProducer paymentEventProducer;

    @Mock
    private PaymentAuditOutboxService paymentAuditOutboxService;

    private PaymentRequestService paymentRequestService;

    private static final Long PAYMENT_ID = 100L;
    private static final Long REQUESTER_ID = 4L;
    private static final Long INVOICE_ID = 10L;
    private static final Long SUPPLIER_ID = 2L;

    private static final BigDecimal AMOUNT =
            new BigDecimal("60000.00");

    @BeforeEach
    void setUp() {

        paymentRequestService = new PaymentRequestService(
                paymentRequestRepository,
                paymentStatusHistoryRepository,
                supplierInvoiceClient,
                authUtil,
                paymentEventProducer,
                paymentAuditOutboxService
        );
    }

    private InvoiceClientResponse invoice() {

        return new InvoiceClientResponse(
                INVOICE_ID,
                "INV-100",
                SUPPLIER_ID,
                "ABC Supplier",
                AMOUNT,
                LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 31),
                null,
                null
        );
    }

    private SupplierClientResponse activeSupplier() {

        return new SupplierClientResponse(
                SUPPLIER_ID,
                "ABC Supplier",
                "ACTIVE",
                null,
                null
        );
    }

    private CreatePaymentRequest createRequest() {

        return new CreatePaymentRequest(
                INVOICE_ID,
                AMOUNT
        );
    }

    private PaymentRequest existingDraftPayment() {

        PaymentRequest payment = new PaymentRequest();

        payment.setId(PAYMENT_ID);
        payment.setInvoiceId(INVOICE_ID);
        payment.setSupplierId(SUPPLIER_ID);
        payment.setPaymentAmount(AMOUNT);
        payment.setRequesterId(REQUESTER_ID);
        payment.setStatus(PaymentStatus.DRAFT);

        return payment;
    }

    private void prepareSuccessfulCreation() {

        when(authUtil.getCurrentUserId())
                .thenReturn(REQUESTER_ID);

        when(supplierInvoiceClient.getInvoice(INVOICE_ID))
                .thenReturn(invoice());

        when(supplierInvoiceClient.getSupplier(SUPPLIER_ID))
                .thenReturn(activeSupplier());

        when(paymentRequestRepository.existsByInvoiceId(INVOICE_ID))
                .thenReturn(false);

        when(paymentRequestRepository.save(
                any(PaymentRequest.class)
        )).thenAnswer(invocation -> {

            PaymentRequest payment =
                    invocation.getArgument(0);

            payment.setId(PAYMENT_ID);

            return payment;
        });
    }

    @Test
    void createPayment_shouldUseRequesterIdFromJwt() {

        prepareSuccessfulCreation();

        PaymentRequestResponse response =
                paymentRequestService.createPayment(
                        createRequest()
                );

        assertEquals(
                REQUESTER_ID,
                response.requesterId()
        );

        assertEquals(
                SUPPLIER_ID,
                response.supplierId()
        );

        assertEquals(
                INVOICE_ID,
                response.invoiceId()
        );

        assertEquals(
                PaymentStatus.DRAFT,
                response.status()
        );

        /*
         * NEW: Verify that the audit event is requested
         * with the correct payment information.
         */
        verify(paymentAuditOutboxService).recordPaymentCreated(
                PAYMENT_ID,
                INVOICE_ID,
                SUPPLIER_ID,
                AMOUNT,
                REQUESTER_ID
        );

        verify(paymentEventProducer, never())
                .publishPaymentSubmitted(
                        any(PaymentSubmittedEvent.class)
                );
    }

    @Test
    void createPayment_shouldRejectInactiveSupplier() {

        when(authUtil.getCurrentUserId())
                .thenReturn(REQUESTER_ID);

        when(supplierInvoiceClient.getInvoice(INVOICE_ID))
                .thenReturn(invoice());

        SupplierClientResponse inactiveSupplier =
                new SupplierClientResponse(
                        SUPPLIER_ID,
                        "ABC Supplier",
                        "INACTIVE",
                        null,
                        null
                );

        when(supplierInvoiceClient.getSupplier(SUPPLIER_ID))
                .thenReturn(inactiveSupplier);

        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> paymentRequestService.createPayment(
                        createRequest()
                )
        );

        assertEquals(
                "Payment request cannot be created for an inactive supplier",
                exception.getMessage()
        );

        verify(paymentRequestRepository, never())
                .save(any(PaymentRequest.class));

        verifyNoInteractions(paymentAuditOutboxService);

        verify(paymentEventProducer, never())
                .publishPaymentSubmitted(
                        any(PaymentSubmittedEvent.class)
                );
    }

    @Test
    void createPayment_shouldRejectDuplicatePaymentForInvoice() {

        when(authUtil.getCurrentUserId())
                .thenReturn(REQUESTER_ID);

        when(supplierInvoiceClient.getInvoice(INVOICE_ID))
                .thenReturn(invoice());

        when(supplierInvoiceClient.getSupplier(SUPPLIER_ID))
                .thenReturn(activeSupplier());

        when(paymentRequestRepository.existsByInvoiceId(INVOICE_ID))
                .thenReturn(true);

        BadRequestException exception = assertThrows(
                BadRequestException.class,
                () -> paymentRequestService.createPayment(
                        createRequest()
                )
        );

        assertEquals(
                "A payment request already exists for this invoice",
                exception.getMessage()
        );

        verify(paymentRequestRepository, never())
                .save(any(PaymentRequest.class));

        verifyNoInteractions(paymentAuditOutboxService);
    }

    @Test
    void getPayment_shouldRejectAnotherRequester() {

        PaymentRequest payment = existingDraftPayment();

        when(paymentRequestRepository.findById(PAYMENT_ID))
                .thenReturn(Optional.of(payment));

        when(authUtil.getCurrentUserId())
                .thenReturn(99L);

        assertThrows(
                AccessDeniedException.class,
                () -> paymentRequestService.getPayment(
                        PAYMENT_ID
                )
        );
    }

    @Test
    void getPayments_shouldOnlyLoadCurrentRequesterPayments() {

        when(authUtil.getCurrentUserId())
                .thenReturn(REQUESTER_ID);

        PaymentRequest payment = existingDraftPayment();

        when(paymentRequestRepository.findByRequesterId(
                REQUESTER_ID
        )).thenReturn(List.of(payment));

        var responses =
                paymentRequestService.getPayments(
                        null,
                        null
                );

        assertEquals(1, responses.size());

        assertEquals(
                REQUESTER_ID,
                responses.get(0).requesterId()
        );

        verify(paymentRequestRepository)
                .findByRequesterId(REQUESTER_ID);

        verify(paymentRequestRepository, never())
                .findAll();
    }

    @Test
    void submitPayment_shouldPublishKafkaEvent() {

        PaymentRequest payment = existingDraftPayment();

        when(paymentRequestRepository.findById(PAYMENT_ID))
                .thenReturn(Optional.of(payment));

        when(authUtil.getCurrentUserId())
                .thenReturn(REQUESTER_ID);

        when(supplierInvoiceClient.getInvoice(INVOICE_ID))
                .thenReturn(invoice());

        when(supplierInvoiceClient.getSupplier(SUPPLIER_ID))
                .thenReturn(activeSupplier());

        when(paymentRequestRepository.save(
                any(PaymentRequest.class)
        )).thenAnswer(invocation ->
                invocation.getArgument(0)
        );

        var response =
                paymentRequestService.submitPayment(
                        PAYMENT_ID
                );

        assertEquals(
                PaymentStatus.PENDING_VALIDATION,
                response.status()
        );

        assertEquals(
                PaymentStatus.PENDING_VALIDATION,
                payment.getStatus()
        );

        assertNotNull(payment.getSubmittedAt());

        verify(paymentRequestRepository).save(payment);

        verify(paymentEventProducer, times(1))
                .publishPaymentSubmitted(
                        any(PaymentSubmittedEvent.class)
                );

        verifyNoInteractions(paymentAuditOutboxService);
    }

    @Test
    void createPayment_shouldPropagateAuditOutboxFailure() {

        prepareSuccessfulCreation();

        doThrow(new IllegalStateException(
                "Simulated audit outbox insert failure"
        )).when(paymentAuditOutboxService)
                .recordPaymentCreated(
                        PAYMENT_ID,
                        INVOICE_ID,
                        SUPPLIER_ID,
                        AMOUNT,
                        REQUESTER_ID
                );

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> paymentRequestService.createPayment(
                        createRequest()
                )
        );

        assertEquals(
                "Simulated audit outbox insert failure",
                exception.getMessage()
        );

        verify(paymentAuditOutboxService)
                .recordPaymentCreated(
                        PAYMENT_ID,
                        INVOICE_ID,
                        SUPPLIER_ID,
                        AMOUNT,
                        REQUESTER_ID
                );

        verify(paymentEventProducer, never())
                .publishPaymentSubmitted(
                        any(PaymentSubmittedEvent.class)
                );
    }
}
