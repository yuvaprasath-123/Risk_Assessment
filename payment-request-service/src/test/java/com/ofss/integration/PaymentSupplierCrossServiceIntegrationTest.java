package com.ofss.integration;

import com.ofss.security.AuthUtil;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import org.springframework.http.MediaType;

import org.springframework.test.web.servlet.MockMvc;

import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

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

    private final RestClient supplierServiceClient =
            RestClient.create(
                    "http://localhost:8082"
            );


    @Test
    void paymentRequestShouldUseRealSupplierInvoiceService()
            throws Exception {

        /*
         * STEP 1
         * Create supplier through the REAL
         * supplier-invoice-service.
         */

        String supplierRequest =
                """
                {
                    "supplierName":
                        "Cross Service Supplier",
                    "status":
                        "ACTIVE"
                }
                """;

        String supplierResponse =
                supplierServiceClient
                        .post()
                        .uri(
                                "/api/v1/suppliers"
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
         * STEP 2
         * Create invoice through the REAL
         * supplier-invoice-service.
         */

        String invoiceNumber =
                "CROSS-INV-"
                        + System.currentTimeMillis();

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


        /*
         * STEP 3
         * Generate a real requester JWT.
         */

        Long requesterId =
                9001L;

        String token =
                authUtil.generateJwtToken(
                        requesterId,
                        "cross_service_requester",
                        List.of(
                                "PAYMENT_REQUESTER"
                        )
                );


        /*
         * STEP 4
         * Call payment-request-service.
         *
         * Internally:
         *
         * PaymentRequestService
         *      ↓
         * SupplierInvoiceClient
         *      ↓ HTTP
         * supplier-invoice-service
         */

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
                                        "Bearer "
                                                + token
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

        String supplierRequest =
                """
                {
                    "supplierName":
                        "Cross Service Inactive Supplier",
                    "status":
                        "INACTIVE"
                }
                """;

        String supplierResponse =
                supplierServiceClient
                        .post()
                        .uri(
                                "/api/v1/suppliers"
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
         * Your supplier-invoice-service itself
         * prevents creating an invoice for an
         * inactive supplier.
         *
         * Therefore we should not expect to get
         * as far as payment creation through this
         * normal API path.
         */
    }
    
    
    @Test
    void paymentCreationShouldRejectSupplierThatWasDeactivated()
            throws Exception {

        /*
         * Create ACTIVE supplier.
         */

        String supplierRequest =
                """
                {
                    "supplierName":
                        "Cross Service Deactivate Supplier",
                    "status":
                        "ACTIVE"
                }
                """;

        String supplierResponse =
                supplierServiceClient
                        .post()
                        .uri(
                                "/api/v1/suppliers"
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
         * Create invoice while supplier is ACTIVE.
         */

        String invoiceNumber =
                "CROSS-INACTIVE-"
                        + System.currentTimeMillis();

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
         * Deactivate supplier.
         */

        String statusRequest =
                """
                {
                    "status": "INACTIVE",
                    "changedBy": 9001
                }
                """;

        supplierServiceClient
                .patch()
                .uri(
                        "/api/v1/suppliers/"
                                + supplierId
                                + "/status"
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
         * Create requester JWT.
         */

        String token =
                authUtil.generateJwtToken(
                        9001L,
                        "cross_service_requester",
                        List.of(
                                "PAYMENT_REQUESTER"
                        )
                );


        /*
         * Payment creation must now fail.
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
                                        "Bearer "
                                                + token
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
}