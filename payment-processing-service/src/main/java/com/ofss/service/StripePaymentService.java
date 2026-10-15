package com.ofss.service;

import com.ofss.event.PaymentApprovedEvent;

import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class StripePaymentService {

    @Value("${stripe.currency}")
    private String currency;

    @Value("${stripe.test-payment-method}")
    private String testPaymentMethod;

    public PaymentIntent createAndConfirmPayment(
            PaymentApprovedEvent event
    ) throws StripeException {

        if (event == null) {

            throw new IllegalArgumentException(
                    "Payment event is required"
            );
        }

        if (event.getPaymentId() == null) {

            throw new IllegalArgumentException(
                    "Payment ID is required"
            );
        }

        if (
                event.getPaymentAmount() == null
                ||
                event.getPaymentAmount()
                        .compareTo(BigDecimal.ZERO) <= 0
        ) {

            throw new IllegalArgumentException(
                    "Payment amount must be greater than zero"
            );
        }

        long amountInPaise =
                event.getPaymentAmount()
                        .multiply(
                                new BigDecimal("100")
                        )
                        .setScale(
                                0,
                                RoundingMode.HALF_UP
                        )
                        .longValueExact();

        PaymentIntentCreateParams.AutomaticPaymentMethods
                automaticPaymentMethods =
                PaymentIntentCreateParams
                        .AutomaticPaymentMethods
                        .builder()
                        .setEnabled(true)
                        .setAllowRedirects(
                                PaymentIntentCreateParams
                                        .AutomaticPaymentMethods
                                        .AllowRedirects
                                        .NEVER
                        )
                        .build();

        PaymentIntentCreateParams params =
                PaymentIntentCreateParams
                        .builder()
                        .setAmount(amountInPaise)
                        .setCurrency(currency)
                        .setPaymentMethod(testPaymentMethod)
                        .setConfirm(true)
                        .setAutomaticPaymentMethods(
                                automaticPaymentMethods
                        )
                        .putMetadata(
                                "paymentId",
                                event.getPaymentId()
                                        .toString()
                        )
                        .putMetadata(
                                "invoiceId",
                                String.valueOf(
                                        event.getInvoiceId()
                                )
                        )
                        .putMetadata(
                                "supplierId",
                                String.valueOf(
                                        event.getSupplierId()
                                )
                        )
                        .build();

        RequestOptions requestOptions =
                RequestOptions
                        .builder()
                        .setIdempotencyKey(
                                "payment-approved-"
                                        + event.getPaymentId()
                        )
                        .build();

        return PaymentIntent.create(
                params,
                requestOptions
        );
    }
}