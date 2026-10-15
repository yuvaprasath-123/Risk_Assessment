package com.ofss.kafka;

import com.ofss.event.PaymentSubmittedEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class PaymentEventProducer {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(
                    PaymentEventProducer.class
            );

    private static final String PAYMENT_SUBMITTED_TOPIC =
            "payment.submitted";

    private final KafkaTemplate<String, Object>
            kafkaTemplate;

    public PaymentEventProducer(
            KafkaTemplate<String, Object> kafkaTemplate) {

        this.kafkaTemplate =
                kafkaTemplate;
    }

    public void publishPaymentSubmitted(
            PaymentSubmittedEvent event) {

        String key =
                String.valueOf(
                        event.getPaymentId()
                );

        LOGGER.info(
                "Publishing payment.submitted event for payment ID {}",
                event.getPaymentId()
        );

        kafkaTemplate.send(
                PAYMENT_SUBMITTED_TOPIC,
                key,
                event
        );

        LOGGER.info(
                "Kafka publish request sent for payment ID {}",
                event.getPaymentId()
        );
    }
}