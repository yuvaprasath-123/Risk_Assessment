package com.ofss.integration;

import com.ofss.entity.Supplier;
import com.ofss.entity.SupplierStatus;

import com.ofss.repository.InvoiceRepository;
import com.ofss.repository.SupplierRepository;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import org.springframework.http.MediaType;

import org.springframework.test.annotation.Rollback;
import org.springframework.test.web.servlet.MockMvc;

import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Rollback
class SupplierInvoiceIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SupplierRepository supplierRepository;

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private JsonMapper objectMapper;


    @Test
    void createSupplierShouldReturnCreated()
            throws Exception {

        String requestBody =
                """
                {
                    "supplierName": "Integration Supplier One",
                    "status": "ACTIVE"
                }
                """;

        mockMvc.perform(
                        post(
                                "/api/v1/suppliers"
                        )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(
                                        requestBody
                                )
                )
                .andExpect(
                        status().isCreated()
                )
                .andExpect(
                        jsonPath(
                                "$.supplierId"
                        ).exists()
                )
                .andExpect(
                        jsonPath(
                                "$.supplierName"
                        ).value(
                                "Integration Supplier One"
                        )
                )
                .andExpect(
                        jsonPath(
                                "$.status"
                        ).value(
                                "ACTIVE"
                        )
                );
    }


    @Test
    void createInvoiceForActiveSupplierShouldReturnCreated()
            throws Exception {

        Supplier supplier =
                createSupplier(
                        "Integration Active Supplier",
                        SupplierStatus.ACTIVE
                );

        String requestBody =
                """
                {
                    "invoiceNumber": "INT-INV-1001",
                    "supplierId": %d,
                    "invoiceAmount": 60000,
                    "invoiceDate": "2026-10-01",
                    "dueDate": "2026-10-31"
                }
                """.formatted(
                        supplier.getId()
                );

        mockMvc.perform(
                        post(
                                "/api/v1/invoices"
                        )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(
                                        requestBody
                                )
                )
                .andExpect(
                        status().isCreated()
                )
                .andExpect(
                        jsonPath(
                                "$.invoiceId"
                        ).exists()
                )
                .andExpect(
                        jsonPath(
                                "$.invoiceNumber"
                        ).value(
                                "INT-INV-1001"
                        )
                )
                .andExpect(
                        jsonPath(
                                "$.supplierId"
                        ).value(
                                supplier.getId()
                        )
                )
                .andExpect(
                        jsonPath(
                                "$.invoiceAmount"
                        ).value(
                                60000
                        )
                );
    }


    @Test
    void createInvoiceForInactiveSupplierShouldReturnBadRequest()
            throws Exception {

        Supplier supplier =
                createSupplier(
                        "Integration Inactive Supplier",
                        SupplierStatus.INACTIVE
                );

        String requestBody =
                """
                {
                    "invoiceNumber": "INT-INV-1002",
                    "supplierId": %d,
                    "invoiceAmount": 45000,
                    "invoiceDate": "2026-10-01",
                    "dueDate": "2026-10-31"
                }
                """.formatted(
                        supplier.getId()
                );

        mockMvc.perform(
                        post(
                                "/api/v1/invoices"
                        )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(
                                        requestBody
                                )
                )
                .andExpect(
                        status().isBadRequest()
                );
    }


    @Test
    void duplicateInvoiceShouldReturnBadRequest()
            throws Exception {

        Supplier supplier =
                createSupplier(
                        "Integration Duplicate Supplier",
                        SupplierStatus.ACTIVE
                );

        String requestBody =
                """
                {
                    "invoiceNumber": "INT-DUP-1001",
                    "supplierId": %d,
                    "invoiceAmount": 50000,
                    "invoiceDate": "2026-10-01",
                    "dueDate": "2026-10-31"
                }
                """.formatted(
                        supplier.getId()
                );

        mockMvc.perform(
                        post(
                                "/api/v1/invoices"
                        )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(
                                        requestBody
                                )
                )
                .andExpect(
                        status().isCreated()
                );

        mockMvc.perform(
                        post(
                                "/api/v1/invoices"
                        )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(
                                        requestBody
                                )
                )
                .andExpect(
                        status().isBadRequest()
                );

        assertTrue(
                invoiceRepository
                        .existsBySupplierIdAndInvoiceNumber(
                                supplier.getId(),
                                "INT-DUP-1001"
                        )
        );
    }


    @Test
    void dueDateBeforeInvoiceDateShouldReturnBadRequest()
            throws Exception {

        Supplier supplier =
                createSupplier(
                        "Integration Date Supplier",
                        SupplierStatus.ACTIVE
                );

        String requestBody =
                """
                {
                    "invoiceNumber": "INT-DATE-1001",
                    "supplierId": %d,
                    "invoiceAmount": 25000,
                    "invoiceDate": "2026-10-20",
                    "dueDate": "2026-10-10"
                }
                """.formatted(
                        supplier.getId()
                );

        mockMvc.perform(
                        post(
                                "/api/v1/invoices"
                        )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(
                                        requestBody
                                )
                )
                .andExpect(
                        status().isBadRequest()
                );
    }


    @Test
    void createAndGetInvoiceShouldReturnStoredInvoice()
            throws Exception {

        Supplier supplier =
                createSupplier(
                        "Integration Retrieval Supplier",
                        SupplierStatus.ACTIVE
                );

        String createRequest =
                """
                {
                    "invoiceNumber": "INT-GET-1001",
                    "supplierId": %d,
                    "invoiceAmount": 75000,
                    "invoiceDate": "2026-10-01",
                    "dueDate": "2026-11-01"
                }
                """.formatted(
                        supplier.getId()
                );

        String responseBody =
                mockMvc.perform(
                                post(
                                        "/api/v1/invoices"
                                )
                                        .contentType(
                                                MediaType.APPLICATION_JSON
                                        )
                                        .content(
                                                createRequest
                                        )
                        )
                        .andExpect(
                                status().isCreated()
                        )
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        JsonNode json =
                objectMapper.readTree(
                        responseBody
                );

        Long invoiceId =
                json
                        .get(
                                "invoiceId"
                        )
                        .asLong();

        mockMvc.perform(
                        get(
                                "/api/v1/invoices/{invoiceId}",
                                invoiceId
                        )
                )
                .andExpect(
                        status().isOk()
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
                                "$.invoiceNumber"
                        ).value(
                                "INT-GET-1001"
                        )
                )
                .andExpect(
                        jsonPath(
                                "$.supplierId"
                        ).value(
                                supplier.getId()
                        )
                );
    }


    @Test
    void exactDuplicateCheckShouldReturnTrue()
            throws Exception {

        Supplier supplier =
                createSupplier(
                        "Integration Exact Duplicate Supplier",
                        SupplierStatus.ACTIVE
                );

        String createRequest =
                """
                {
                    "invoiceNumber": "INT-EXACT-1001",
                    "supplierId": %d,
                    "invoiceAmount": 90000,
                    "invoiceDate": "2026-10-01",
                    "dueDate": "2026-10-31"
                }
                """.formatted(
                        supplier.getId()
                );

        mockMvc.perform(
                        post(
                                "/api/v1/invoices"
                        )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(
                                        createRequest
                                )
                )
                .andExpect(
                        status().isCreated()
                );

        mockMvc.perform(
                        get(
                                "/api/v1/invoices/duplicates/exact"
                        )
                                .param(
                                        "supplierId",
                                        supplier
                                                .getId()
                                                .toString()
                                )
                                .param(
                                        "invoiceNumber",
                                        "INT-EXACT-1001"
                                )
                )
                .andExpect(
                        status().isOk()
                )
                .andExpect(
                        jsonPath(
                                "$.duplicate"
                        ).value(
                                true
                        )
                );
    }


    private Supplier createSupplier(
            String supplierName,
            SupplierStatus status
    ) {

        Supplier supplier =
                new Supplier();

        supplier.setSupplierName(
                supplierName
        );

        supplier.setStatus(
                status
        );

        return supplierRepository.save(
                supplier
        );
    }
}