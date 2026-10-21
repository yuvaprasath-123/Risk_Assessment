
package com.ofss.service;

import com.ofss.event.ApprovalDecidedEvent;
import com.ofss.kafka.ApprovalDecisionProducer;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ApprovalOutboxPublisher {

    private final JdbcTemplate jdbcTemplate;
    private final ApprovalDecisionProducer producer;
    private final TransactionTemplate transactionTemplate;

    public ApprovalOutboxPublisher(
            JdbcTemplate jdbcTemplate,
            ApprovalDecisionProducer producer,
            org.springframework.transaction.PlatformTransactionManager transactionManager
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.producer = producer;
        this.transactionTemplate = new TransactionTemplate(
                transactionManager
        );

        /*
         * Keep the Oracle row lock for the entire
         * publish-and-acknowledge operation.
         */
        this.transactionTemplate.setTimeout(30);
    }

    public void publish(Long eventId) {

        transactionTemplate.executeWithoutResult(status -> {

            /*
             * Lock this outbox row so concurrent
             * dispatchers cannot publish it simultaneously.
             */
            List<Long> pendingRows = jdbcTemplate.query(
                    """
                    SELECT EVENT_ID
                    FROM PAY_APPROVAL_OUTBOX
                    WHERE EVENT_ID = ?
                      AND STATUS = 'PENDING'
                    FOR UPDATE
                    """,
                    (rs, rowNum) -> rs.getLong("EVENT_ID"),
                    eventId
            );

            if (pendingRows.isEmpty()) {
                return;
            }

            List<ApprovalDecidedEvent> events =
                    jdbcTemplate.query(
                            """
                            SELECT
                                D.PAYMENT_ID,
                                D.TASK_ID,
                                D.ID AS DECISION_ID,
                                D.APPROVER_ID,
                                D.DECISION,
                                D.DECIDED_AT
                            FROM PAY_APPROVAL_OUTBOX O
                            JOIN APPROVAL_DECISIONS D
                              ON D.ID = O.APPROVAL_DECISION_ID
                            JOIN APPROVAL_TASKS T
                              ON T.ID = D.TASK_ID
                            WHERE O.EVENT_ID = ?
                              AND O.PAYMENT_ID = D.PAYMENT_ID
                              AND T.PAYMENT_ID = D.PAYMENT_ID
                              AND T.DECIDED_BY = D.APPROVER_ID
                              AND (
                                   (T.STATUS = 'APPROVED'
                                    AND D.DECISION IN (
                                        'APPROVED',
                                        'OVERRIDE_APPROVED'
                                    ))
                                   OR
                                   (T.STATUS = 'REJECTED'
                                    AND D.DECISION = 'REJECTED')
                              )
                            """,
                            (rs, rowNum) -> {

                                OffsetDateTime decidedAt =
                                        rs.getObject(
                                                "DECIDED_AT",
                                                OffsetDateTime.class
                                        );

                                if (decidedAt == null) {
                                    throw new IllegalStateException(
                                            "Missing approval timestamp"
                                    );
                                }

                                return new ApprovalDecidedEvent(
                                        rs.getLong("PAYMENT_ID"),
                                        rs.getLong("TASK_ID"),
                                        rs.getLong("DECISION_ID"),
                                        rs.getLong("APPROVER_ID"),
                                        rs.getString("DECISION"),
                                        decidedAt
                                );
                            },
                            eventId
                    );

            if (events.size() != 1) {
                throw new IllegalStateException(
                        "Expected exactly one verified approval decision "
                                + "for outbox event " + eventId
                );
            }

            ApprovalDecidedEvent event = events.get(0);

            try {
                /*
                 * Wait for Kafka broker acknowledgment.
                 *
                 * Do not mark PUBLISHED until Kafka
                 * confirms the message.
                 */
                producer.publish(event)
                        .get(15, TimeUnit.SECONDS);

                int updated = jdbcTemplate.update(
                        """
                        UPDATE PAY_APPROVAL_OUTBOX
                        SET STATUS = 'PUBLISHED',
                            PUBLISHED_AT = SYSTIMESTAMP,
                            ATTEMPT_COUNT = ATTEMPT_COUNT + 1,
                            LAST_ERROR = NULL
                        WHERE EVENT_ID = ?
                          AND STATUS = 'PENDING'
                        """,
                        eventId
                );

                if (updated != 1) {
                    throw new IllegalStateException(
                            "Unable to mark approval event published: "
                                    + eventId
                    );
                }

            } catch (InterruptedException exception) {

                Thread.currentThread().interrupt();

                recordFailure(eventId, exception);

            } catch (Exception exception) {

                recordFailure(eventId, exception);
            }
        });
    }

    private void recordFailure(
            Long eventId,
            Exception exception
    ) {

        String message = exception.getMessage();

        if (message == null) {
            message = exception.getClass().getSimpleName();
        }

        if (message.length() > 1900) {
            message = message.substring(0, 1900);
        }

        jdbcTemplate.update(
                """
                UPDATE PAY_APPROVAL_OUTBOX
                SET ATTEMPT_COUNT = ATTEMPT_COUNT + 1,
                    LAST_ERROR = ?
                WHERE EVENT_ID = ?
                  AND STATUS = 'PENDING'
                """,
                message,
                eventId
        );
    }
}

