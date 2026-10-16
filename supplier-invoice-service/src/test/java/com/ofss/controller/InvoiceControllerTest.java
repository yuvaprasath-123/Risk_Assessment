package com.ofss.controller;

import com.ofss.dto.CreateInvoiceRequest;
import com.ofss.dto.InvoiceResponse;
import com.ofss.service.InvoiceService;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(InvoiceController.class)
class InvoiceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper objectMapper;

    @MockitoBean
    private InvoiceService invoiceService;

    @Test
    void createInvoiceShouldReturnCreated()
            throws Exception {

        CreateInvoiceRequest request =
                new CreateInvoiceRequest(
                        "INV-100",
                        1L,
                        new BigDecimal("60000"),
                        LocalDate.of(
                                2026,
                                10,
                                1
                        ),
                        LocalDate.of(
                                2026,
                                10,
                                31
                        )
                );

        InvoiceResponse response =
                new InvoiceResponse(
                        10L,
                        "INV-100",
                        1L,
                        "ABC Supplier",
                        new BigDecimal("60000"),
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
                invoiceService.createInvoice(
                        any(CreateInvoiceRequest.class)
                )
        ).thenReturn(response);

        mockMvc.perform(
                        post(
                                "/api/v1/invoices"
                        )
                                .contentType(
                                        MediaType.APPLICATION_JSON
                                )
                                .content(
                                        objectMapper
                                                .writeValueAsString(
                                                        request
                                                )
                                )
                )
                .andExpect(
                        status().isCreated()
                )
                .andExpect(
                        jsonPath(
                                "$.invoiceId"
                        ).value(10)
                )
                .andExpect(
                        jsonPath(
                                "$.invoiceNumber"
                        ).value(
                                "INV-100"
                        )
                )
                .andExpect(
                        jsonPath(
                                "$.supplierId"
                        ).value(1)
                );
    }

    @Test
    void getInvoiceShouldReturnInvoice()
            throws Exception {

        InvoiceResponse response =
                new InvoiceResponse(
                        10L,
                        "INV-100",
                        1L,
                        "ABC Supplier",
                        new BigDecimal("60000"),
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
                invoiceService.getInvoice(
                        10L
                )
        ).thenReturn(response);

        mockMvc.perform(
                        get(
                                "/api/v1/invoices/10"
                        )
                )
                .andExpect(
                        status().isOk()
                )
                .andExpect(
                        jsonPath(
                                "$.invoiceId"
                        ).value(10)
                )
                .andExpect(
                        jsonPath(
                                "$.invoiceNumber"
                        ).value(
                                "INV-100"
                        )
                );
    }

    @Test
    void getInvoicesShouldReturnList()
            throws Exception {

        InvoiceResponse response =
                new InvoiceResponse(
                        10L,
                        "INV-100",
                        1L,
                        "ABC Supplier",
                        new BigDecimal("60000"),
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
                invoiceService.getInvoices(
                        null
                )
        ).thenReturn(
                List.of(response)
        );

        mockMvc.perform(
                        get(
                                "/api/v1/invoices"
                        )
                )
                .andExpect(
                        status().isOk()
                )
                .andExpect(
                        jsonPath(
                                "$[0].invoiceId"
                        ).value(10)
                );
    }
}