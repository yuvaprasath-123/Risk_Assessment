package com.ofss.kafka;

import com.ofss.event.PaymentSubmittedEvent;
import com.ofss.event.PaymentValidatedEvent;
import com.ofss.event.PaymentValidationFailedEvent;
import com.ofss.service.PaymentValidationService;
import com.ofss.service.ValidationResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class PaymentSubmittedConsumer {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(
                    PaymentSubmittedConsumer.class
            );

    private final PaymentValidationService
            paymentValidationService;

    private final PaymentValidationProducer
            paymentValidationProducer;

    public PaymentSubmittedConsumer(
            PaymentValidationService paymentValidationService,
            PaymentValidationProducer paymentValidationProducer) {

        this.paymentValidationService =
                paymentValidationService;

        this.paymentValidationProducer =
                paymentValidationProducer;
    }

    @KafkaListener(
            topics = "payment.submitted",
            groupId = "validation-service"
    )
    public void consume(
            PaymentSubmittedEvent event) {

        LOGGER.info(
                "Payment submitted event received for payment ID {}",
                event.getPaymentId()
        );

        ValidationResult result =
                paymentValidationService
                        .validate(
                                event
                        );

        if (result.valid()) {

            PaymentValidatedEvent validatedEvent =
                    new PaymentValidatedEvent(
                            event.getPaymentId(),
                            event.getInvoiceId(),
                            event.getPaymentAmount(),
                            event.getSubmittedBy(),
                            event.getSubmittedAt(),
                            LocalDateTime.now()
                    );

            paymentValidationProducer
                    .publishValidated(
                            validatedEvent
                    );

            LOGGER.info(
                    "Payment validation completed successfully for payment ID {}",
                    event.getPaymentId()
            );

            return;
        }

        PaymentValidationFailedEvent failedEvent =
                new PaymentValidationFailedEvent(
                        event.getPaymentId(),
                        event.getInvoiceId(),
                        event.getPaymentAmount(),
                        result.reason(),
                        LocalDateTime.now()
                );

        paymentValidationProducer
                .publishValidationFailed(
                        failedEvent
                );

        LOGGER.warn(
                "Payment validation failed for payment ID {}. Reason: {}",
                event.getPaymentId(),
                result.reason()
        );
    }
}