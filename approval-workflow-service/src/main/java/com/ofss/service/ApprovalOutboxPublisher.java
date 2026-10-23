
package com.ofss.service;

import com.ofss.event.ApprovalDecidedEvent;
import com.ofss.kafka.ApprovalDecisionProducer;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ApprovalOutboxPublisher {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(
                    ApprovalOutboxPublisher.class
            );

    private static final long KAFKA_TIMEOUT_SECONDS = 15L;

    private final JdbcTemplate jdbcTemplate;
    private final ApprovalDecisionProducer producer;

    public ApprovalOutboxPublisher(
            JdbcTemplate jdbcTemplate,
            ApprovalDecisionProducer producer
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.producer = producer;
    }

    /*
     * One database transaction per outbox event.
     *
     * Lock the event before publishing so two dispatchers
     * cannot publish the same pending row concurrently.
     *
     * Kafka acknowledgment occurs before marking the
     * event PUBLISHED.
     *
     * Delivery semantics are at-least-once: a crash after
     * Kafka acknowledgment but before Oracle commit can
     * result in a retry. Downstream consumers must deduplicate
     * using approvalDecisionId.
     */
    @Transactional
    public void publish(Long eventId) {

        if (eventId == null || eventId <= 0) {
            throw new IllegalArgumentException(
                    "Valid outbox event ID is required"
            );
        }

        List<OutboxEvent> events = jdbcTemplate.query(
                """
                SELECT
                    O.EVENT_ID,
                    O.APPROVAL_DECISION_ID,
                    O.PAYMENT_ID,
                    O.STATUS
                FROM PAY_APPROVAL_OUTBOX O
                WHERE O.EVENT_ID = ?
                FOR UPDATE OF O.STATUS
                """,
                (rs, rowNum) -> new OutboxEvent(
                        rs.getLong("EVENT_ID"),
                        rs.getLong("APPROVAL_DECISION_ID"),
                        rs.getLong("PAYMENT_ID"),
                        rs.getString("STATUS")
                ),
                eventId
        );

        if (events.isEmpty()) {
            LOGGER.warn(
                    "Approval outbox event {} no longer exists",
                    eventId
            );
            return;
        }

        OutboxEvent outbox = events.get(0);

        if (!"PENDING".equals(outbox.status())) {
            return;
        }

        /*
         * Load the committed approval decision.
         * The payment and decision IDs must match the
         * values persisted in the outbox.
         */
        List<ApprovalDecidedEvent> decisions =
                jdbcTemplate.query(
                        """
                        SELECT
                            D.ID,
                            D.TASK_ID,
                            D.PAYMENT_ID,
                            D.APPROVER_ID,
                            D.DECISION,
                            D.DECIDED_AT
                        FROM APPROVAL_DECISIONS D
                        WHERE D.ID = ?
                          AND D.PAYMENT_ID = ?
                        """,
                        (rs, rowNum) -> {

                            OffsetDateTime decidedAt =
                                    rs.getObject(
                                            "DECIDED_AT",
                                            OffsetDateTime.class
                                    );

                            return new ApprovalDecidedEvent(
                                    rs.getLong("PAYMENT_ID"),
                                    rs.getLong("TASK_ID"),
                                    rs.getLong("ID"),
                                    rs.getLong("APPROVER_ID"),
                                    rs.getString("DECISION"),
                                    decidedAt
                            );
                        },
                        outbox.approvalDecisionId(),
                        outbox.paymentId()
                );

        if (decisions.size() != 1) {
            recordFailure(
                    eventId,
                    "Expected exactly one matching approval decision"
            );

            LOGGER.error(
                    "Approval decision missing or mismatched " +
                    "for outbox event {}",
                    eventId
            );
            return;
        }

        ApprovalDecidedEvent event = decisions.get(0);

        try {

            /*
             * Wait for the Kafka broker acknowledgment.
             *
             * Do not mark PUBLISHED merely because the
             * asynchronous send method returned.
             */
            producer.publish(event)
                    .get(
                            KAFKA_TIMEOUT_SECONDS,
                            TimeUnit.SECONDS
                    );

            int updated = jdbcTemplate.update(
                    """
                    UPDATE PAY_APPROVAL_OUTBOX
                    SET STATUS = 'PUBLISHED',
                        ATTEMPT_COUNT = ATTEMPT_COUNT + 1,
                        PUBLISHED_AT = SYSTIMESTAMP,
                        LAST_ERROR = NULL
                    WHERE EVENT_ID = ?
                      AND STATUS = 'PENDING'
                    """,
                    eventId
            );

            if (updated != 1) {
                throw new IllegalStateException(
                        "Outbox publication status update failed"
                );
            }

            LOGGER.info(
                    "Approval outbox event {} published; " +
                    "approval decision ID {}",
                    eventId,
                    event.approvalDecisionId()
            );

        } catch (InterruptedException exception) {

            Thread.currentThread().interrupt();

            recordFailure(
                    eventId,
                    "Kafka publication interrupted"
            );

            LOGGER.error(
                    "Kafka publication interrupted for event {}",
                    eventId,
                    exception
            );

        } catch (Exception exception) {

            /*
             * Keep the event PENDING so the existing
             * scheduled dispatcher can retry it.
             *
             * Do not store the entire exception because
             * it may contain sensitive connection details.
             */
            recordFailure(
                    eventId,
                    exception.getClass().getSimpleName()
            );

            LOGGER.error(
                    "Kafka publication failed for outbox event {}; " +
                    "event remains pending",
                    eventId,
                    exception
            );
        }
    }

    private void recordFailure(
            Long eventId,
            String reason
    ) {

        String safeReason =
                reason == null || reason.isBlank()
                        ? "Unknown publication failure"
                        : reason;

        if (safeReason.length() > 200) {
            safeReason = safeReason.substring(0, 200);
        }

        jdbcTemplate.update(
                """
                UPDATE PAY_APPROVAL_OUTBOX
                SET ATTEMPT_COUNT = ATTEMPT_COUNT + 1,
                    LAST_ERROR = ?
                WHERE EVENT_ID = ?
                  AND STATUS = 'PENDING'
                """,
                safeReason,
                eventId
        );
    }

    private record OutboxEvent(
            Long eventId,
            Long approvalDecisionId,
            Long paymentId,
            String status
    ) {
    }
}
