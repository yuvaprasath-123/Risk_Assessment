package com.ofss.risk.persistence;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** TEST ONLY: exact UUID namespace, fixed keys, synthetic markers and untouched ACCEPTED rows required. */
final class OracleAcceptanceFixtureCleaner {
    static final String LOCK = """
            SELECT ASSESSMENT_ID, SUBMISSION_KEY, WORK_STATE, EVALUATOR_VERSION, VALIDATION_REFERENCE,
                   CONFIGURATION_SNAPSHOT_ID, FROZEN_INPUT_EVIDENCE_ID, INPUT_HASH,
                   ATTEMPT_COUNT, LEASE_EPOCH, ROW_VERSION
            FROM D2_RISK.RISK_ASSESSMENTS WHERE SUBMISSION_SOURCE = ? FOR UPDATE
            """;
    static final String DEPENDENTS = """
            SELECT (SELECT COUNT(*) FROM D2_RISK.RISK_ASSESSMENT_FACTORS WHERE ASSESSMENT_ID = ?) +
                   (SELECT COUNT(*) FROM D2_RISK.RISK_INPUT_EVIDENCE WHERE ASSESSMENT_ID = ?) FROM DUAL
            """;
    static final String DETACH = """
            UPDATE D2_RISK.RISK_ASSESSMENTS SET CONFIGURATION_SNAPSHOT_ID = NULL
            WHERE ASSESSMENT_ID = ? AND SUBMISSION_SOURCE = ? AND SUBMISSION_KEY = ?
              AND WORK_STATE = 'ACCEPTED' AND EVALUATOR_VERSION = ? AND VALIDATION_REFERENCE = ?
              AND ROW_VERSION = 0 AND ATTEMPT_COUNT = 0 AND LEASE_EPOCH = 0
              AND FROZEN_INPUT_EVIDENCE_ID IS NULL AND INPUT_HASH IS NULL
            """;
    static final String DELETE_SNAPSHOT = """
            DELETE FROM D2_RISK.RISK_CONFIGURATION_SNAPSHOTS S
            WHERE S.ASSESSMENT_ID = ? AND S.SNAPSHOT_ID = ?
              AND EXISTS (SELECT 1 FROM D2_RISK.RISK_ASSESSMENTS A
                          WHERE A.ASSESSMENT_ID = S.ASSESSMENT_ID AND A.SUBMISSION_SOURCE = ?
                            AND A.SUBMISSION_KEY = ? AND A.EVALUATOR_VERSION = ? AND A.VALIDATION_REFERENCE = ?)
            """;
    static final String DELETE_PARENT = """
            DELETE FROM D2_RISK.RISK_ASSESSMENTS
            WHERE ASSESSMENT_ID = ? AND SUBMISSION_SOURCE = ? AND SUBMISSION_KEY = ?
              AND WORK_STATE = 'ACCEPTED' AND EVALUATOR_VERSION = ? AND VALIDATION_REFERENCE = ?
              AND CONFIGURATION_SNAPSHOT_ID IS NULL AND ROW_VERSION = 0 AND ATTEMPT_COUNT = 0 AND LEASE_EPOCH = 0
              AND FROZEN_INPUT_EVIDENCE_ID IS NULL AND INPUT_HASH IS NULL
            """;
    record Row(long id, String key, String state, String evaluator, String validation,
            Object snapshot, Object frozenInput, String inputHash, long attempts, long epoch, long revision) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    OracleAcceptanceFixtureCleaner(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc; transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    void clean(String namespace) {
        OracleAcceptanceFixtures.requireNamespace(namespace); // Refuse before any connection/SQL.
        transaction.executeWithoutResult(status -> {
            var rows = jdbc.query(LOCK, (rs, i) -> new Row(PersistenceValues.number(rs.getObject("ASSESSMENT_ID"), true),
                    rs.getString("SUBMISSION_KEY"), rs.getString("WORK_STATE"), rs.getString("EVALUATOR_VERSION"), rs.getString("VALIDATION_REFERENCE"),
                    rs.getObject("CONFIGURATION_SNAPSHOT_ID"), rs.getObject("FROZEN_INPUT_EVIDENCE_ID"), rs.getString("INPUT_HASH"),
                    PersistenceValues.number(rs.getObject("ATTEMPT_COUNT"), false), PersistenceValues.number(rs.getObject("LEASE_EPOCH"), false),
                    PersistenceValues.number(rs.getObject("ROW_VERSION"), false)), namespace);
            // Validate ALL locked rows before touching any of them; reject unknown keys or later workflow data.
            var seen = new HashSet<String>();
            for (var row : rows) {
                require(OracleAcceptanceFixtures.KEYS.contains(row.key()) && seen.add(row.key())
                        && "ACCEPTED".equals(row.state()) && OracleAcceptanceFixtures.EVALUATOR.equals(row.evaluator())
                        && ("synthetic-pass/" + row.key()).equals(row.validation())
                        && row.frozenInput() == null && row.inputHash() == null && row.attempts() == 0 && row.epoch() == 0 && row.revision() == 0);
                Long children = jdbc.queryForObject(DEPENDENTS, Long.class, row.id(), row.id());
                require(children != null && children == 0);
                if (row.snapshot() != null) PersistenceValues.number(row.snapshot(), true);
            }
            for (var row : rows) {
                require(jdbc.update(DETACH, row.id(), namespace, row.key(), row.evaluator(), row.validation()) == 1);
                if (row.snapshot() != null) require(jdbc.update(DELETE_SNAPSHOT, row.id(), PersistenceValues.number(row.snapshot(), true),
                        namespace, row.key(), row.evaluator(), row.validation()) == 1);
                require(jdbc.update(DELETE_PARENT, row.id(), namespace, row.key(), row.evaluator(), row.validation()) == 1);
            }
        });
    }
    private static void require(boolean valid) {
        if (!valid) throw new IllegalStateException("Fixture cleanup refused: unexpected identity, lifecycle or dependent rows");
    }
}
