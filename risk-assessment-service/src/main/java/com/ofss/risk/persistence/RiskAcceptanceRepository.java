package com.ofss.risk.persistence;

import java.io.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** All writes must be called within RiskAcceptanceService's transaction boundary. */
public class RiskAcceptanceRepository {
    static final String FIND = """
            SELECT A.*, S.SNAPSHOT_ID AS SAVED_SNAPSHOT_ID, S.ASSESSMENT_ID AS SNAPSHOT_PARENT_ID,
                   S.SOURCE_VERSION_ID, S.SOURCE_ROW_VERSION, S.SNAPSHOT_FORMAT_VERSION,
                   S.HASH_FORMAT_VERSION AS SNAPSHOT_HASH_FORMAT, S.CONFIGURATION_JSON,
                   S.CONTENT_HASH AS SNAPSHOT_HASH, S.CAPTURED_AT
            FROM D2_RISK.RISK_ASSESSMENTS A
            LEFT JOIN D2_RISK.RISK_CONFIGURATION_SNAPSHOTS S
              ON S.ASSESSMENT_ID = A.ASSESSMENT_ID AND S.SNAPSHOT_ID = A.CONFIGURATION_SNAPSHOT_ID
            WHERE A.SUBMISSION_SOURCE = ? AND A.SUBMISSION_KEY = ?
            """;
    static final String INSERT = """
            INSERT INTO D2_RISK.RISK_ASSESSMENTS
              (SUBMISSION_SOURCE, SUBMISSION_KEY, PAYMENT_ID, SUPPLIER_ID, INVOICE_ID, REQUESTER_ID,
               VALIDATION_REFERENCE, VALIDATION_REVISION, PAYMENT_AMOUNT, CURRENCY_CODE,
               CONFIGURATION_VERSION_ID, CONFIGURATION_ROW_VERSION, EVALUATOR_VERSION,
               HASH_FORMAT_VERSION, ACCEPTANCE_JSON, ACCEPTANCE_HASH, CONFIGURATION_HASH,
               EVIDENCE_AS_OF, CREATED_AT, CORRELATION_ID)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    static final String SNAPSHOT = """
            INSERT INTO D2_RISK.RISK_CONFIGURATION_SNAPSHOTS
              (ASSESSMENT_ID, SOURCE_VERSION_ID, SOURCE_ROW_VERSION, SNAPSHOT_FORMAT_VERSION,
               HASH_FORMAT_VERSION, CONFIGURATION_JSON, CONTENT_HASH, CAPTURED_AT)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;
    static final String ASSOCIATE = """
            UPDATE D2_RISK.RISK_ASSESSMENTS SET CONFIGURATION_SNAPSHOT_ID = ?
            WHERE ASSESSMENT_ID = ? AND CONFIGURATION_SNAPSHOT_ID IS NULL
              AND WORK_STATE = 'ACCEPTED' AND ROW_VERSION = 0
            """;
    private final JdbcTemplate jdbc;
    public RiskAcceptanceRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<AcceptedAssessment> find(SubmissionIdentity identity) {
        var rows = jdbc.query(FIND, (rs, index) -> map(rs), identity.source(), identity.key());
        if (rows.size() > 1 || (!rows.isEmpty() && !identity.equals(rows.getFirst().identity()))) throw invalid();
        return rows.stream().findFirst();
    }

