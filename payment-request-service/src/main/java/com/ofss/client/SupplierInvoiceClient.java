package com.ofss.client;

import com.ofss.exception.BadRequestException;
import com.ofss.exception.ResourceNotFoundException;

import org.springframework.beans.factory.annotation.Value;

import org.springframework.stereotype.Component;

import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class SupplierInvoiceClient {

    private final RestClient restClient;

    private final String baseUrl;

    public SupplierInvoiceClient(
            RestClient restClient,

            @Value(
                    "${services.supplier-invoice.base-url}"
            )
            String baseUrl
    ) {

        this.restClient =
                restClient;

        this.baseUrl =
                baseUrl;
    }

    public InvoiceClientResponse getInvoice(
            Long invoiceId
    ) {

        try {

            return restClient
                    .get()
                    .uri(
                            baseUrl
                                    + "/api/v1/invoices/"
                                    + invoiceId
                    )
                    .retrieve()
                    .body(
                            InvoiceClientResponse.class
                    );

        } catch (
                RestClientResponseException ex
        ) {

            if (
                    ex.getStatusCode()
                            .value() == 404
            ) {

                throw new ResourceNotFoundException(
                        "Invoice",
                        String.valueOf(
                                invoiceId
                        )
                );
            }

            throw new BadRequestException(
                    "Unable to validate invoice"
            );
        }
    }

    public SupplierClientResponse getSupplier(
            Long supplierId
    ) {

        try {

            return restClient
                    .get()
                    .uri(
                            baseUrl
                                    + "/api/v1/suppliers/"
                                    + supplierId
                    )
                    .retrieve()
                    .body(
                            SupplierClientResponse.class
                    );

        } catch (
                RestClientResponseException ex
        ) {

            if (
                    ex.getStatusCode()
                            .value() == 404
            ) {

                throw new ResourceNotFoundException(
                        "Supplier",
                        String.valueOf(
                                supplierId
                        )
                );
            }

            throw new BadRequestException(
                    "Unable to validate supplier"
            );
        }
    }
}