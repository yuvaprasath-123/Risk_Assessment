package com.ofss.kafka;

import com.ofss.event.PaymentApprovedEvent;
import com.ofss.event.PaymentCompletedEvent;
import com.ofss.event.PaymentFailedEvent;

import com.ofss.service.StripePaymentService;

import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class PaymentApprovedConsumer {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(
                    PaymentApprovedConsumer.class
            );

    private final StripePaymentService
            stripePaymentService;

    private final PaymentProcessingProducer
            paymentProcessingProducer;

    public PaymentApprovedConsumer(
            StripePaymentService stripePaymentService,
            PaymentProcessingProducer paymentProcessingProducer
    ) {

        this.stripePaymentService =
                stripePaymentService;

        this.paymentProcessingProducer =
                paymentProcessingProducer;
    }

    @KafkaListener(
            topics = "payment.approved",
            groupId = "payment-processing-service"
    )
    public void consume(
            PaymentApprovedEvent event
    ) {

        LOGGER.info(
                "Received payment.approved for payment ID {}",
                event.getPaymentId()
        );

        try {

            LOGGER.info(
                    "Sending payment ID {} to Stripe",
                    event.getPaymentId()
            );

            PaymentIntent paymentIntent =
                    stripePaymentService
                            .createAndConfirmPayment(
                                    event
                            );

            LOGGER.info(
                    "Stripe PaymentIntent created. Payment ID={}, Stripe ID={}, status={}",
                    event.getPaymentId(),
                    paymentIntent.getId(),
                    paymentIntent.getStatus()
            );

            if (
                    "succeeded".equalsIgnoreCase(
                            paymentIntent.getStatus()
                    )
            ) {

                PaymentCompletedEvent completedEvent =
                        new PaymentCompletedEvent(
                                event.getPaymentId(),
                                event.getPaymentAmount(),
                                paymentIntent.getId(),
                                LocalDateTime.now()
                        );

                paymentProcessingProducer
                        .publishCompleted(
                                completedEvent
                        );

                LOGGER.info(
                        "Payment completed successfully. Payment ID={}, Stripe ID={}",
                        event.getPaymentId(),
                        paymentIntent.getId()
                );

            } else {

                PaymentFailedEvent failedEvent =
                        new PaymentFailedEvent(
                                event.getPaymentId(),
                                event.getPaymentAmount(),
                                "Stripe returned status: "
                                        + paymentIntent.getStatus(),
                                LocalDateTime.now()
                        );

                paymentProcessingProducer
                        .publishFailed(
                                failedEvent
                        );

                LOGGER.warn(
                        "Payment did not succeed. Payment ID={}, status={}",
                        event.getPaymentId(),
                        paymentIntent.getStatus()
                );
            }

        } catch (
                StripeException |
                IllegalArgumentException exception
        ) {

            LOGGER.error(
                    "Stripe payment failed for payment ID {}",
                    event.getPaymentId(),
                    exception
            );

            PaymentFailedEvent failedEvent =
                    new PaymentFailedEvent(
                            event.getPaymentId(),
                            event.getPaymentAmount(),
                            exception.getMessage(),
                            LocalDateTime.now()
                    );

            paymentProcessingProducer
                    .publishFailed(
                            failedEvent
                    );
        }
    }
}