    AcceptedAssessment insert(AcceptanceRequest r, CanonicalAcceptance.Document acceptance,
            CanonicalAcceptance.Document config, Instant createdAt) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !TransactionSynchronizationManager.hasResource(Objects.requireNonNull(jdbc.getDataSource()))) throw invalid();
        long version = PersistenceValues.id(r.configuration().versionId());
        long revision = Long.parseLong(r.configuration().rowVersion());
        long assessment = identity(INSERT, "ASSESSMENT_ID", true, (connection, s, lobs) -> {
            s.setString(1, r.identity().source()); s.setString(2, r.identity().key());
            s.setLong(3, r.paymentId()); s.setLong(4, PersistenceValues.id(r.current().supplierId()));
            s.setLong(5, PersistenceValues.id(r.current().invoiceId())); s.setLong(6, r.requesterId());
            s.setString(7, r.validationReference()); nullableText(s, 8, r.validationRevision());
            s.setBigDecimal(9, r.current().amount()); s.setString(10, r.current().currencyCode());
            s.setLong(11, version); s.setLong(12, revision); s.setString(13, r.evaluatorVersion());
            s.setString(14, CanonicalAcceptance.HASH_FORMAT); lobs.bind(connection, s, 15, acceptance.json());
            s.setString(16, acceptance.hash()); s.setString(17, config.hash());
            time(s, 18, r.cutoff()); time(s, 19, createdAt); nullableText(s, 20, r.correlationId());
        });
        long snapshot = identity(SNAPSHOT, "SNAPSHOT_ID", false, (connection, s, lobs) -> {
            s.setLong(1, assessment); s.setLong(2, version); s.setLong(3, revision);
            s.setString(4, CanonicalAcceptance.SNAPSHOT_FORMAT); s.setString(5, CanonicalAcceptance.HASH_FORMAT);
            lobs.bind(connection, s, 6, config.json()); s.setString(7, config.hash()); time(s, 8, createdAt);
        });
        if (jdbc.update(ASSOCIATE, snapshot, assessment) != 1) throw invalid();
        var saved = find(r.identity()).orElseThrow(RiskAcceptanceRepository::invalid);
        if (!Long.toString(assessment).equals(saved.assessmentId())
                || !Long.toString(snapshot).equals(saved.configurationSnapshotId())
                || !acceptance.json().equals(saved.acceptanceJson()) || !config.json().equals(saved.configurationJson())
                || !createdAt.equals(saved.createdAt()) || !"ACCEPTED".equals(saved.workState())
                || !"0".equals(saved.rowVersion())) throw invalid();
        return saved;
    }

    @FunctionalInterface private interface Bindings {
        void bind(Connection connection, PreparedStatement statement, TemporaryClobs lobs) throws SQLException;
    }
    private long identity(String sql, String column, boolean parent, Bindings bindings) {
        try (var lobs = new TemporaryClobs()) {
        Long result = jdbc.execute((PreparedStatementCreator) connection -> {
            var statement = connection.prepareStatement(sql, new String[]{column});
            try { bindings.bind(connection, statement, lobs); return statement; }
            catch (SQLException | RuntimeException | Error failure) {
                // JdbcTemplate cannot close a statement whose creator failed before returning it.
                try { statement.close(); } catch (SQLException closeFailure) { failure.addSuppressed(closeFailure); }
                throw failure;
            }
        }, s -> {
            final int count;
            try { count = s.executeUpdate(); }
            catch (SQLException e) {
                if (parent && SubmissionCollision.matches(e)) throw new SubmissionCollision();
                throw e;
            }
            if (count != 1) throw invalid();
            // Named identity request avoids Oracle ROWID; exact conversion rejects fractions/overflow.
            try (var keys = s.getGeneratedKeys()) {
                if (keys == null || !keys.next() || keys.getMetaData().getColumnCount() != 1) throw invalid();
                long id = PersistenceValues.number(keys.getObject(1), true);
                if (keys.next()) throw invalid();
                return id;
            }
        });
        if (result == null) throw invalid();
        return result;
        }
    }

    /** Auto-generated keys use Oracle RETURNING, which does not support stream binding.
     * Populate temporary LOB locators first and retain them through execution/key retrieval. */
    private static final class TemporaryClobs implements AutoCloseable {
        private final List<Clob> allocated = new ArrayList<>();
        void bind(Connection connection, PreparedStatement statement, int index, String json) throws SQLException {
            Clob clob = connection.createClob();
            if (clob == null) throw invalid();
            allocated.add(clob);
            if (clob.setString(1, json) != json.length()) throw invalid();
            statement.setClob(index, clob);
        }
        @Override public void close() {
            SQLException failure = null;
            for (Clob clob : allocated) {
                try { clob.free(); }
                catch (SQLException error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
            }
            if (failure != null) throw new UncategorizedSQLException("Temporary CLOB cleanup", null, failure);
        }
    }

    private static AcceptedAssessment map(ResultSet rs) throws SQLException {
        long assessment = number(rs, "ASSESSMENT_ID", true);
        long snapshot = number(rs, "CONFIGURATION_SNAPSHOT_ID", true);
        long version = number(rs, "CONFIGURATION_VERSION_ID", true);
        long revision = number(rs, "CONFIGURATION_ROW_VERSION", false);
        if (snapshot != number(rs, "SAVED_SNAPSHOT_ID", true)
                || assessment != number(rs, "SNAPSHOT_PARENT_ID", true)
                || version != number(rs, "SOURCE_VERSION_ID", true)
                || revision != number(rs, "SOURCE_ROW_VERSION", false)
                || !CanonicalAcceptance.HASH_FORMAT.equals(rs.getString("HASH_FORMAT_VERSION"))
                || !CanonicalAcceptance.HASH_FORMAT.equals(rs.getString("SNAPSHOT_HASH_FORMAT"))
                || !CanonicalAcceptance.SNAPSHOT_FORMAT.equals(rs.getString("SNAPSHOT_FORMAT_VERSION"))) throw invalid();
        String acceptanceJson = clob(rs, "ACCEPTANCE_JSON"), configJson = clob(rs, "CONFIGURATION_JSON");
        String acceptanceHash = rs.getString("ACCEPTANCE_HASH"), configHash = rs.getString("CONFIGURATION_HASH");
        if (!CanonicalAcceptance.hash(acceptanceJson).equals(acceptanceHash)
                || !CanonicalAcceptance.hash(configJson).equals(configHash)
                || !configHash.equals(rs.getString("SNAPSHOT_HASH"))) throw invalid();
        var a = CanonicalAcceptance.read(acceptanceJson);
        var c = CanonicalAcceptance.read(configJson);
        Instant cutoff = time(rs, "EVIDENCE_AS_OF"), created = time(rs, "CREATED_AT");
        var identity = new SubmissionIdentity(rs.getString("SUBMISSION_SOURCE"), rs.getString("SUBMISSION_KEY"));
        same(a, "hashFormatVersion", CanonicalAcceptance.HASH_FORMAT);
        same(c, "hashFormatVersion", CanonicalAcceptance.HASH_FORMAT);
        same(c, "snapshotFormatVersion", CanonicalAcceptance.SNAPSHOT_FORMAT);
        same(a, "configurationVersionId", Long.toString(version)); same(a, "configurationRowVersion", Long.toString(revision));
        same(a, "configurationHash", configHash); same(a, "cutoff", cutoff.toString());
        same(a, "paymentId", Long.toString(number(rs, "PAYMENT_ID", true)));
        same(a, "requesterId", Long.toString(number(rs, "REQUESTER_ID", true)));
        same(a, "evaluatorVersion", rs.getString("EVALUATOR_VERSION"));
        same(a, "validationReference", rs.getString("VALIDATION_REFERENCE")); same(a, "validationRevision", rs.getString("VALIDATION_REVISION"));
        var submission = nested(a, "submission"); same(submission, "source", identity.source()); same(submission, "key", identity.key());
        var current = nested(a, "current");
        same(current, "supplierId", Long.toString(number(rs, "SUPPLIER_ID", true)));
        same(current, "invoiceId", Long.toString(number(rs, "INVOICE_ID", true)));
        same(current, "currencyCode", rs.getString("CURRENCY_CODE"));
        var money = rs.getBigDecimal("PAYMENT_AMOUNT");
        if (money == null) throw invalid();
        same(current, "amount", money.setScale(2, java.math.RoundingMode.UNNECESSARY).toPlainString());
        var configuration = nested(c, "configuration");
        same(configuration, "versionId", Long.toString(version)); same(configuration, "rowVersion", Long.toString(revision));
        if (!created.equals(time(rs, "CAPTURED_AT")) || cutoff.isAfter(created)) throw invalid();
        String state = rs.getString("WORK_STATE");
        if (!Set.of("ACCEPTED", "IN_PROGRESS", "RETRYABLE_FAILURE", "BLOCKED", "COMPLETED").contains(state)) throw invalid();
        return new AcceptedAssessment(Long.toString(assessment), identity, Long.toString(snapshot), Long.toString(version),
                Long.toString(revision), Long.toString(number(rs, "ROW_VERSION", false)), state, cutoff, created,
                acceptanceHash, configHash, acceptanceJson, configJson);
    }
    private static Map<?, ?> nested(Map<?, ?> map, String key) {
        if (map.get(key) instanceof Map<?, ?> nested) return nested;
        throw invalid();
    }
    private static void same(Map<?, ?> map, String key, Object expected) {
        if (!map.containsKey(key) || !Objects.equals(expected, map.get(key))) throw invalid();
    }
    private static long number(ResultSet rs, String column, boolean positive) throws SQLException {
        return PersistenceValues.number(rs.getObject(column), positive);
    }
    private static Instant time(ResultSet rs, String column) throws SQLException {
        var value = rs.getObject(column, OffsetDateTime.class);
        if (value == null) throw invalid();
        Instant instant = value.toInstant(); PersistenceValues.timestamp(instant); return instant;
    }
    private static void time(PreparedStatement s, int index, Instant value) throws SQLException {
        s.setObject(index, value.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE);
    }
    private static void nullableText(PreparedStatement s, int index, String value) throws SQLException {
        if (value == null) s.setNull(index, Types.VARCHAR); else s.setString(index, value);
    }
    private static String clob(ResultSet rs, String column) throws SQLException {
        try (var reader = rs.getCharacterStream(column)) {
            if (reader == null) throw invalid();
            var result = new StringWriter(); reader.transferTo(result); return result.toString();
        } catch (IOException e) { throw invalid(); }
    }
    private static DataRetrievalFailureException invalid() { return new DataRetrievalFailureException("Invalid stored acceptance"); }
}
