package com.ofss.risk.persistence;

import com.ofss.risk.engine.RiskInput;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in real Oracle WRITES/cleanup. No DDL, host startup or upstream calls. */
@EnabledIfEnvironmentVariable(named="RISK_VERIFY_ORACLE_EVIDENCE_WRITES",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OracleEvidencePersistenceTest {
    final OracleEvidenceFixtures fixtures=new OracleEvidenceFixtures();
    final OracleAcceptanceDiagnostics diagnostics=new OracleAcceptanceDiagnostics();
    JdbcTemplate jdbc; JdbcTransactionManager manager; RiskWorkService work; boolean cleanupAllowed;
    record Case(AcceptanceRequest acceptance,AcceptedAssessment assessment,WorkLease lease,EvidenceFreezeRequest evidence) {}
    @BeforeAll void connect() {
        System.out.println("Synthetic Oracle freeze namespace (retain for cleanup recovery): "+fixtures.namespace);
        var ds=OracleAcceptanceConnection.externalDataSource(); jdbc=diagnostics.jdbc(ds); manager=new JdbcTransactionManager(ds);
        assertEquals(0L,count()); cleanupAllowed=true;
        work=new RiskWorkService(new RiskWorkRepository(jdbc),manager,new OracleLeaseClock(jdbc),Duration.ofMinutes(2));
    }
    @AfterAll void cleanup() {
        diagnostics.print();
        if(!cleanupAllowed) { System.out.println("Freeze fixture cleanup deferred; namespace: "+fixtures.namespace); return; }
        try {
            new OracleEvidenceFixtureCleaner(jdbc,manager).clean(fixtures.namespace); assertEquals(0L,count());
            System.out.println("Freeze fixture cleanup completed; remaining parents=0; namespace: "+fixtures.namespace);
        } catch(RuntimeException e) { diagnostics.record("FREEZE_CLEANUP",e); diagnostics.print(); throw new AssertionError("Freeze cleanup failed/refused; retain exact namespace: "+fixtures.namespace); }
    }
    long count() { return jdbc.queryForObject("SELECT COUNT(*) FROM D2_RISK.RISK_ASSESSMENTS WHERE SUBMISSION_SOURCE=?",Long.class,fixtures.namespace); }
    Case prepare(String key) {
        var dbNow=jdbc.queryForObject(OracleLeaseClock.SQL,(rs,n)->rs.getObject("LEASE_NOW",OffsetDateTime.class).toInstant(),new Object[0]);
        Instant created=dbNow.minusSeconds(5).truncatedTo(ChronoUnit.SECONDS).plusNanos(123456890);
        var request=fixtures.acceptance(key,created);
        var accepted=new RiskAcceptanceService(new RiskAcceptanceRepository(jdbc),new CanonicalAcceptance(),manager,Clock.fixed(created,ZoneOffset.UTC)).accept(request);
        var lease=work.claim(accepted.assessmentId(),accepted.rowVersion()); return new Case(request,accepted,lease,fixtures.request(lease,request));
    }
    RiskEvidenceService service() { return service(new RiskEvidenceRepository(jdbc),new RiskWorkRepository(jdbc)); }
    RiskEvidenceService service(RiskEvidenceRepository delegate,RiskWorkRepository workRepository) {
        return service(delegate,workRepository,new OracleLeaseClock(jdbc));
    }
    RiskEvidenceService service(RiskEvidenceRepository delegate,RiskWorkRepository workRepository,Clock clock) {
        var observed=new RiskEvidenceRepository(jdbc) {
            @Override Stored insert(long id,Stored row) { try { return delegate.insert(id,row); } catch(RuntimeException e) { diagnostics.record("FREEZE_CHILD_INSERT",e); throw e; } }
            @Override void associate(RiskWorkRepository.Row parent,Header header,long revision) {
                try { delegate.associate(parent,header,revision); } catch(RuntimeException e) { diagnostics.record("FREEZE_ASSOCIATE",e); throw e; }
            }
            @Override List<Stored> children(long id) { try { return delegate.children(id); } catch(RuntimeException e) { diagnostics.record("FREEZE_CHILD_READBACK",e); throw e; } }
        };
        return new RiskEvidenceService(workRepository,new RiskAcceptanceRepository(jdbc),observed,manager,clock);
    }
    long id(Case fixture) { return Long.parseLong(fixture.assessment.assessmentId()); }
    Map<String,Object> parent(Case fixture) { return jdbc.queryForObject("SELECT * FROM D2_RISK.RISK_ASSESSMENTS WHERE ASSESSMENT_ID=?",(rs,n)->OracleWorkPersistenceTest.values(rs),id(fixture)); }
    Map<String,Object> snapshot(Case fixture) { return jdbc.queryForObject("SELECT * FROM D2_RISK.RISK_CONFIGURATION_SNAPSHOTS WHERE ASSESSMENT_ID=?",(rs,n)->OracleWorkPersistenceTest.values(rs),id(fixture)); }
    List<RiskEvidenceRepository.Stored> inputs(Case fixture) {
        return new TransactionTemplate(manager).execute(status->new RiskEvidenceRepository(jdbc).children(id(fixture)));
    }
    RiskEvidenceRepository.Header header(Case fixture) { return new TransactionTemplate(manager).execute(status->new RiskEvidenceRepository(jdbc).header(id(fixture))); }
    Map<String,Object> stableAcceptance(Case fixture) {
        var fields=new TreeMap<>(parent(fixture)); OracleWorkPersistenceTest.MUTABLE.forEach(fields::remove);
        fields.remove("INPUT_HASH"); fields.remove("FROZEN_INPUT_EVIDENCE_ID"); fields.remove("INPUT_FROZEN_AT"); return fields;
    }
    void unchanged(Case fixture,Map<String,Object> before,List<RiskEvidenceRepository.Stored> evidence) {
        assertTrue(before.equals(parent(fixture)),"Every parent field must be unchanged"); assertTrue(evidence.equals(inputs(fixture)),"Every attempt-0 row must be unchanged");
    }
    void noScore(Case fixture) {
        var fields=parent(fixture); for(String field:List.of("TOTAL_RAW_SCORE","FINAL_SCORE","RISK_BAND","FINAL_FACTOR_COUNT","EVALUATED_AT","COMPLETED_AT")) assertNull(fields.get(field),field);
    }
    @Test void everyGeneratedKeyClobRoundTripsExactlyAndReconstructsEngineInput() {
        var fixture=prepare("roundtrip"); var before=stableAcceptance(fixture); var snap=snapshot(fixture);
        var result=service().freeze(fixture.evidence); var rows=inputs(fixture); assertEquals(6,rows.size());
        assertEquals(fixture.evidence.input(),result.input()); assertNotSame(fixture.evidence.input(),result.input());
        assertEquals(123456789,result.input().cutoff().getNano());
        assertEquals(fixture.evidence.input().payments().records().getFirst().amount(),result.input().payments().records().getFirst().amount());
        assertEquals(result.inputHash(),header(fixture).hash()); assertEquals(result.manifestId(),Long.toString(header(fixture).manifest()));
        for(var row:rows) {
            assertTrue(row.id()>0); assertTrue(row.json().length()>32767,"Every locator INSERT with RETURNING must carry a large CLOB");
            assertEquals(CanonicalAcceptance.hash(row.json()),row.hash()); assertEquals(fixture.acceptance.cutoff(),row.cutoff());
            assertEquals(result.frozenAt(),row.captured()); assertEquals(CanonicalEvidence.HASH_FORMAT,row.hashFormat());
        }
        var manifest=rows.stream().filter(r->r.key().equals("ENGINE_INPUT")).findFirst().orElseThrow();
        assertEquals(result.manifestHash(),manifest.hash()); assertNotEquals(result.inputHash(),result.manifestHash());
        assertEquals(result.input(),FrozenInputDecoder.read(result.canonicalInputJson()));
        assertTrue(before.equals(stableAcceptance(fixture))); assertTrue(snap.equals(snapshot(fixture))); noScore(fixture);
    }
    @Test void identicalReuseAndChangedRecordsOrProvenanceNeverOverwriteFrozenData() {
        var fixture=prepare("retries"); var first=service().freeze(fixture.evidence); var before=parent(fixture); var rows=inputs(fixture);
        var request=fixtures.request(first.lease(),fixture.acceptance);
        assertEquals(first,service().freeze(request)); unchanged(fixture,before,rows);
        var source=new EnumMap<>(request.sources()); source.put(EvidenceFreezeRequest.Kind.PAYMENT_HISTORY,new EvidenceFreezeRequest.Source("synthetic-freeze-adapter","changed",null));
        var changedSource=new EvidenceFreezeRequest(first.lease(),fixture.acceptance,request.input(),source);
        assertEquals(FreezeFailure.Code.FROZEN_EVIDENCE_CONFLICT,assertThrows(FreezeFailure.class,()->service().freeze(changedSource)).code()); unchanged(fixture,before,rows);
        var input=request.input(); var old=input.payments().records().getFirst();
        var modified=new RiskInput.Payment(old.evidenceId(),old.submissionId(),old.invoiceId(),"DIFFERENT-INVOICE",old.supplierId(),old.currencyCode(),old.amount(),old.submittedAt(),old.completedAt(),old.statusAsOfCutoff(),old.validated());
        var changedInput=new RiskInput(input.current(),input.cutoff(),input.currentAndRetrySubmissionIds(),new RiskInput.Evidence<>(input.payments().availability(),input.payments().sourceSnapshotId(),
            input.payments().supplierId(),input.payments().currencyCode(),input.payments().cutoff(),input.payments().coveredFrom(),List.of(modified)),input.supplier(),input.supplierTransitions(),input.previousAssessments());
        assertEquals(FreezeFailure.Code.FROZEN_EVIDENCE_CONFLICT,assertThrows(FreezeFailure.class,()->service().freeze(new EvidenceFreezeRequest(first.lease(),fixture.acceptance,changedInput,request.sources()))).code());
        unchanged(fixture,before,rows);
    }
    @ParameterizedTest @ValueSource(strings={"UNAVAILABLE","INCOMPLETE"}) void requiredPartialEvidenceProducesNoFrozenWrites(String availability) {
        var fixture=prepare("partial-"+availability.toLowerCase(Locale.ROOT)); var original=fixture.evidence; var input=original.input(); var before=parent(fixture);
        var unavailable=new RiskInput.Evidence<RiskInput.Payment>(RiskInput.Availability.valueOf(availability),null,"456","INR",input.cutoff(),null,List.of());
        var partial=new RiskInput(input.current(),input.cutoff(),input.currentAndRetrySubmissionIds(),unavailable,input.supplier(),input.supplierTransitions(),input.previousAssessments());
        assertEquals(FreezeFailure.Code.EVIDENCE_NOT_READY,assertThrows(FreezeFailure.class,()->service().freeze(new EvidenceFreezeRequest(fixture.lease,fixture.acceptance,partial,original.sources()))).code());
        unchanged(fixture,before,List.of()); assertNull(header(fixture).hash()); assertNull(header(fixture).manifest());
    }
    @ParameterizedTest @ValueSource(strings={"child","associated"}) void injectedFailureReachesIntendedWriteAndActuallyRollsBack(String stage) {
        var fixture=prepare("rollback-"+stage); var before=parent(fixture); var injected=new AtomicBoolean(); var writes=new AtomicInteger();
        var repository=new RiskEvidenceRepository(jdbc) {
            @Override Stored insert(long id,Stored row) {
                var saved=super.insert(id,row);
                if(stage.equals("child") && writes.incrementAndGet()==2) {
                    assertEquals(2,inputs(fixture).size()); trigger();
                }
                return saved;
            }
            @Override void associate(RiskWorkRepository.Row parent,Header header,long revision) {
                super.associate(parent,header,revision);
                if(stage.equals("associated")) { assertEquals(6,inputs(fixture).size()); assertEquals(header,OracleEvidencePersistenceTest.this.header(fixture)); trigger(); }
            }
            void trigger() { assertTrue(TransactionSynchronizationManager.isActualTransactionActive()); injected.set(true); throw new IllegalStateException("Synthetic freeze injection"); }
        };
        assertEquals(FreezeFailure.Code.EVIDENCE_PERSISTENCE_UNAVAILABLE,assertThrows(FreezeFailure.class,()->service(repository,new RiskWorkRepository(jdbc)).freeze(fixture.evidence)).code());
        assertTrue(injected.get(),"Earlier SQL failure is not proof of the intended rollback"); unchanged(fixture,before,List.of()); assertNull(header(fixture).hash());
    }
    static final String EXPIRE="""
        UPDATE D2_RISK.RISK_ASSESSMENTS SET LEASE_EXPIRES_AT=CREATED_AT, ROW_VERSION=ROW_VERSION+1
        WHERE ASSESSMENT_ID=? AND SUBMISSION_SOURCE=? AND SUBMISSION_KEY=? AND EVALUATOR_VERSION=?
          AND VALIDATION_REFERENCE=? AND WORK_STATE='IN_PROGRESS' AND LEASE_TOKEN=? AND LEASE_EPOCH=?
          AND ATTEMPT_COUNT=? AND ROW_VERSION=? AND LEASE_EXPIRES_AT=?
        """;
    static final String EXPIRE_FOR_GUARD=EXPIRE.replace("LEASE_EXPIRES_AT=CREATED_AT,","LEASE_EXPIRES_AT=CREATED_AT+INTERVAL '1' SECOND,");
    WorkLease expire(Case fixture,WorkLease lease) {
        return expire(fixture,lease,false);
    }
    WorkLease expire(Case fixture,WorkLease lease,boolean shortBoundary) {
        return new TransactionTemplate(manager).execute(status->{
            var repository=new RiskWorkRepository(jdbc); repository.lock(id(fixture));
            assertEquals(1,jdbc.update(shortBoundary?EXPIRE_FOR_GUARD:EXPIRE,id(fixture),fixtures.namespace,fixture.acceptance.identity().key(),OracleEvidenceFixtures.EVALUATOR,
                fixture.acceptance.validationReference(),lease.token(),lease.epoch(),lease.attempt(),Long.parseLong(lease.rowVersion()),lease.expiresAt().atOffset(ZoneOffset.UTC)));
            var expired=repository.read(id(fixture)); assertFalse(expired.expiry().isAfter(new OracleLeaseClock(jdbc).instant())); return expired.lease();
        });
    }
    @Test void expiredAndSupersededWorkersCannotFreezeOrAppendAnyInputs() {
        var fixture=prepare("fencing"); var expired=expire(fixture,fixture.lease); var before=parent(fixture);
        assertEquals(WorkFailure.Code.LEASE_REJECTED,assertThrows(WorkFailure.class,()->service().freeze(fixtures.request(expired,fixture.acceptance))).code()); unchanged(fixture,before,List.of());
        var replacement=work.claim(fixture.assessment.assessmentId(),expired.rowVersion()); before=parent(fixture);
        assertEquals(WorkFailure.Code.LEASE_REJECTED,assertThrows(WorkFailure.class,()->service().freeze(fixture.evidence)).code()); unchanged(fixture,before,List.of());
        var result=service().freeze(fixtures.request(replacement,fixture.acceptance)); assertEquals(6,inputs(fixture).size()); assertEquals(2,result.lease().epoch());
    }
    @Test void populatedFrozenInputSurvivesFailureExpiryAndTakeoverWithoutNewIdsOrCutoff() {
        var fixture=prepare("recovery"); var frozen=service().freeze(fixture.evidence); var rows=inputs(fixture); var firstHeader=header(fixture);
        String revision=work.fail(frozen.lease(),WorkDiagnostic.TRANSIENT_FAILURE);
        assertTrue(rows.equals(inputs(fixture))); assertEquals(firstHeader,header(fixture));
        var retry=work.claim(fixture.assessment.assessmentId(),revision); var reused=service().freeze(fixtures.request(retry,fixture.acceptance));
        assertEquals(frozen.input(),reused.input()); assertEquals(frozen.manifestId(),reused.manifestId()); assertTrue(rows.equals(inputs(fixture)));
        var expired=expire(fixture,reused.lease()); assertTrue(rows.equals(inputs(fixture))); assertEquals(firstHeader,header(fixture));
        var takeover=work.claim(fixture.assessment.assessmentId(),expired.rowVersion()); var before=parent(fixture);
        assertEquals(WorkFailure.Code.LEASE_REJECTED,assertThrows(WorkFailure.class,()->service().freeze(fixtures.request(reused.lease(),fixture.acceptance))).code()); unchanged(fixture,before,rows);
        var recovered=service().freeze(fixtures.request(takeover,fixture.acceptance));
        assertEquals(frozen.input(),recovered.input()); assertEquals(frozen.manifestId(),recovered.manifestId()); assertEquals(frozen.inputHash(),recovered.inputHash());
        assertEquals(firstHeader,header(fixture)); assertTrue(rows.equals(inputs(fixture))); noScore(fixture);
    }
    @Test void concurrentFreezeRequestsCreateOneManifestAndBothWorkersTerminate() {
        var fixture=prepare("concurrent"); var barrier=new CyclicBarrier(2);
        var repository=new RiskWorkRepository(jdbc) {
            @Override Row lock(long id) {
                try { barrier.await(10,TimeUnit.SECONDS); } catch(Exception e) { throw new IllegalStateException("Synthetic freeze barrier failed"); }
                return super.lock(id);
            }
        };
        var concurrent=service(new RiskEvidenceRepository(jdbc),repository);
        var results=OracleAcceptanceWorkers.pair(()-> {
            try { return (Object)concurrent.freeze(fixture.evidence); } catch(WorkFailure e) { return e.code(); }
        },30000,()->cleanupAllowed=false,event->System.out.println("Freeze worker lifecycle: "+event));
        assertEquals(1,results.stream().filter(FrozenInput.class::isInstance).count());
        assertEquals(1,results.stream().filter(o->o==WorkFailure.Code.LEASE_REJECTED).count());
        var winner=(FrozenInput)results.stream().filter(FrozenInput.class::isInstance).findFirst().orElseThrow();
        assertEquals(6,inputs(fixture).size()); assertEquals(winner.manifestId(),Long.toString(header(fixture).manifest())); noScore(fixture);
    }
    @Test void finalOracleClockGuardRollsBackChildrenEvenWhenInitialTimeSampleIsStale() {
        var fixture=prepare("final-expiry"); var expired=expire(fixture,fixture.lease,true); var before=parent(fixture); var reached=new AtomicBoolean();
        var repository=new RiskEvidenceRepository(jdbc) {
            @Override void associate(RiskWorkRepository.Row parent,Header header,long revision) {
                assertEquals(6,inputs(fixture).size());
                assertEquals(parent,new RiskWorkRepository(jdbc).read(id(fixture)),"All identity/fence values must still match");
                assertFalse(new OracleLeaseClock(jdbc).instant().isBefore(parent.expiry()),"Real Oracle time must have passed expiry");
                reached.set(true); super.associate(parent,header,revision);
            }
        };
        // Simulates time elapsing after sampling, without sleeps or changing Oracle's clock.
        // Production wiring still uses OracleLeaseClock; only this test injects an old sample.
        Clock staleSample=Clock.fixed(expired.expiresAt().minusNanos(1),ZoneOffset.UTC);
        var service=service(repository,new RiskWorkRepository(jdbc),staleSample);
        assertEquals(WorkFailure.Code.LEASE_REJECTED,assertThrows(WorkFailure.class,()->service.freeze(fixtures.request(expired,fixture.acceptance))).code());
        assertTrue(reached.get(),"Must reach the final Oracle guard after six successful INSERTs"); unchanged(fixture,before,List.of()); assertNull(header(fixture).hash());
    }
}
