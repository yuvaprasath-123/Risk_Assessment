package com.ofss.risk.persistence;

import java.sql.*;
import java.io.StringReader;
import java.time.*;
import java.util.*;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Parent-row locking plus conditional updates: no mutation of acceptance, snapshots or frozen inputs. */
public class RiskWorkRepository {
    void beginSnapshot(){new RiskFinalizationRepository(jdbc).beginSnapshot();}
    static final String READ = """
        SELECT ASSESSMENT_ID, CONFIGURATION_SNAPSHOT_ID, WORK_STATE, LEASE_TOKEN,
               LEASE_EPOCH, ATTEMPT_COUNT, ROW_VERSION, LEASE_EXPIRES_AT, CREATED_AT,
               EVIDENCE_AS_OF, LAST_ERROR_CODE, LAST_ERROR_MESSAGE
        FROM D2_RISK.RISK_ASSESSMENTS WHERE ASSESSMENT_ID = ?
        """;
    static final String LOCK = READ + " FOR UPDATE";
    static final String UPDATE = """
        UPDATE D2_RISK.RISK_ASSESSMENTS
        SET WORK_STATE=?, LEASE_TOKEN=?, LEASE_EPOCH=?, ATTEMPT_COUNT=?, ROW_VERSION=?,
            LEASE_EXPIRES_AT=?, LAST_ERROR_CODE=?, LAST_ERROR_MESSAGE=?
        WHERE ASSESSMENT_ID=? AND WORK_STATE=? AND LEASE_EPOCH=? AND ATTEMPT_COUNT=? AND ROW_VERSION=?
          AND ((? IS NULL AND LEASE_TOKEN IS NULL) OR LEASE_TOKEN=?)
          AND ((? IS NULL AND LEASE_EXPIRES_AT IS NULL) OR LEASE_EXPIRES_AT=?)
        """;
    static final String LIVE_UPDATE = UPDATE + " AND LEASE_EXPIRES_AT > SYSTIMESTAMP";
    static final String CLAIM_UPDATE = UPDATE + " AND ? > SYSTIMESTAMP";
    static final String TAKEOVER_UPDATE = CLAIM_UPDATE + " AND LEASE_EXPIRES_AT <= SYSTIMESTAMP";
    static final String DIAGNOSTIC = """
        INSERT INTO D2_RISK.RISK_INPUT_EVIDENCE
          (ASSESSMENT_ID, RECORD_CLASS, ATTEMPT_NO, EVIDENCE_KEY, EVIDENCE_KIND, SOURCE_SERVICE,
           EVIDENCE_FORMAT_VERSION, HASH_FORMAT_VERSION, AS_OF_AT, EVIDENCE_JSON, CONTENT_HASH, CAPTURED_AT)
        VALUES (?, 'ATTEMPT_DIAGNOSTIC', ?, 'RESULT', 'ATTEMPT_RESULT', 'risk-assessment-service',
                'risk-work-diagnostic-v1', 'risk-canonical-v1', ?, ?, ?, ?)
        """;
    record Row(long id, long snapshot, String state, String token, long epoch, int attempt, long revision,
               Instant expiry, Instant created, Instant cutoff, String errorCode, String errorMessage) {
        WorkLease lease() { return new WorkLease(Long.toString(id), token, epoch, attempt, Long.toString(revision), expiry); }
    }
    private final JdbcTemplate jdbc;
    public RiskWorkRepository(JdbcTemplate jdbc) { this.jdbc = Objects.requireNonNull(jdbc); }
    Row lock(long id) { writable(); return read(LOCK, id); }
    Row read(long id) {
        if(!TransactionSynchronizationManager.isActualTransactionActive() || !TransactionSynchronizationManager.hasResource(jdbc.getDataSource()))
            throw new IllegalStateException("Work reads require their bound transaction");
        return read(READ,id);
    }
    private Row read(String sql, long id) {
        var rows = jdbc.query(sql, ps -> ps.setLong(1, id), (rs, n) -> {
            long attempt = number(rs, "ATTEMPT_COUNT", false);
            if (attempt > Integer.MAX_VALUE) throw invalid();
            var row = new Row(number(rs,"ASSESSMENT_ID",true), number(rs,"CONFIGURATION_SNAPSHOT_ID",true),
                rs.getString("WORK_STATE"), rs.getString("LEASE_TOKEN"), number(rs,"LEASE_EPOCH",false),
                (int)attempt, number(rs,"ROW_VERSION",false), time(rs,"LEASE_EXPIRES_AT",false),
                time(rs,"CREATED_AT",true), time(rs,"EVIDENCE_AS_OF",true),
                rs.getString("LAST_ERROR_CODE"), rs.getString("LAST_ERROR_MESSAGE"));
            validate(row); return row;
        });
        if (rows.isEmpty()) throw new WorkFailure(WorkFailure.Code.WORK_NOT_FOUND);
        if (rows.size() != 1 || rows.getFirst().id != id) throw invalid();
        return rows.getFirst();
    }
    void update(Row before, Row after, boolean currentWorker) {
        writable();
        String sql = currentWorker ? LIVE_UPDATE : before.state.equals("IN_PROGRESS") ? TAKEOVER_UPDATE : CLAIM_UPDATE;
        int count = jdbc.update(sql, ps -> {
            ps.setString(1,after.state); text(ps,2,after.token); ps.setLong(3,after.epoch);
            ps.setInt(4,after.attempt); ps.setLong(5,after.revision); time(ps,6,after.expiry);
            text(ps,7,after.errorCode); text(ps,8,after.errorMessage);
            ps.setLong(9,before.id); ps.setString(10,before.state); ps.setLong(11,before.epoch);
            ps.setInt(12,before.attempt); ps.setLong(13,before.revision);
            text(ps,14,before.token); text(ps,15,before.token); time(ps,16,before.expiry); time(ps,17,before.expiry);
            if(!currentWorker) time(ps,18,after.expiry);
        });
        if (count != 1) throw new WorkFailure(WorkFailure.Code.LEASE_REJECTED);
    }
    void diagnostic(Row row, WorkDiagnostic diagnostic, Instant now) {
        writable();
        // Fixed fields only; stable enum strings require no ad-hoc escaping and contain no supplied payloads.
        String json = "{\"attempt\":" + row.attempt + ",\"code\":\"" + diagnostic.name()
            + "\",\"epoch\":\"" + row.epoch + "\",\"state\":\"" + diagnostic.state() + "\"}";
        int count = jdbc.update(DIAGNOSTIC, ps -> {
            ps.setLong(1,row.id); ps.setInt(2,row.attempt); time(ps,3,row.cutoff);
            // No generated-key RETURNING here; a character stream is safe for this bounded diagnostic.
            ps.setCharacterStream(4,new StringReader(json),json.length());
            ps.setString(5,CanonicalAcceptance.hash(json)); time(ps,6,now);
        });
        if (count != 1) throw invalid();
    }
    private void writable() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
            || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
            || !TransactionSynchronizationManager.hasResource(jdbc.getDataSource()))
            throw new IllegalStateException("Work writes require a bound writable transaction");
    }
    private static void validate(Row r) {
        if (!Set.of("ACCEPTED","IN_PROGRESS","RETRYABLE_FAILURE","BLOCKED","COMPLETED").contains(r.state)
            || r.cutoff.isAfter(r.created)) throw invalid();
        if (r.state.equals("IN_PROGRESS")) {
            if (r.token == null || r.token.isBlank() || r.token.length()>64 || r.expiry == null
                || r.expiry.isBefore(r.created) || r.epoch==0 || r.attempt==0) throw invalid();
        } else if (r.token!=null || r.expiry!=null) throw invalid();
        boolean failed = r.state.equals("RETRYABLE_FAILURE") || r.state.equals("BLOCKED");
        if (failed ? r.errorCode == null || r.errorCode.isBlank() : r.errorCode!=null || r.errorMessage!=null) throw invalid();
    }
    private static long number(ResultSet rs,String name,boolean positive) throws SQLException {
        return PersistenceValues.number(rs.getObject(name),positive);
    }
    private static Instant time(ResultSet rs,String name,boolean required) throws SQLException {
        var value=rs.getObject(name,OffsetDateTime.class);
        if (value==null) { if(required) throw invalid(); return null; }
        var instant=value.toInstant(); PersistenceValues.timestamp(instant); return instant;
    }
    private static void time(PreparedStatement ps,int i,Instant value) throws SQLException {
        if(value==null) ps.setNull(i,Types.TIMESTAMP_WITH_TIMEZONE);
        else ps.setObject(i,value.atOffset(ZoneOffset.UTC),Types.TIMESTAMP_WITH_TIMEZONE);
    }
    private static void text(PreparedStatement ps,int i,String value) throws SQLException {
        if(value==null) ps.setNull(i,Types.VARCHAR); else ps.setString(i,value);
    }
    private static DataRetrievalFailureException invalid() { return new DataRetrievalFailureException("Invalid persisted work"); }
}
