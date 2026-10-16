package com.ofss.risk.persistence;

import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in committed fixture WRITES; real repositories, Oracle clock and Spring transactions. No DDL/host. */
@EnabledIfEnvironmentVariable(named="RISK_VERIFY_ORACLE_WORK_WRITES",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OracleWorkPersistenceTest {
    final OracleWorkFixtures fixtures=new OracleWorkFixtures();
    final OracleAcceptanceDiagnostics diagnostics=new OracleAcceptanceDiagnostics();
    JdbcTemplate jdbc; JdbcTransactionManager manager; RiskWorkService work;
    boolean cleanupAllowed;
    static final Duration DURATION=Duration.ofMinutes(2); // Technical fixture setting only, not a business SLA.
    static final Set<String> MUTABLE=Set.of("WORK_STATE","LEASE_TOKEN","LEASE_EPOCH","ATTEMPT_COUNT","ROW_VERSION",
        "LEASE_EXPIRES_AT","LAST_ERROR_CODE","LAST_ERROR_MESSAGE");
    static final String EXPIRE="""
        UPDATE D2_RISK.RISK_ASSESSMENTS SET LEASE_EXPIRES_AT=CREATED_AT, ROW_VERSION=ROW_VERSION+1
        WHERE ASSESSMENT_ID=? AND SUBMISSION_SOURCE=? AND SUBMISSION_KEY=?
          AND EVALUATOR_VERSION=? AND VALIDATION_REFERENCE=? AND WORK_STATE='IN_PROGRESS'
          AND LEASE_TOKEN=? AND LEASE_EPOCH=? AND ATTEMPT_COUNT=? AND ROW_VERSION=? AND LEASE_EXPIRES_AT=?
        """;
    @BeforeAll void connect() {
        System.out.println("Synthetic Oracle work namespace (retain for cleanup recovery): "+fixtures.namespace);
        var ds=OracleAcceptanceConnection.externalDataSource(); jdbc=diagnostics.jdbc(ds); manager=new JdbcTransactionManager(ds);
        assertEquals(0L,countNamespace()); cleanupAllowed=true;
        work=service(new RiskWorkRepository(jdbc));
    }
    RiskWorkService service(RiskWorkRepository repository) {
        var observed=new RiskWorkRepository(jdbc) {
            @Override Row lock(long id) {
                try { return repository.lock(id); } catch(RuntimeException e) { diagnostics.record("WORK_LOCK",e); throw e; }
            }
            @Override Row read(long id) {
                try { return repository.read(id); } catch(RuntimeException e) { diagnostics.record("WORK_READBACK",e); throw e; }
            }
            @Override void update(Row before,Row after,boolean currentWorker) {
                try { repository.update(before,after,currentWorker); } catch(RuntimeException e) { diagnostics.record("WORK_UPDATE",e); throw e; }
            }
            @Override void diagnostic(Row row,WorkDiagnostic reason,Instant now) {
                try { repository.diagnostic(row,reason,now); } catch(RuntimeException e) { diagnostics.record("WORK_DIAGNOSTIC",e); throw e; }
            }
        };
        return new RiskWorkService(observed,manager,new OracleLeaseClock(jdbc),DURATION);
    }
    AcceptedAssessment accept(String key) {
        // Acceptance time is safely before Oracle time, never based on the workstation clock.
        Instant created=jdbc.queryForObject(OracleLeaseClock.SQL,(rs,n)->rs.getObject("LEASE_NOW",OffsetDateTime.class).toInstant(),new Object[0]).minusSeconds(5);
        var localAcceptance=new RiskAcceptanceService(new RiskAcceptanceRepository(jdbc),new CanonicalAcceptance(),manager,Clock.fixed(created,ZoneOffset.UTC));
        return localAcceptance.accept(fixtures.request(key,created));
    }
    @AfterAll void cleanup() {
        diagnostics.print();
        if(!cleanupAllowed) { System.out.println("Work fixture cleanup deferred; namespace: "+fixtures.namespace); return; }
        try {
            new OracleWorkFixtureCleaner(jdbc,manager).clean(fixtures.namespace);
            assertEquals(0L,countNamespace());
            System.out.println("Work fixture cleanup completed; remaining parents=0; namespace: "+fixtures.namespace);
        } catch(RuntimeException e) {
            diagnostics.record("WORK_CLEANUP",e); diagnostics.print();
            throw new AssertionError("Work cleanup failed/refused. Retain exact namespace: "+fixtures.namespace);
        }
    }
    long countNamespace() { return jdbc.queryForObject("SELECT COUNT(*) FROM D2_RISK.RISK_ASSESSMENTS WHERE SUBMISSION_SOURCE=?",Long.class,fixtures.namespace); }
    long id(AcceptedAssessment assessment) { return Long.parseLong(assessment.assessmentId()); }
    RiskWorkRepository.Row row(long id) {
        return new TransactionTemplate(manager).execute(status -> new RiskWorkRepository(jdbc).read(id));
    }
    Map<String,Object> parent(long id) {
        return jdbc.queryForObject("SELECT * FROM D2_RISK.RISK_ASSESSMENTS WHERE ASSESSMENT_ID=?",(rs,n)->values(rs),id);
    }
    Map<String,Object> snapshot(long id) {
        return jdbc.queryForObject("SELECT * FROM D2_RISK.RISK_CONFIGURATION_SNAPSHOTS WHERE ASSESSMENT_ID=?",(rs,n)->values(rs),id);
    }
    List<Map<String,Object>> diagnosticRows(long id) {
        return jdbc.query("SELECT * FROM D2_RISK.RISK_INPUT_EVIDENCE WHERE ASSESSMENT_ID=? ORDER BY ATTEMPT_NO, EVIDENCE_ID",(rs,n)->values(rs),id);
    }
    static Map<String,Object> values(ResultSet rs) throws SQLException {
        var result=new TreeMap<String,Object>(); var meta=rs.getMetaData();
        for(int i=1;i<=meta.getColumnCount();i++) {
            Object value; String type=meta.getColumnTypeName(i).toUpperCase(Locale.ROOT);
            if(type.equals("CLOB")) value=rs.getString(i);
            else if(type.startsWith("TIMESTAMP")) {
                var time=rs.getObject(i,OffsetDateTime.class); value=time==null?null:time.toInstant();
            } else if(type.equals("NUMBER")) value=rs.getBigDecimal(i);
            else value=rs.getString(i);
            result.put(meta.getColumnLabel(i),value);
        }
        return result;
    }
    Map<String,Object> immutable(long id) { var fields=new TreeMap<>(parent(id)); MUTABLE.forEach(fields::remove); return fields; }
    void unscored(long id) {
        var row=parent(id);
        for(String field:List.of("TOTAL_RAW_SCORE","FINAL_SCORE","RISK_BAND","FINAL_FACTOR_COUNT","EVALUATED_AT","COMPLETED_AT"))
            assertNull(row.get(field),field);
    }
    void assertRejected(WorkFailure.Code code,Runnable operation) {
        var e=assertThrows(WorkFailure.class,operation::run); assertEquals(code,e.code()); assertNull(e.getCause());
    }
    WorkLease expire(AcceptedAssessment assessment,WorkLease lease,String key) {
        return new TransactionTemplate(manager).execute(status -> {
            var repository=new RiskWorkRepository(jdbc); repository.lock(id(assessment));
            assertEquals(1,jdbc.update(EXPIRE,id(assessment),fixtures.namespace,key,OracleWorkFixtures.EVALUATOR,
                "synthetic-work-pass/"+key,lease.token(),lease.epoch(),lease.attempt(),Long.parseLong(lease.rowVersion()),
                lease.expiresAt().atOffset(ZoneOffset.UTC)),"Only the exact current synthetic lease may be expired");
            var expired=repository.read(id(assessment));
            assertFalse(expired.expiry().isAfter(new OracleLeaseClock(jdbc).instant())); return expired.lease();
        });
    }
    @Test void competingClaimsProduceOneCurrentLeaseAndOneConflict() {
        var accepted=accept("competing"); var before=immutable(id(accepted)); var snap=snapshot(id(accepted));
        var barrier=new CyclicBarrier(2);
        var repository=new RiskWorkRepository(jdbc) {
            @Override Row lock(long id) {
                try { barrier.await(10,TimeUnit.SECONDS); }
                catch(Exception e) { throw new IllegalStateException("Fixture claim barrier failed"); }
                return super.lock(id);
            }
        };
        var concurrent=service(repository);
        var outcomes=OracleAcceptanceWorkers.pair(() -> {
            try { return (Object)concurrent.claim(accepted.assessmentId(),accepted.rowVersion()); }
            catch(WorkFailure e) { return e.code(); }
        },30000,()->cleanupAllowed=false,event->System.out.println("Work claim lifecycle: "+event));
        assertEquals(1,outcomes.stream().filter(WorkLease.class::isInstance).count());
        assertEquals(1,outcomes.stream().filter(o -> o==WorkFailure.Code.WORK_CONFLICT).count());
        var winner=(WorkLease)outcomes.stream().filter(WorkLease.class::isInstance).findFirst().orElseThrow();
        assertEquals(winner,row(id(accepted)).lease()); assertEquals(1,winner.epoch()); assertEquals(1,winner.attempt());
        assertEquals("1",winner.rowVersion()); assertEquals(before,immutable(id(accepted))); assertEquals(snap,snapshot(id(accepted)));
        assertTrue(diagnosticRows(id(accepted)).isEmpty()); unscored(id(accepted));
    }
    @Test void renewalRejectsOldFenceAndCannotReviveExpiredLease() {
        var accepted=accept("renewal"); var lease=work.claim(accepted.assessmentId(),accepted.rowVersion());
        var before=immutable(id(accepted)); var renewed=work.renew(lease);
        assertEquals(lease.token(),renewed.token()); assertEquals(lease.epoch(),renewed.epoch()); assertEquals(lease.attempt(),renewed.attempt());
        assertEquals("2",renewed.rowVersion()); assertFalse(renewed.expiresAt().isBefore(lease.expiresAt()));
        var current=parent(id(accepted));
        assertRejected(WorkFailure.Code.LEASE_REJECTED,()->work.renew(lease));
        assertRejected(WorkFailure.Code.LEASE_REJECTED,()->work.fail(lease,WorkDiagnostic.TRANSIENT_FAILURE));
        assertTrue(current.equals(parent(id(accepted))),"Rejected old fence must leave every column unchanged");
        var expired=expire(accepted,renewed,"renewal"); current=parent(id(accepted));
        assertRejected(WorkFailure.Code.LEASE_REJECTED,()->work.renew(expired));
        assertRejected(WorkFailure.Code.LEASE_REJECTED,()->work.fail(expired,WorkDiagnostic.INTERVENTION_REQUIRED));
        assertTrue(current.equals(parent(id(accepted))),"Expired worker must leave every column unchanged"); assertTrue(diagnosticRows(id(accepted)).isEmpty());
        assertEquals(before,immutable(id(accepted))); unscored(id(accepted));
    }
    @Test void expiredTakeoverChangesOnlyWorkFieldsAndRejectsSupersededWorker() {
        var accepted=accept("takeover"); var before=immutable(id(accepted)); var snap=snapshot(id(accepted));
        var old=work.claim(accepted.assessmentId(),accepted.rowVersion()); var expired=expire(accepted,old,"takeover");
        var replacement=work.claim(accepted.assessmentId(),expired.rowVersion());
        assertNotEquals(old.token(),replacement.token()); assertEquals(2,replacement.epoch()); assertEquals(2,replacement.attempt());
        assertEquals("3",replacement.rowVersion()); var state=parent(id(accepted));
        for(var obsolete:List.of(old,expired)) {
            assertRejected(WorkFailure.Code.LEASE_REJECTED,()->work.renew(obsolete));
            assertRejected(WorkFailure.Code.LEASE_REJECTED,()->work.fail(obsolete,WorkDiagnostic.TRANSIENT_FAILURE));
        }
        assertTrue(state.equals(parent(id(accepted))),"Superseded worker must leave every column unchanged"); assertEquals(before,immutable(id(accepted))); assertEquals(snap,snapshot(id(accepted)));
        assertTrue(diagnosticRows(id(accepted)).isEmpty()); unscored(id(accepted));
    }
    @Test void retryableThenBlockedPreservesOriginalImmutableDiagnostics() {
        var accepted=accept("outcomes"); var before=immutable(id(accepted)); var snap=snapshot(id(accepted));
        var first=work.claim(accepted.assessmentId(),accepted.rowVersion());
        String revision=work.fail(first,WorkDiagnostic.TRANSIENT_FAILURE);
        var one=diagnosticRows(id(accepted)); assertEquals(1,one.size()); verifyDiagnostic(one.getFirst(),first,WorkDiagnostic.TRANSIENT_FAILURE);
        assertEquals("RETRYABLE_FAILURE",row(id(accepted)).state()); assertNull(row(id(accepted)).token()); assertNull(row(id(accepted)).expiry());
        assertRejected(WorkFailure.Code.LEASE_REJECTED,()->work.fail(first,WorkDiagnostic.TRANSIENT_FAILURE));
        assertEquals(one,diagnosticRows(id(accepted)));
        var second=work.claim(accepted.assessmentId(),revision); assertEquals(2,second.attempt()); assertEquals(2,second.epoch());
        var active=row(id(accepted)); assertNull(active.errorCode()); assertNull(active.errorMessage());
        String blockedRevision=work.fail(second,WorkDiagnostic.INTERVENTION_REQUIRED);
        var two=diagnosticRows(id(accepted)); assertEquals(2,two.size()); assertEquals(one.getFirst(),two.getFirst());
        verifyDiagnostic(two.get(1),second,WorkDiagnostic.INTERVENTION_REQUIRED);
        var blocked=parent(id(accepted)); assertEquals("BLOCKED",row(id(accepted)).state());
        assertRejected(WorkFailure.Code.WORK_CONFLICT,()->work.claim(accepted.assessmentId(),blockedRevision));
        assertRejected(WorkFailure.Code.LEASE_REJECTED,()->work.renew(second));
        assertRejected(WorkFailure.Code.LEASE_REJECTED,()->work.fail(second,WorkDiagnostic.TRANSIENT_FAILURE));
        assertTrue(blocked.equals(parent(id(accepted))),"Blocked work must remain unchanged"); assertEquals(two,diagnosticRows(id(accepted)));
        assertEquals(before,immutable(id(accepted))); assertEquals(snap,snapshot(id(accepted))); unscored(id(accepted));
    }
    void verifyDiagnostic(Map<String,Object> diagnostic,WorkLease lease,WorkDiagnostic code) {
        assertEquals("ATTEMPT_DIAGNOSTIC",diagnostic.get("RECORD_CLASS")); assertEquals("RESULT",diagnostic.get("EVIDENCE_KEY"));
        assertEquals("ATTEMPT_RESULT",diagnostic.get("EVIDENCE_KIND")); assertNull(diagnostic.get("AVAILABILITY")); assertNull(diagnostic.get("RECORD_COUNT"));
        assertEquals(lease.attempt(),PersistenceValues.number(diagnostic.get("ATTEMPT_NO"),true));
        String json=(String)diagnostic.get("EVIDENCE_JSON"); var body=CanonicalAcceptance.read(json);
        assertEquals(Set.of("attempt","code","epoch","state"),body.keySet());
        assertEquals(code.name(),body.get("code")); assertEquals(code.state(),body.get("state")); assertEquals(Long.toString(lease.epoch()),body.get("epoch"));
        assertEquals(lease.attempt(),PersistenceValues.number(body.get("attempt"),true));
        assertEquals(CanonicalAcceptance.hash(json),diagnostic.get("CONTENT_HASH")); assertFalse(json.contains(lease.token()));
    }
    @ParameterizedTest @ValueSource(ints={1,2,3})
    void injectedFailuresReallyReachTargetAndRollBackAllChanges(int stage) {
        String key=List.of("rollback-claim","rollback-diagnostic","rollback-parent").get(stage-1);
        var accepted=accept(key); WorkLease lease=stage==1?null:work.claim(accepted.assessmentId(),accepted.rowVersion());
        var before=parent(id(accepted)); var snap=snapshot(id(accepted)); var injected=new AtomicBoolean();
        var repository=new RiskWorkRepository(jdbc) {
            @Override void diagnostic(Row row,WorkDiagnostic reason,Instant now) {
                super.diagnostic(row,reason,now);
                if(stage==2) { assertEquals(1,diagnosticRows(row.id()).size()); throwInjected(); }
            }
            @Override Row read(long id) {
                var saved=super.read(id);
                if(stage!=2) {
                    assertEquals(stage==1?"IN_PROGRESS":"RETRYABLE_FAILURE",saved.state());
                    if(stage==3) assertEquals(1,diagnosticRows(id).size());
                    throwInjected();
                }
                return saved;
            }
            void throwInjected() { assertTrue(TransactionSynchronizationManager.isActualTransactionActive()); injected.set(true); throw new IllegalStateException("Synthetic injected work failure"); }
        };
        var failing=service(repository);
        assertRejected(WorkFailure.Code.WORK_PERSISTENCE_UNAVAILABLE,()-> {
            if(stage==1) failing.claim(accepted.assessmentId(),accepted.rowVersion()); else failing.fail(lease,WorkDiagnostic.TRANSIENT_FAILURE);
        });
        assertTrue(injected.get(),"The intended post-write injection must be reached, not an earlier SQL failure");
        assertTrue(before.equals(parent(id(accepted))),"Rollback must restore every parent column"); assertEquals(snap,snapshot(id(accepted))); assertTrue(diagnosticRows(id(accepted)).isEmpty());
    }
    @Test void finalFenceRejectsChangedExpiryAndRollsBackDiagnosticAndFixtureExpiryTogether() {
        var accepted=accept("rollback-expiry"); var lease=work.claim(accepted.assessmentId(),accepted.rowVersion());
        var before=parent(id(accepted)); var injected=new AtomicBoolean();
        var repository=new RiskWorkRepository(jdbc) {
            @Override void diagnostic(Row row,WorkDiagnostic reason,Instant now) {
                super.diagnostic(row,reason,now);
                assertEquals(1,diagnosticRows(row.id()).size());
                expire(accepted,lease,"rollback-expiry"); // Joins this transaction; no independent fixture commit.
                injected.set(true);
            }
        };
        assertRejected(WorkFailure.Code.LEASE_REJECTED,()->service(repository).fail(lease,WorkDiagnostic.TRANSIENT_FAILURE));
        assertTrue(injected.get()); assertTrue(before.equals(parent(id(accepted))),"Expiry injection and failure writes must roll back"); assertTrue(diagnosticRows(id(accepted)).isEmpty());
    }
}
