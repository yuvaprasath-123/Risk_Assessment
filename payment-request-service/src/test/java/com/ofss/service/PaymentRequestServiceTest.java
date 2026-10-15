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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentRequestServiceTest {

    @Mock
    private PaymentRequestRepository
            paymentRequestRepository;

    @Mock
    private PaymentStatusHistoryRepository
            paymentStatusHistoryRepository;

    @Mock
    private SupplierInvoiceClient
            supplierInvoiceClient;

    @Mock
    private AuthUtil
            authUtil;

    @Mock
    private PaymentEventProducer
            paymentEventProducer;

    private PaymentRequestService
            paymentRequestService;

    @BeforeEach
    void setUp() {

        paymentRequestService =
                new PaymentRequestService(
                        paymentRequestRepository,
                        paymentStatusHistoryRepository,
                        supplierInvoiceClient,
                        authUtil,
                        paymentEventProducer
                );
    }

    @Test
    void createPayment_shouldUseRequesterIdFromJwt() {

        Long requesterId =
                4L;

        Long supplierId =
                2L;

        Long invoiceId =
                10L;

        BigDecimal amount =
                new BigDecimal(
                        "60000.00"
                );

        when(
                authUtil
                        .getCurrentUserId()
        ).thenReturn(
                requesterId
        );

        SupplierClientResponse supplier =
                new SupplierClientResponse(
                        supplierId,
                        "ABC Supplier",
                        "ACTIVE",
                        null,
                        null
                );

        InvoiceClientResponse invoice =
                new InvoiceClientResponse(
                        invoiceId,
                        "INV-100",
                        supplierId,
                        "ABC Supplier",
                        amount,
                        LocalDate.of(
                                2026,
                                10,
                                1
                        ),
                        LocalDate.of(
                                2026,
                                10,
                                31
                        ),
                        null,
                        null
                );

        when(
                supplierInvoiceClient
                        .getSupplier(
                                supplierId
                        )
        ).thenReturn(
                supplier
        );

        when(
                supplierInvoiceClient
                        .getInvoice(
                                invoiceId
                        )
        ).thenReturn(
                invoice
        );

        when(
                paymentRequestRepository
                        .existsByInvoiceId(
                                invoiceId
                        )
        ).thenReturn(
                false
        );

        when(
                paymentRequestRepository
                        .save(
                                any(
                                        PaymentRequest.class
                                )
                        )
        ).thenAnswer(
                invocation -> {

                    PaymentRequest payment =
                            invocation.getArgument(
                                    0
                            );

                    payment.setId(
                            100L
                    );

                    return payment;
                }
        );

        CreatePaymentRequest request =
                new CreatePaymentRequest(
                        invoiceId,
                        amount
                );

        PaymentRequestResponse response =
                paymentRequestService
                        .createPayment(
                                request
                        );

        assertEquals(
                requesterId,
                response.requesterId()
        );

        assertEquals(
                supplierId,
                response.supplierId()
        );

        assertEquals(
                invoiceId,
                response.invoiceId()
        );

        assertEquals(
                PaymentStatus.DRAFT,
                response.status()
        );

        verify(
                paymentEventProducer,
                never()
        ).publishPaymentSubmitted(
                any(
                        PaymentSubmittedEvent.class
                )
        );
    }

    @Test
    void createPayment_shouldRejectInactiveSupplier() {

        Long requesterId =
                1L;

        Long invoiceId =
                10L;

        Long supplierId =
                2L;

        BigDecimal amount =
                new BigDecimal(
                        "60000.00"
                );

        when(
                authUtil
                        .getCurrentUserId()
        ).thenReturn(
                requesterId
        );

        InvoiceClientResponse invoice =
                new InvoiceClientResponse(
                        invoiceId,
                        "INV-100",
                        supplierId,
                        "ABC Supplier",
                        amount,
                        LocalDate.of(
                                2026,
                                10,
                                1
                        ),
                        LocalDate.of(
                                2026,
                                10,
                                31
                        ),
                        null,
                        null
                );

        when(
                supplierInvoiceClient
                        .getInvoice(
                                invoiceId
                        )
        ).thenReturn(
                invoice
        );

        SupplierClientResponse supplier =
                new SupplierClientResponse(
                        supplierId,
                        "ABC Supplier",
                        "INACTIVE",
                        null,
                        null
                );

        when(
                supplierInvoiceClient
                        .getSupplier(
                                supplierId
                        )
        ).thenReturn(
                supplier
        );

        CreatePaymentRequest request =
                new CreatePaymentRequest(
                        invoiceId,
                        amount
                );

        BadRequestException exception =
                assertThrows(
                        BadRequestException.class,
                        () ->
                                paymentRequestService
                                        .createPayment(
                                                request
                                        )
                );

        assertEquals(
                "Payment request cannot be created for an inactive supplier",
                exception.getMessage()
        );

        verify(
                paymentRequestRepository,
                never()
        ).save(
                any(
                        PaymentRequest.class
                )
        );

        verify(
                paymentEventProducer,
                never()
        ).publishPaymentSubmitted(
                any(
                        PaymentSubmittedEvent.class
                )
        );
    }

    @Test
    void createPayment_shouldRejectDuplicatePaymentForInvoice() {

        Long requesterId =
                1L;

        Long invoiceId =
                10L;

        Long supplierId =
                2L;

        BigDecimal amount =
                new BigDecimal(
                        "60000.00"
                );

        when(
                authUtil
                        .getCurrentUserId()
        ).thenReturn(
                requesterId
        );

        InvoiceClientResponse invoice =
                new InvoiceClientResponse(
                        invoiceId,
                        "INV-100",
                        supplierId,
                        "ABC Supplier",
                        amount,
                        LocalDate.of(
                                2026,
                                10,
                                1
                        ),
                        LocalDate.of(
                                2026,
                                10,
                                31
                        ),
                        null,
                        null
                );

        when(
                supplierInvoiceClient
                        .getInvoice(
                                invoiceId
                        )
        ).thenReturn(
                invoice
        );

        SupplierClientResponse supplier =
                new SupplierClientResponse(
                        supplierId,
                        "ABC Supplier",
                        "ACTIVE",
                        null,
                        null
                );

        when(
                supplierInvoiceClient
                        .getSupplier(
                                supplierId
                        )
        ).thenReturn(
                supplier
        );

        when(
                paymentRequestRepository
                        .existsByInvoiceId(
                                invoiceId
                        )
        ).thenReturn(
                true
        );

        CreatePaymentRequest request =
                new CreatePaymentRequest(
                        invoiceId,
                        amount
                );

        BadRequestException exception =
                assertThrows(
                        BadRequestException.class,
                        () ->
                                paymentRequestService
                                        .createPayment(
                                                request
                                        )
                );

        assertEquals(
                "A payment request already exists for this invoice",
                exception.getMessage()
        );

        verify(
                paymentRequestRepository,
                never()
        ).save(
                any(
                        PaymentRequest.class
                )
        );

        verify(
                paymentEventProducer,
                never()
        ).publishPaymentSubmitted(
                any(
                        PaymentSubmittedEvent.class
                )
        );
    }

    @Test
    void getPayment_shouldRejectAnotherRequester() {

        PaymentRequest payment =
                new PaymentRequest();

        payment.setId(
                1L
        );

        payment.setRequesterId(
                1L
        );

        payment.setStatus(
                PaymentStatus.DRAFT
        );

        when(
                paymentRequestRepository
                        .findById(
                                1L
                        )
        ).thenReturn(
                Optional.of(
                        payment
                )
        );

        when(
                authUtil
                        .getCurrentUserId()
        ).thenReturn(
                4L
        );

        assertThrows(
                AccessDeniedException.class,
                () ->
                        paymentRequestService
                                .getPayment(
                                        1L
                                )
        );
    }

    @Test
    void getPayments_shouldOnlyLoadCurrentRequesterPayments() {

        Long requesterId =
                4L;

        when(
                authUtil
                        .getCurrentUserId()
        ).thenReturn(
                requesterId
        );

        PaymentRequest payment =
                new PaymentRequest();

        payment.setId(
                10L
        );

        payment.setRequesterId(
                requesterId
        );

        payment.setStatus(
                PaymentStatus.DRAFT
        );

        when(
                paymentRequestRepository
                        .findByRequesterId(
                                requesterId
                        )
        ).thenReturn(
                java.util.List.of(
                        payment
                )
        );

        var responses =
                paymentRequestService
                        .getPayments(
                                null,
                                null
                        );

        assertEquals(
                1,
                responses.size()
        );

        assertEquals(
                requesterId,
                responses
                        .get(
                                0
                        )
                        .requesterId()
        );

        verify(
                paymentRequestRepository
        ).findByRequesterId(
                requesterId
        );

        verify(
                paymentRequestRepository,
                never()
        ).findAll();
    }

    @Test
    void submitPayment_shouldPublishKafkaEvent() {

        Long paymentId =
                100L;

        Long requesterId =
                4L;

        Long invoiceId =
                10L;

        Long supplierId =
                2L;

        BigDecimal amount =
                new BigDecimal(
                        "60000.00"
                );

        PaymentRequest payment =
                new PaymentRequest();

        payment.setId(
                paymentId
        );

        payment.setInvoiceId(
                invoiceId
        );

        payment.setSupplierId(
                supplierId
        );

        payment.setPaymentAmount(
                amount
        );

        payment.setRequesterId(
                requesterId
        );

        payment.setStatus(
                PaymentStatus.DRAFT
        );

        InvoiceClientResponse invoice =
                new InvoiceClientResponse(
                        invoiceId,
                        "INV-100",
                        supplierId,
                        "ABC Supplier",
                        amount,
                        LocalDate.of(
                                2026,
                                10,
                                1
                        ),
                        LocalDate.of(
                                2026,
                                10,
                                31
                        ),
                        null,
                        null
                );

        SupplierClientResponse supplier =
                new SupplierClientResponse(
                        supplierId,
                        "ABC Supplier",
                        "ACTIVE",
                        null,
                        null
                );

        when(
                paymentRequestRepository
                        .findById(
                                paymentId
                        )
        ).thenReturn(
                Optional.of(
                        payment
                )
        );

        when(
                authUtil
                        .getCurrentUserId()
        ).thenReturn(
                requesterId
        );

        when(
                supplierInvoiceClient
                        .getInvoice(
                                invoiceId
                        )
        ).thenReturn(
                invoice
        );

        when(
                supplierInvoiceClient
                        .getSupplier(
                                supplierId
                        )
        ).thenReturn(
                supplier
        );

        when(
                paymentRequestRepository
                        .save(
                                any(
                                        PaymentRequest.class
                                )
                        )
        ).thenAnswer(
                invocation ->
                        invocation.getArgument(
                                0
                        )
        );

        var response =
                paymentRequestService
                        .submitPayment(
                                paymentId
                        );

        assertEquals(
                PaymentStatus.PENDING_VALIDATION,
                response.status()
        );

        assertEquals(
                PaymentStatus.PENDING_VALIDATION,
                payment.getStatus()
        );

        assertNotNull(
                payment.getSubmittedAt()
        );

        verify(
                paymentRequestRepository
        ).save(
                payment
        );

        verify(
                paymentEventProducer,
                times(
                        1
                )
        ).publishPaymentSubmitted(
                any(
                        PaymentSubmittedEvent.class
                )
        );
    }
}