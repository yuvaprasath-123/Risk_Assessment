package com.ofss.risk.persistence;

import com.ofss.risk.contract.RuleCode;
import com.ofss.risk.engine.*;
import java.math.BigInteger;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in committed Oracle fixture WRITES. Real repositories/transactions/engine; no host/DDL. */
@EnabledIfEnvironmentVariable(named="RISK_VERIFY_ORACLE_FINALIZATION_WRITES",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OracleFinalizationPersistenceTest {
    final OracleFinalizationFixtures fixtures=new OracleFinalizationFixtures();
    final OracleAcceptanceDiagnostics diagnostics=new OracleAcceptanceDiagnostics();
    JdbcTemplate jdbc; JdbcTransactionManager manager; RiskWorkService work; boolean cleanupAllowed;
    record Case(AcceptanceRequest acceptance,AcceptedAssessment accepted,FrozenInput frozen) {
        long id() { return Long.parseLong(accepted.assessmentId()); }
        FinalizationRequest request() { return request(frozen.lease()); }
        FinalizationRequest request(WorkLease lease) { return new FinalizationRequest(acceptance.identity(),lease,accepted.acceptanceHash(),accepted.configurationHash(),frozen.inputHash()); }
    }
    @BeforeAll void connect() {
        System.out.println("Synthetic Oracle finalization namespace (retain for cleanup recovery): "+fixtures.namespace);
        var source=OracleAcceptanceConnection.externalDataSource(); jdbc=diagnostics.jdbc(source); manager=new JdbcTransactionManager(source);
        assertEquals(0L,count()); cleanupAllowed=true;
        work=new RiskWorkService(new RiskWorkRepository(jdbc),manager,new OracleLeaseClock(jdbc),Duration.ofMinutes(2));
    }
    @AfterAll void cleanup() {
        diagnostics.print();
        if(!cleanupAllowed) { System.out.println("Finalization fixture cleanup deferred; namespace: "+fixtures.namespace); return; }
        try {
            new OracleFinalizationFixtureCleaner(jdbc,manager).clean(fixtures.namespace); assertEquals(0L,count());
            System.out.println("Finalization fixture cleanup completed; remaining parents=0; namespace: "+fixtures.namespace);
        } catch(RuntimeException failure) {
            diagnostics.record("FINALIZATION_CLEANUP",failure); diagnostics.print();
            throw new AssertionError("Finalization cleanup failed/refused; retain exact namespace: "+fixtures.namespace);
        }
    }
    long count() { return jdbc.queryForObject("SELECT COUNT(*) FROM D2_RISK.RISK_ASSESSMENTS WHERE SUBMISSION_SOURCE=?",Long.class,fixtures.namespace); }
    Case prepare(String key) {
        Instant oracle=jdbc.queryForObject(OracleLeaseClock.SQL,(rs,n)->rs.getObject("LEASE_NOW",OffsetDateTime.class).toInstant(),new Object[0]);
        Instant created=oracle.minusSeconds(5).truncatedTo(ChronoUnit.SECONDS).plusNanos(123456890);
        var request=fixtures.acceptance(key,created);
        var accepted=new RiskAcceptanceService(new RiskAcceptanceRepository(jdbc),new CanonicalAcceptance(),manager,Clock.fixed(created,ZoneOffset.UTC)).accept(request);
        var lease=work.claim(accepted.assessmentId(),accepted.rowVersion());
        var frozen=new RiskEvidenceService(new RiskWorkRepository(jdbc),new RiskAcceptanceRepository(jdbc),new RiskEvidenceRepository(jdbc),manager,new OracleLeaseClock(jdbc))
            .freeze(fixtures.evidence(lease,request));
        return new Case(request,accepted,frozen);
    }
    RiskFinalizationService service() { return service(new RiskFinalizationRepository(jdbc),PinnedRiskEvaluators.current(),new OracleLeaseClock(jdbc)); }
    RiskFinalizationService service(RiskFinalizationRepository delegate,PinnedRiskEvaluators registry,Clock clock) {
        var observed=new RiskFinalizationRepository(jdbc) {
            @Override void beginSnapshot() { observe("FINAL_SNAPSHOT",()->{delegate.beginSnapshot(); return null;}); }
            @Override boolean hasFactors(long id) { return observe("FINAL_FACTOR_CHECK",()->delegate.hasFactors(id)); }
            @Override void insert(FrozenAssessmentLoader.Snapshot s,RiskResult.Factor f,String receipt,Instant evaluated) {
                observe("FINAL_FACTOR_INSERT",()->{delegate.insert(s,f,receipt,evaluated); return null;});
            }
            @Override void complete(FrozenAssessmentLoader.Snapshot s,RiskResult result,Instant evaluated,Instant completed) {
                observe("FINAL_COMPLETE",()->{delegate.complete(s,result,evaluated,completed); return null;});
            }
            @Override CompletedAssessment read(FrozenAssessmentLoader.Snapshot s,FinalizationRequest request) {
                return observe("FINAL_READBACK",()->delegate.read(s,request));
            }
        };
        var accepted=new RiskAcceptanceRepository(jdbc); var evidence=new RiskEvidenceRepository(jdbc);
        var workRepository=new RiskWorkRepository(jdbc) {
            @Override Row lock(long id) { return observe("FINAL_PARENT_LOCK",()->super.lock(id)); }
            @Override Row read(long id) { return observe("FINAL_PARENT_READ",()->super.read(id)); }
        };
        var loader=new FrozenAssessmentLoader(accepted,workRepository,evidence) {
            @Override Snapshot load(FinalizationRequest request) { return observe("FINAL_RECONSTRUCT",()->super.load(request)); }
        };
        return new RiskFinalizationService(loader,workRepository,observed,registry,manager,clock);
    }
    <T> T observe(String phase,Supplier<T> operation) {
        try { return operation.get(); } catch(RuntimeException failure) { diagnostics.record(phase,failure); throw failure; }
    }
    Map<String,Object> parent(Case f) { return jdbc.queryForObject("SELECT * FROM D2_RISK.RISK_ASSESSMENTS WHERE ASSESSMENT_ID=?",(rs,n)->OracleWorkPersistenceTest.values(rs),f.id()); }
    List<Map<String,Object>> factors(Case f) { return jdbc.query(RiskFinalizationRepository.FACTORS,(rs,n)->OracleWorkPersistenceTest.values(rs),f.id()); }
    Map<String,Object> state(Case f) {
        return Map.of("parent",parent(f),"snapshot",jdbc.query("SELECT * FROM D2_RISK.RISK_CONFIGURATION_SNAPSHOTS WHERE ASSESSMENT_ID=?",(rs,n)->OracleWorkPersistenceTest.values(rs),f.id()),
            "evidence",jdbc.query("SELECT * FROM D2_RISK.RISK_INPUT_EVIDENCE WHERE ASSESSMENT_ID=? ORDER BY EVIDENCE_ID",(rs,n)->OracleWorkPersistenceTest.values(rs),f.id()),"factors",factors(f));
    }
    Map<String,Object> immutable(Case f) {
        var fields=new TreeMap<>(parent(f)); OracleWorkPersistenceTest.MUTABLE.forEach(fields::remove);
        for(String key:List.of("TOTAL_RAW_SCORE","FINAL_SCORE","RISK_BAND","FINAL_FACTOR_COUNT","EVALUATED_AT","COMPLETED_AT")) fields.remove(key);
        return Map.of("parent",fields,"snapshot",state(f).get("snapshot"),"evidence",state(f).get("evidence"));
    }
    void unchanged(Case f,Map<String,Object> before) { assertTrue(before.equals(state(f)),"Every stored parent, factor, input and snapshot field must remain unchanged"); }
    void noCompletion(Case f) {
        for(String key:List.of("TOTAL_RAW_SCORE","FINAL_SCORE","RISK_BAND","FINAL_FACTOR_COUNT","EVALUATED_AT","COMPLETED_AT")) assertNull(parent(f).get(key),key);
        assertTrue(factors(f).isEmpty()); assertEquals("IN_PROGRESS",parent(f).get("WORK_STATE"));
    }
    @Test void sevenFactorsLargeUnicodeClobsExactInputAndCompletionRoundTrip() {
        var f=prepare("success"); var before=immutable(f); var result=service().finalizeAssessment(f.request());
        assertEquals(BigInteger.valueOf(135),result.result().rawTotal()); assertEquals(100,result.result().finalScore()); assertEquals("HIGH",result.result().riskBand());
        assertEquals(7,result.result().factors().size()); assertTrue(result.result().factors().stream().allMatch(v->v.outcome()==RiskResult.Outcome.TRIGGERED));
        assertEquals(f.frozen.input(),result.result().inputSnapshot()); assertEquals(123456789,result.result().cutoff().getNano());
        assertTrue(new CanonicalAcceptance().configuration(f.acceptance.configuration()).equals(new CanonicalAcceptance().configuration(result.result().configurationSnapshot())));
        assertTrue(before.equals(immutable(f))); assertEquals(Long.parseLong(f.frozen.lease().rowVersion())+1,Long.parseLong(result.rowVersion()));
        var rows=factors(f); assertEquals(7,rows.size()); assertTrue(rows.stream().anyMatch(v->((String)v.get("DETAILS_JSON")).length()>32767));
        assertTrue(rows.stream().anyMatch(v->((String)v.get("DETAILS_JSON")).contains("ಕನ್ನಡ")));
        for(var row:rows) { assertEquals(result.evaluatedAt(),row.get("EVALUATED_AT")); assertEquals(Long.parseLong(f.frozen.manifestId()),PersistenceValues.number(row.get("PRIMARY_EVIDENCE_ID"),true)); }
        assertEquals(result.evaluatedAt(),parent(f).get("EVALUATED_AT")); assertEquals(result.completedAt(),parent(f).get("COMPLETED_AT"));
        assertEquals("COMPLETED",parent(f).get("WORK_STATE")); assertNull(parent(f).get("LEASE_TOKEN")); assertNull(parent(f).get("LEASE_EXPIRES_AT"));
        assertEquals(result,service().finalizeAssessment(f.request()));
    }
    @Test void insufficientHistoryCompletesWithApprovedZeroContribution() {
        var f=prepare("insufficient"); var result=service().finalizeAssessment(f.request());
        assertEquals(BigInteger.ZERO,result.result().rawTotal()); assertEquals(0,result.result().finalScore()); assertEquals("LOW",result.result().riskBand());
        var amount=result.result().factors().stream().filter(v->v.ruleCode()==RuleCode.UNUSUAL_AMOUNT).findFirst().orElseThrow();
        assertEquals(RiskResult.Outcome.INSUFFICIENT_HISTORY,amount.outcome()); assertEquals(20,amount.configuredWeight()); assertEquals(0,amount.contribution()); assertEquals(7,factors(f).size());
    }
    @Test void exactNineDigitTimestampsBindAndReadBackInParentAndEveryFactor() {
        var f=prepare("nanoseconds");
        // Test-only Oracle-derived sample with all nine fractional digits; normal production uses OracleLeaseClock.
        Instant exact=f.frozen.frozenAt().truncatedTo(ChronoUnit.SECONDS).plusNanos(123456789);
        if(exact.isBefore(f.frozen.frozenAt())) exact=exact.plusSeconds(1);
        var saved=service(new RiskFinalizationRepository(jdbc),PinnedRiskEvaluators.current(),Clock.fixed(exact,ZoneOffset.UTC)).finalizeAssessment(f.request());
        assertEquals(exact,saved.evaluatedAt()); assertEquals(exact,saved.completedAt());
        assertEquals(exact,parent(f).get("EVALUATED_AT")); assertEquals(exact,parent(f).get("COMPLETED_AT"));
        for(var row:factors(f)) assertEquals(exact,row.get("EVALUATED_AT"));
        assertEquals(saved,service().finalizeAssessment(f.request()));
    }
    @Test void rawTotalBeyondIntegerRangeAndMaximumFactorWeightsRemainExact() {
        var f=prepare("maximum-score"); var saved=service().finalizeAssessment(f.request());
        assertEquals(new BigInteger("15032385529"),saved.result().rawTotal()); assertEquals(100,saved.result().finalScore());
        assertEquals(15032385529L,PersistenceValues.number(parent(f).get("TOTAL_RAW_SCORE"),false));
        for(var row:factors(f)) { assertEquals(Integer.MAX_VALUE,PersistenceValues.number(row.get("CONFIGURED_WEIGHT"),false)); assertEquals(Integer.MAX_VALUE,PersistenceValues.number(row.get("SCORE_CONTRIBUTION"),false)); }
    }
    @Test void completedRetryDoesNotRescoreOrChangeAnyColumnOrTimestamp() {
        var f=prepare("retries"); var completed=service().finalizeAssessment(f.request()); var before=state(f); var calls=new AtomicInteger();
        var unavailable=new PinnedRiskEvaluators(Map.of(PinnedRiskEvaluators.CURRENT,(config,input)->{calls.incrementAndGet(); throw new AssertionError("Completed retry must not evaluate");}));
        var retry=service(new RiskFinalizationRepository(jdbc),unavailable,new OracleLeaseClock(jdbc)).finalizeAssessment(f.request());
        assertEquals(completed,retry); assertEquals(0,calls.get()); unchanged(f,before);
        assertEquals(completed,service(new RiskFinalizationRepository(jdbc),new PinnedRiskEvaluators(Map.of()),new OracleLeaseClock(jdbc)).finalizeAssessment(f.request())); unchanged(f,before);
    }
    @Test void competingFinalizersReturnOneStoredResultAndBothWorkersTerminate() {
        var f=prepare("concurrent"); var barrier=new CyclicBarrier(2); var evaluations=new AtomicInteger();
        var registry=new PinnedRiskEvaluators(Map.of(PinnedRiskEvaluators.CURRENT,(config,input)->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); evaluations.incrementAndGet();
            try { barrier.await(15,TimeUnit.SECONDS); } catch(Exception e) { throw new IllegalStateException("Synthetic finalization barrier failed"); }
            return new RiskScoringEngine().evaluate(config,input);
        }));
        var concurrent=service(new RiskFinalizationRepository(jdbc),registry,new OracleLeaseClock(jdbc));
        var results=OracleAcceptanceWorkers.pair(()->concurrent.finalizeAssessment(f.request()),45000,()->cleanupAllowed=false,event->System.out.println("Finalization worker lifecycle: "+event));
        assertEquals(2,evaluations.get()); assertEquals(results.getFirst(),results.getLast()); assertEquals(7,factors(f).size());
        assertEquals(Long.parseLong(f.frozen.lease().rowVersion())+1,PersistenceValues.number(parent(f).get("ROW_VERSION"),false));
    }
    static final String EXPIRE="""
        UPDATE D2_RISK.RISK_ASSESSMENTS SET LEASE_EXPIRES_AT=?,ROW_VERSION=ROW_VERSION+1
        WHERE ASSESSMENT_ID=? AND SUBMISSION_SOURCE=? AND SUBMISSION_KEY=? AND EVALUATOR_VERSION=? AND VALIDATION_REFERENCE=?
        AND WORK_STATE='IN_PROGRESS' AND LEASE_TOKEN=? AND LEASE_EPOCH=? AND ATTEMPT_COUNT=? AND ROW_VERSION=? AND LEASE_EXPIRES_AT=?
        """;
    WorkLease expire(Case f,Instant expiry) {
        return new TransactionTemplate(manager).execute(status->{
            var repository=new RiskWorkRepository(jdbc); var row=repository.lock(f.id()); var lease=row.lease();
            assertEquals(1,jdbc.update(EXPIRE,expiry.atOffset(ZoneOffset.UTC),f.id(),fixtures.namespace,f.acceptance.identity().key(),PinnedRiskEvaluators.CURRENT,
                f.acceptance.validationReference(),lease.token(),lease.epoch(),lease.attempt(),Long.parseLong(lease.rowVersion()),lease.expiresAt().atOffset(ZoneOffset.UTC)));
            var expired=repository.read(f.id()); assertFalse(new OracleLeaseClock(jdbc).instant().isBefore(expired.expiry())); return expired.lease();
        });
    }
    @Test void expiredAndTakenOverWorkersCannotCommitFactorsAndRecoveryReusesFrozenEvidence() {
        var f=prepare("fencing"); var fixed=immutable(f); var expired=expire(f,(Instant)parent(f).get("CREATED_AT")); var before=state(f);
        assertEquals(WorkFailure.Code.LEASE_REJECTED,assertThrows(WorkFailure.class,()->service().finalizeAssessment(f.request(expired))).code()); unchanged(f,before);
        var replacement=work.claim(f.accepted.assessmentId(),expired.rowVersion()); before=state(f);
        assertEquals(WorkFailure.Code.LEASE_REJECTED,assertThrows(WorkFailure.class,()->service().finalizeAssessment(f.request())).code()); unchanged(f,before);
        assertEquals(BigInteger.valueOf(135),service().finalizeAssessment(f.request(replacement)).result().rawTotal()); assertTrue(fixed.equals(immutable(f)));
    }
    @ParameterizedTest @ValueSource(strings={"factor","completed","readback"}) void injectedPostWriteFailureActuallyRollsBackAllFinalization(String stage) {
        var f=prepare("rollback-"+stage); var before=state(f); var reached=new AtomicBoolean(); var inserts=new AtomicInteger();
        var repository=new RiskFinalizationRepository(jdbc) {
            void trigger() { assertTrue(TransactionSynchronizationManager.isActualTransactionActive()); reached.set(true); throw new IllegalStateException("Synthetic finalization injection"); }
            @Override void insert(FrozenAssessmentLoader.Snapshot s,RiskResult.Factor factor,String receipt,Instant time) {
                super.insert(s,factor,receipt,time); if(inserts.incrementAndGet()==2 && stage.equals("factor")) { assertEquals(2,factors(f).size()); trigger(); }
            }
            @Override void complete(FrozenAssessmentLoader.Snapshot s,RiskResult r,Instant evaluated,Instant completed) {
                super.complete(s,r,evaluated,completed);
                if(stage.equals("completed")) { assertEquals("COMPLETED",parent(f).get("WORK_STATE")); assertEquals(7,factors(f).size()); trigger(); }
            }
            @Override CompletedAssessment read(FrozenAssessmentLoader.Snapshot s,FinalizationRequest request) {
                var stored=super.read(s,request); if(stage.equals("readback")) { assertEquals(7,stored.result().factors().size()); trigger(); } return stored;
            }
        };
        assertEquals(FinalizationFailure.Code.FINALIZATION_UNAVAILABLE,assertThrows(FinalizationFailure.class,()->service(repository,PinnedRiskEvaluators.current(),new OracleLeaseClock(jdbc)).finalizeAssessment(f.request())).code());
        assertTrue(reached.get(),"Earlier SQL failure is not rollback verification"); unchanged(f,before); noCompletion(f);
        assertEquals(7,service().finalizeAssessment(f.request()).result().factors().size());
    }
    @Test void finalOracleExpiryGuardRejectsEvenWhenAllSampledTimesStillAppearValid() {
        var f=prepare("final-expiry"); var expired=expire(f,f.frozen.frozenAt().plusNanos(1)); var before=state(f); var reached=new AtomicBoolean();
        var repository=new RiskFinalizationRepository(jdbc) {
            @Override void complete(FrozenAssessmentLoader.Snapshot s,RiskResult result,Instant evaluated,Instant completed) {
                assertEquals(7,factors(f).size()); assertEquals(expired,new RiskWorkRepository(jdbc).read(f.id()).lease());
                assertFalse(new OracleLeaseClock(jdbc).instant().isBefore(expired.expiresAt())); reached.set(true); super.complete(s,result,evaluated,completed);
            }
        };
        Clock stale=Clock.fixed(f.frozen.frozenAt(),ZoneOffset.UTC);
        assertEquals(WorkFailure.Code.LEASE_REJECTED,assertThrows(WorkFailure.class,()->service(repository,PinnedRiskEvaluators.current(),stale).finalizeAssessment(f.request(expired))).code());
        assertTrue(reached.get(),"Must reach Oracle's final expiry predicate with seven inserted factors and a matching fence"); unchanged(f,before); noCompletion(f);
    }
    @Test void completedWorkCannotBeClaimedRenewedOrFailed() {
        var f=prepare("completed-work"); var completed=service().finalizeAssessment(f.request()); var before=state(f);
        assertEquals(WorkFailure.Code.WORK_CONFLICT,assertThrows(WorkFailure.class,()->work.claim(completed.assessmentId(),completed.rowVersion())).code()); unchanged(f,before);
        assertEquals(WorkFailure.Code.LEASE_REJECTED,assertThrows(WorkFailure.class,()->work.renew(f.frozen.lease())).code()); unchanged(f,before);
        for(var code:WorkDiagnostic.values()) { assertEquals(WorkFailure.Code.LEASE_REJECTED,assertThrows(WorkFailure.class,()->work.fail(f.frozen.lease(),code)).code()); unchanged(f,before); }
    }
    @Test void unresolvedPolicyCannotProduceCompletedZeroRisk() {
        var f=prepare("policy-unresolved"); var before=state(f);
        assertEquals(FinalizationFailure.Code.POLICY_UNRESOLVED,assertThrows(FinalizationFailure.class,()->service().finalizeAssessment(f.request())).code()); unchanged(f,before); noCompletion(f);
    }
}
