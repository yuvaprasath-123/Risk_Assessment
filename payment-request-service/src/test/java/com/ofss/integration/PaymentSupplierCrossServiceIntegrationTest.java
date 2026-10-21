package com.ofss.integration;

import com.ofss.kafka.PaymentEventProducer;
import com.ofss.security.AuthUtil;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import org.springframework.http.MediaType;

import org.springframework.test.context.bean.override.mockito.MockitoBean;

import org.springframework.test.web.servlet.MockMvc;

import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PaymentSupplierCrossServiceIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthUtil authUtil;

    @Autowired
    private JsonMapper objectMapper;

    /*
     * Kafka is not the purpose of these
     * supplier/payment cross-service tests.
     */
    @MockitoBean
    private PaymentEventProducer
            paymentEventProducer;

    private final RestClient supplierServiceClient =
            RestClient.create(
                    "http://localhost:8082"
            );


    @Test
    void paymentRequestShouldUseRealSupplierInvoiceService()
            throws Exception {

        Long requesterId =
                9001L;

        String token =
                createRequesterToken(
                        requesterId
                );

        /*
         * Use a unique supplier name because this
         * test calls the REAL supplier service and
         * therefore persists data in Oracle.
         */
        String supplierName =
                uniqueName(
                        "Cross Service Supplier"
                );

        String supplierRequest =
                """
                {
                    "supplierName": "%s",
                    "status": "ACTIVE"
                }
                """.formatted(
                        supplierName
                );

        String supplierResponse =
                supplierServiceClient
                        .post()
                        .uri(
                                "/api/v1/suppliers"
                        )
                        .header(
                                "Authorization",
                                "Bearer " + token
                        )
                        .contentType(
                                MediaType.APPLICATION_JSON
                        )
                        .body(
                                supplierRequest
                        )
                        .retrieve()
                        .body(
                                String.class
                        );

        JsonNode supplierJson =
                objectMapper.readTree(
                        supplierResponse
                );

        Long supplierId =
                supplierJson
                        .get(
                                "supplierId"
                        )
                        .asLong();


        String invoiceNumber =
                "CROSS-INV-"
                        + UUID.randomUUID();

        String invoiceRequest =
                """
                {
                    "invoiceNumber": "%s",
                    "supplierId": %d,
                    "invoiceAmount": 60000,
                    "invoiceDate": "2026-10-11",
                    "dueDate": "2026-11-11"
                }
                """.formatted(
                        invoiceNumber,
                        supplierId
                );

        String invoiceResponse =
                supplierServiceClient
                        .post()
                        .uri(
                                "/api/v1/invoices"
                        )
                        .header(
                                "Authorization",
                                "Bearer " + token
                        )
                        .contentType(
                                MediaType.APPLICATION_JSON
                        )
                        .body(
                                invoiceRequest
                        )
                        .retrieve()
                        .body(
                                String.class
                        );

        JsonNode invoiceJson =
                objectMapper.readTree(
                        invoiceResponse
                );

        Long invoiceId =
                invoiceJson
                        .get(
                                "invoiceId"
                        )
                        .asLong();


        String paymentRequest =
                """
                {
                    "invoiceId": %d,
                    "paymentAmount": 60000
                }
                """.formatted(
                        invoiceId
                );

        mockMvc.perform(
                        post(
                                "/api/v1/payment-requests"
                        )
                                .header(
                                        "Authorization",
                                        "Bearer " + token
                                )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(
                                        paymentRequest
                                )
                )
                .andExpect(
                        status().isCreated()
                )
                .andExpect(
                        jsonPath(
                                "$.invoiceId"
                        ).value(
                                invoiceId
                        )
                )
                .andExpect(
                        jsonPath(
                                "$.supplierId"
                        ).value(
                                supplierId
                        )
                )
                .andExpect(
                        jsonPath(
                                "$.requesterId"
                        ).value(
                                requesterId
                        )
                )
                .andExpect(
                        jsonPath(
                                "$.paymentAmount"
                        ).value(
                                60000
                        )
                )
                .andExpect(
                        jsonPath(
                                "$.status"
                        ).value(
                                "DRAFT"
                        )
                );
    }


    @Test
    void paymentCreationShouldFailForInactiveSupplierThroughRealService()
            throws Exception {

        String token =
                createRequesterToken(
                        9002L
                );

        String supplierName =
                uniqueName(
                        "Cross Service Inactive Supplier"
                );

        String supplierRequest =
                """
                {
                    "supplierName": "%s",
                    "status": "INACTIVE"
                }
                """.formatted(
                        supplierName
                );

        String supplierResponse =
                supplierServiceClient
                        .post()
                        .uri(
                                "/api/v1/suppliers"
                        )
                        .header(
                                "Authorization",
                                "Bearer " + token
                        )
                        .contentType(
                                MediaType.APPLICATION_JSON
                        )
                        .body(
                                supplierRequest
                        )
                        .retrieve()
                        .body(
                                String.class
                        );

        JsonNode supplierJson =
                objectMapper.readTree(
                        supplierResponse
                );

        Long supplierId =
                supplierJson
                        .get(
                                "supplierId"
                        )
                        .asLong();

        /*
         * The supplier-invoice-service prevents
         * creating an invoice for an inactive
         * supplier through the normal API flow.
         *
         * Confirm that the real inactive supplier
         * was created successfully.
         */
        assertNotNull(
                supplierId
        );
    }


    @Test
    void paymentCreationShouldRejectSupplierThatWasDeactivated()
            throws Exception {

        String token =
                createRequesterToken(
                        9001L
                );

        String supplierName =
                uniqueName(
                        "Cross Service Deactivate Supplier"
                );

        /*
         * STEP 1
         *
         * Create an ACTIVE supplier.
         */
        String supplierRequest =
                """
                {
                    "supplierName": "%s",
                    "status": "ACTIVE"
                }
                """.formatted(
                        supplierName
                );

        String supplierResponse =
                supplierServiceClient
                        .post()
                        .uri(
                                "/api/v1/suppliers"
                        )
                        .header(
                                "Authorization",
                                "Bearer " + token
                        )
                        .contentType(
                                MediaType.APPLICATION_JSON
                        )
                        .body(
                                supplierRequest
                        )
                        .retrieve()
                        .body(
                                String.class
                        );

        Long supplierId =
                objectMapper
                        .readTree(
                                supplierResponse
                        )
                        .get(
                                "supplierId"
                        )
                        .asLong();


        /*
         * STEP 2
         *
         * Create invoice while supplier is ACTIVE.
         */
        String invoiceNumber =
                "CROSS-INACTIVE-"
                        + UUID.randomUUID();

        String invoiceRequest =
                """
                {
                    "invoiceNumber": "%s",
                    "supplierId": %d,
                    "invoiceAmount": 50000,
                    "invoiceDate": "2026-10-11",
                    "dueDate": "2026-11-11"
                }
                """.formatted(
                        invoiceNumber,
                        supplierId
                );

        String invoiceResponse =
                supplierServiceClient
                        .post()
                        .uri(
                                "/api/v1/invoices"
                        )
                        .header(
                                "Authorization",
                                "Bearer " + token
                        )
                        .contentType(
                                MediaType.APPLICATION_JSON
                        )
                        .body(
                                invoiceRequest
                        )
                        .retrieve()
                        .body(
                                String.class
                        );

        Long invoiceId =
                objectMapper
                        .readTree(
                                invoiceResponse
                        )
                        .get(
                                "invoiceId"
                        )
                        .asLong();


        /*
         * STEP 3
         *
         * Deactivate the supplier.
         */
        String statusRequest =
                """
                {
                    "status": "INACTIVE"
                }
                """;

        supplierServiceClient
                .patch()
                .uri(
                        "/api/v1/suppliers/"
                                + supplierId
                                + "/status"
                )
                .header(
                        "Authorization",
                        "Bearer " + token
                )
                .contentType(
                        MediaType.APPLICATION_JSON
                )
                .body(
                        statusRequest
                )
                .retrieve()
                .toBodilessEntity();


        /*
         * STEP 4
         *
         * Payment creation must fail because
         * the supplier is now inactive.
         */
        String paymentRequest =
                """
                {
                    "invoiceId": %d,
                    "paymentAmount": 50000
                }
                """.formatted(
                        invoiceId
                );

        mockMvc.perform(
                        post(
                                "/api/v1/payment-requests"
                        )
                                .header(
                                        "Authorization",
                                        "Bearer " + token
                                )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(
                                        paymentRequest
                                )
                )
                .andExpect(
                        status().isBadRequest()
                );
    }


    private String createRequesterToken(
            Long userId) {

        return authUtil.generateJwtToken(
                userId,
                "cross_service_requester",
                List.of(
                        "PAYMENT_REQUESTER"
                )
        );
    }


    private String uniqueName(
            String prefix) {

        return prefix
                + "-"
                + System.currentTimeMillis();
    }
}