package com.ofss.risk.persistence;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** OPT-IN committed synthetic fixtures. No DDL, application startup, upstream network or issuer required. */
@EnabledIfEnvironmentVariable(named="RISK_VERIFY_ORACLE_PRIOR_CAPTURE_WRITES",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS) @Execution(ExecutionMode.SAME_THREAD)
class OraclePriorCaptureTest {
    final OraclePriorCaptureFixtures fixtures=new OraclePriorCaptureFixtures();JdbcTemplate jdbc;JdbcTransactionManager tx;RiskWorkService work;
    final OracleAcceptanceDiagnostics diagnostics=new OracleAcceptanceDiagnostics();boolean cleanupAllowed,workersStopped=true;
    record Target(AcceptanceRequest a,AcceptedAssessment accepted,WorkLease lease) {}
    @BeforeAll void connect(){
        System.out.println("Synthetic prior capture namespace (retain for recovery): "+fixtures.namespace);
        String url=System.getenv("RISK_DB_URL");assertTrue(url==null||url.equals("jdbc:oracle:thin:@//localhost:1521/FREEPDB1"),"Only fixed local FREEPDB1 is supported");
        var ds=OracleAcceptanceConnection.externalDataSource();jdbc=diagnostics.jdbc(ds);jdbc.setQueryTimeout(5);tx=new JdbcTransactionManager(ds);
        assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM D2_RISK.RISK_ASSESSMENTS WHERE SUBMISSION_SOURCE=?",Long.class,fixtures.namespace));
        for(String key:OraclePriorCaptureFixtures.CASES)assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM D2_RISK.RISK_ASSESSMENTS WHERE SUPPLIER_ID=?",Long.class,fixtures.supplier(key)),"Synthetic supplier already in use; no fixtures started");
        cleanupAllowed=true;work=new RiskWorkService(new RiskWorkRepository(jdbc),tx,new OracleLeaseClock(jdbc),Duration.ofMinutes(2));
    }
    @AfterAll void cleanup(){
        diagnostics.print();if(!cleanupAllowed||!workersStopped){System.out.println("Prior cleanup deferred; retain namespace: "+fixtures.namespace);return;}
        new OraclePriorCaptureCleaner(jdbc,tx).clean(fixtures.namespace);assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM D2_RISK.RISK_ASSESSMENTS WHERE SUBMISSION_SOURCE=?",Long.class,fixtures.namespace));
        System.out.println("Prior capture cleanup completed; remaining parents=0; namespace: "+fixtures.namespace);
    }
    Instant cutoff(){return jdbc.queryForObject(OracleLeaseClock.SQL,(rs,n)->rs.getObject("LEASE_NOW",OffsetDateTime.class).toInstant()).minusSeconds(10).truncatedTo(java.time.temporal.ChronoUnit.SECONDS).plusNanos(123456789);}
    Target accept(AcceptanceRequest a){var accepted=new RiskAcceptanceService(new RiskAcceptanceRepository(jdbc),new CanonicalAcceptance(),tx,Clock.fixed(a.cutoff().plusNanos(1),ZoneOffset.UTC)).accept(a);return new Target(a,accepted,work.claim(accepted.assessmentId(),accepted.rowVersion()));}
    Target target(String key,Instant cutoff){return accept(fixtures.request(key,key,cutoff,true,false));}
    Target seed(String key,int number,Instant completion,boolean zero){var a=fixtures.request("seed-"+key+"-"+number,key,completion.minusSeconds(3),false,zero);var t=accept(a);var frozen=new RiskEvidenceService(new RiskWorkRepository(jdbc),new RiskAcceptanceRepository(jdbc),new RiskEvidenceRepository(jdbc),tx,Clock.fixed(a.cutoff().plusSeconds(1),ZoneOffset.UTC)).freeze(fixtures.sourceInput(t.lease(),a));return new Target(a,t.accepted(),frozen.lease());}
    RiskFinalizationService finalizer(Instant completion,RiskFinalizationRepository repository){return new RiskFinalizationService(new RiskAcceptanceRepository(jdbc),new RiskWorkRepository(jdbc),new RiskEvidenceRepository(jdbc),repository,PinnedRiskEvaluators.current(),tx,Clock.fixed(completion,ZoneOffset.UTC));}
    void complete(Target seed,Instant time){var hash=new SubmissionSnapshotReader(new RiskAcceptanceRepository(jdbc),new RiskWorkRepository(jdbc),new RiskEvidenceRepository(jdbc),tx).frozenHash(seed.a().identity());finalizer(time,new RiskFinalizationRepository(jdbc)).finalizeAssessment(new FinalizationRequest(seed.a().identity(),seed.lease(),seed.accepted().acceptanceHash(),seed.accepted().configurationHash(),hash));}
    PriorHistoryCaptureService service(int capacity){return service(capacity,new RiskEvidenceRepository(jdbc));}
    PriorHistoryCaptureService service(int capacity,RiskEvidenceRepository evidence){return new PriorHistoryCaptureService(new PriorAssessmentHistoryReader(new PriorAssessmentHistoryRepository(jdbc),tx,capacity),new RiskAcceptanceRepository(jdbc),new RiskWorkRepository(jdbc),evidence,tx,new OracleLeaseClock(jdbc));}
    com.ofss.risk.submission.SubmissionAdapters.Deadline deadline(){return new com.ofss.risk.submission.SubmissionAdapters.Deadline(System.nanoTime()+Duration.ofSeconds(30).toNanos());}
    PriorAssessmentHistory.Request request(Target target){return new PriorAssessmentHistory.Request(target.a(),Set.of(),null);}
    PriorHistoryCaptureService.Captured capture(Target t,int capacity){var s=service(capacity);var r=request(t);return s.persist(t.lease(),r,s.loadOrCollect(r,deadline()));}
    List<RiskEvidenceRepository.Stored> rows(Target t){return new TransactionTemplate(tx).execute(status->new RiskEvidenceRepository(jdbc).children(Long.parseLong(t.accepted().assessmentId())));}
    EvidenceFreezeRequest fullInput(Target t,PriorHistoryCaptureService.Captured captured){
        var base=fixtures.sourceInput(captured.lease(),t.a());var input=base.input();var merged=new com.ofss.risk.engine.RiskInput(input.current(),input.cutoff(),input.currentAndRetrySubmissionIds(),input.payments(),input.supplier(),input.supplierTransitions(),captured.history().evidence());
        var sources=new EnumMap<>(base.sources());sources.put(EvidenceFreezeRequest.Kind.PRIOR_ASSESSMENTS,captured.history().source());return new EvidenceFreezeRequest(captured.lease(),t.a(),merged,sources);
    }
    @Test void capturesExactRecordsCoverageUnicodeAndHashBeforeFinalInput(){var time=cutoff();var old=seed("capture",0,time.minusNanos(1),true);complete(old,time.minusNanos(1));var target=target("capture",time);var saved=capture(target,1000);
        assertEquals(1,saved.history().evidence().records().size());assertEquals(time.minusNanos(1),saved.history().evidence().records().getFirst().assessedAt());assertTrue(saved.history().evidence().records().getFirst().triggeredFactors().contains(com.ofss.risk.contract.RuleCode.NEW_SUPPLIER));
        assertEquals(saved.history(),service(1000).loadRequired(request(target)));assertEquals(1,rows(target).size());assertTrue(rows(target).getFirst().json().contains("ಕನ್ನಡ😀"));assertNull(jdbc.queryForObject("SELECT INPUT_HASH FROM D2_RISK.RISK_ASSESSMENTS WHERE ASSESSMENT_ID=?",String.class,Long.parseLong(target.accepted().assessmentId())));
        var retained=rows(target).getFirst();var frozen=new RiskEvidenceService(new RiskWorkRepository(jdbc),new RiskAcceptanceRepository(jdbc),new RiskEvidenceRepository(jdbc),tx,new OracleLeaseClock(jdbc)).freeze(fullInput(target,saved));
        assertEquals(7,rows(target).size());assertTrue(rows(target).contains(retained));
        var completed=new RiskFinalizationService(new RiskAcceptanceRepository(jdbc),new RiskWorkRepository(jdbc),new RiskEvidenceRepository(jdbc),new RiskFinalizationRepository(jdbc),PinnedRiskEvaluators.current(),tx,new OracleLeaseClock(jdbc))
            .finalizeAssessment(new FinalizationRequest(target.a().identity(),frozen.lease(),target.accepted().acceptanceHash(),target.accepted().configurationHash(),frozen.inputHash()));
        assertEquals(10,completed.result().finalScore(),"Original zero-weight trigger must still qualify the prior-flags rule");
    }
    @Test void changedHistoryAfterCaptureCannotReplaceOrExpandTheOriginalPopulation(){var time=cutoff();var first=seed("changed",0,time.minusSeconds(1),false);complete(first,time.minusSeconds(1));var t=target("changed",time);var saved=capture(t,1000);var original=rows(t);
        var late=seed("changed",1,time.minusNanos(1),false);complete(late,time.minusNanos(1));assertEquals(saved.history(),service(1000).loadOrCollect(request(t),deadline()));assertEquals(original,rows(t));assertEquals(1,saved.history().evidence().records().size());
    }
    @Test void retryAndLeaseTakeoverReuseDurableCaptureAndRejectTheOldWorker(){var time=cutoff();var t=target("takeover",time);var saved=capture(t,1000);var oldRows=rows(t);String revision=work.fail(saved.lease(),WorkDiagnostic.TRANSIENT_FAILURE);var replacement=work.claim(t.accepted().assessmentId(),revision);
        var replay=service(1000).loadOrCollect(request(t),deadline());assertEquals(saved.history(),replay);assertEquals(replay,service(1000).persist(replacement,request(t),replay).history());assertThrows(WorkFailure.class,()->service(1000).persist(saved.lease(),request(t),saved.history()));assertEquals(oldRows,rows(t));
    }
    @Test void nanosecondHalfOpenCompletionBoundariesAreExact(){var time=cutoff();var from=time.minusSeconds(7776000);var moments=List.of(from.minusNanos(1),from,time.minusNanos(1),time);
        for(int i=0;i<moments.size();i++){var old=seed("bounds",i,moments.get(i),false);complete(old,moments.get(i));}
        var saved=capture(target("bounds",time),1000);assertEquals(List.of(from,time.minusNanos(1)),saved.history().evidence().records().stream().map(com.ofss.risk.engine.RiskInput.PriorAssessment::assessedAt).toList());
    }
    @Test void explicitRetryExclusionsPersistWithThePopulation(){var time=cutoff();var old=seed("excluded",0,time.minusSeconds(1),false);complete(old,time.minusSeconds(1));var t=target("excluded",time);var r=new PriorAssessmentHistory.Request(t.a(),Set.of(old.a().identity()),null);var s=service(1000);var saved=s.persist(t.lease(),r,s.loadOrCollect(r,deadline()));assertTrue(saved.history().evidence().records().isEmpty());assertEquals(saved.history(),s.loadRequired(r));assertThrows(RuntimeException.class,()->s.loadRequired(request(t)));}
    @Test void overCapacityFailureIsDurableAndDoesNotBecomeEmptyComplete(){var time=cutoff();for(int i=0;i<2;i++){var old=seed("capacity",i,time.minusSeconds(i+1),false);complete(old,time.minusSeconds(i+1));}var t=target("capacity",time);var saved=capture(t,1);assertEquals(com.ofss.risk.engine.RiskInput.Availability.INCOMPLETE,saved.history().evidence().availability());assertEquals(saved.history(),service(1000).loadRequired(request(t)));assertTrue(saved.history().evidence().records().isEmpty());}
    @Test void injectedPostInsertFailureActuallyRollsBackCaptureAndRevision(){var t=target("rollback",cutoff());var before=jdbc.queryForMap("SELECT ROW_VERSION,INPUT_HASH FROM D2_RISK.RISK_ASSESSMENTS WHERE ASSESSMENT_ID=?",Long.parseLong(t.accepted().assessmentId()));var reached=new AtomicBoolean();
        var repository=new RiskEvidenceRepository(jdbc){@Override Stored insert(long id,Stored row){var saved=super.insert(id,row);reached.set(true);throw new IllegalStateException("injected after successful capture INSERT");}};
        var s=service(1000,repository);var history=s.loadOrCollect(request(t),deadline());assertThrows(RuntimeException.class,()->s.persist(t.lease(),request(t),history));assertTrue(reached.get(),"Must reach the intended injection point");assertTrue(rows(t).isEmpty());assertEquals(before,jdbc.queryForMap("SELECT ROW_VERSION,INPUT_HASH FROM D2_RISK.RISK_ASSESSMENTS WHERE ASSESSMENT_ID=?",Long.parseLong(t.accepted().assessmentId())));
        var captured=capture(t,1000);var retained=rows(t);var childReached=new AtomicBoolean();var failFreeze=new RiskEvidenceRepository(jdbc){@Override Stored insert(long id,Stored row){var value=super.insert(id,row);childReached.set(true);throw new IllegalStateException("injected after final input child INSERT");}};
        assertThrows(FreezeFailure.class,()->new RiskEvidenceService(new RiskWorkRepository(jdbc),new RiskAcceptanceRepository(jdbc),failFreeze,tx,new OracleLeaseClock(jdbc)).freeze(fullInput(t,captured)));
        assertTrue(childReached.get());assertEquals(retained,rows(t));assertNull(jdbc.queryForObject("SELECT INPUT_HASH FROM D2_RISK.RISK_ASSESSMENTS WHERE ASSESSMENT_ID=?",String.class,Long.parseLong(t.accepted().assessmentId())));
    }
    @Test void finalizationUncommittedAtCollectionSnapshotIsExcludedForeverFromThatCapture()throws Exception{
        var time=cutoff();var old=seed("visibility",0,time.minusNanos(1),false);var t=target("visibility",time);var written=new CountDownLatch(1);var release=new CountDownLatch(1);var pool=Executors.newSingleThreadExecutor();
        var repository=new RiskFinalizationRepository(jdbc){@Override void complete(FrozenAssessmentLoader.Snapshot s,com.ofss.risk.engine.RiskResult result,Instant evaluated,Instant completed){super.complete(s,result,evaluated,completed);written.countDown();try{if(!release.await(15,TimeUnit.SECONDS))throw new IllegalStateException("Visibility barrier timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Visibility worker interrupted");}}};
        try{var hash=new SubmissionSnapshotReader(new RiskAcceptanceRepository(jdbc),new RiskWorkRepository(jdbc),new RiskEvidenceRepository(jdbc),tx).frozenHash(old.a().identity());var future=pool.submit(()->finalizer(time.minusNanos(1),repository).finalizeAssessment(new FinalizationRequest(old.a().identity(),old.lease(),old.accepted().acceptanceHash(),old.accepted().configurationHash(),hash)));
            assertTrue(written.await(10,TimeUnit.SECONDS));var saved=capture(t,1000);assertTrue(saved.history().evidence().records().isEmpty());release.countDown();future.get(20,TimeUnit.SECONDS);assertEquals(saved.history(),service(1000).loadOrCollect(request(t),deadline()));
        }finally{release.countDown();pool.shutdownNow();workersStopped=pool.awaitTermination(5,TimeUnit.SECONDS);assertTrue(workersStopped,"Live worker remains; cleanup deferred");System.out.println("Prior visibility worker terminated="+workersStopped);}
    }
}
