package com.ofss.service;

import com.ofss.entity.PaymentRequest;
import com.ofss.entity.PaymentStatus;
import com.ofss.event.PaymentCompletedEvent;
import com.ofss.event.PaymentFailedEvent;
import com.ofss.repository.PaymentRequestRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentResultService {

    private final PaymentRequestRepository paymentRequestRepository;

    public PaymentResultService(
            PaymentRequestRepository paymentRequestRepository
    ) {
        this.paymentRequestRepository =
                paymentRequestRepository;
    }

    @Transactional
    public void handleCompleted(
            PaymentCompletedEvent event
    ) {

        PaymentRequest paymentRequest =
                paymentRequestRepository
                        .findById(event.getPaymentId())
                        .orElseThrow(
                                () -> new IllegalArgumentException(
                                        "Payment not found with ID: "
                                                + event.getPaymentId()
                                )
                        );

        paymentRequest.setStatus(
                PaymentStatus.COMPLETE
        );

        paymentRequest.setTransactionReference(
                event.getTransactionReference()
        );

        paymentRequest.setCompletedAt(
                event.getCompletedAt()
        );

        paymentRequest.setFailedAt(null);

        paymentRequest.setFailureReason(null);

        paymentRequestRepository.save(
                paymentRequest
        );
    }

    @Transactional
    public void handleFailed(
            PaymentFailedEvent event
    ) {

        PaymentRequest paymentRequest =
                paymentRequestRepository
                        .findById(event.getPaymentId())
                        .orElseThrow(
                                () -> new IllegalArgumentException(
                                        "Payment not found with ID: "
                                                + event.getPaymentId()
                                )
                        );

        paymentRequest.setStatus(
                PaymentStatus.FAILED
        );

        paymentRequest.setFailedAt(
                event.getFailedAt()
        );

        paymentRequest.setFailureReason(
                event.getReason()
        );

        paymentRequestRepository.save(
                paymentRequest
        );
    }
}