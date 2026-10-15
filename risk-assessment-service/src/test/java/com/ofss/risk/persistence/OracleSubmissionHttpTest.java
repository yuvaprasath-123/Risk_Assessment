package com.ofss.risk.persistence;

import com.ofss.risk.RiskAssessmentApplication;
import com.ofss.risk.submission.*;
import com.ofss.risk.submission.SubmissionAdapters.*;
import com.ofss.internal.*;
import com.ofss.internal.verification.*;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.*;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** OPT-IN writes + temporary loopback server. Contract test issuer/adapters only, no genuine D1/publication proof. */
@EnabledIfEnvironmentVariable(named="RISK_VERIFY_ORACLE_SUBMISSION_HTTP_WRITES",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class OracleSubmissionHttpTest {
    static final String AUDIENCE="risk-assessment-service";
    static final String GRANTS="{\"contract-source\":[\"RISK_ASSESSMENT_SUBMIT\",\"RISK_ASSESSMENT_READ\",\"RISK_FACTORS_READ\",\"RISK_CONFIGURATION_READ\"],\"contract-reader\":[\"RISK_ASSESSMENT_READ\"],\"other-source\":[\"RISK_ASSESSMENT_SUBMIT\"]}";
    final OracleSubmissionFixtures fixture=new OracleSubmissionFixtures();final EphemeralCredentials credentials=new EphemeralCredentials();
    final JsonMapper json=JsonMapper.builder().build();JdbcTemplate jdbc;JdbcTransactionManager manager;
    EphemeralReceiver receiver,unavailable;SubmissionContractHttp http;DependencyCalls dependencyWorkers,unavailableWorkers;
    volatile boolean safe=true;boolean fixtureStarted;ExecutorService concurrent;
    static void localTarget(String url){OracleInternalRiskHttpTest.localTarget(url);}
    @BeforeAll void prepare(){
        localTarget(System.getenv("RISK_DB_URL"));var source=OracleAcceptanceConnection.externalDataSource();jdbc=new JdbcTemplate(source);jdbc.setQueryTimeout(10);manager=new JdbcTransactionManager(source);
        assertEquals(0L,count());System.out.println("Submission CONTRACT-TEST fixture namespace: "+fixture.namespace);
        fixtureStarted=true;http=new SubmissionContractHttp(()->safe=false);
        receiver=EphemeralReceiver.start(RiskAssessmentApplication.class,credentials,AUDIENCE,GRANTS,true,database(),this::contractAdapters);
        dependencyWorkers=receiver.bean(DependencyCalls.class);
    }
    void contractAdapters(ConfigurableApplicationContext c){
            settings(c);c.getBeanFactory().registerSingleton("submissionContractFacts",(Facts)fixture::facts);
            c.getBeanFactory().registerSingleton("submissionContractEvidence",(Evidence)fixture::retrieve);
            c.getBeanFactory().registerSingleton("submissionContractPublishedConfiguration",(PublishedConfiguration)fixture::resolve);
            c.getBeanFactory().addBeanPostProcessor(new BeanPostProcessor(){
                @Override public Object postProcessAfterInitialization(Object bean,String name){
                    if(bean instanceof RiskAcceptanceService acceptance){var spy=spy(acceptance);doAnswer(i->{fixture.acceptanceRace(i.getArgument(0));return i.callRealMethod();}).when(spy).accept(any());return spy;}
                    if(bean instanceof RiskFinalizationService finalization){var spy=spy(finalization);doAnswer(i->{FinalizationRequest request=i.getArgument(0);
                        fixture.finalizationCalls.incrementAndGet();
                        if(request.identity().key().equals("frozen-recovery")&&fixture.failedFinalization.add("frozen-recovery"))throw new FinalizationFailure(FinalizationFailure.Code.EVALUATION_TECHNICAL_FAILURE);
                        return i.callRealMethod();}).when(spy).finalizeAssessment(any());return spy;}
                    return bean;
                }
            });
        }
    Map<String,Object> database(){String password=System.getenv("RISK_DB_PASSWORD");if(password==null||password.isBlank())throw new AssertionError("Supply RISK_DB_PASSWORD externally");return Map.of("RISK_DB_URL","jdbc:oracle:thin:@//localhost:1521/FREEPDB1","RISK_DB_USERNAME","D2_RISK","RISK_DB_PASSWORD",password);}
    void settings(ConfigurableApplicationContext c){c.getEnvironment().setActiveProfiles("submission-contract-test");c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("submission-contract-test-only",Map.of(
        "risk-submission.enabled",true,"risk-submission.sources","{\"contract-source\":[\""+fixture.namespace+"\"]}","risk-submission.dependency-budget","PT30S","RISK_LEASE_DURATION","PT120S","spring.profiles.active","submission-contract-test")));}
    String token(){return credentials.service(AUDIENCE,"contract-source",List.of(ServicePermissions.RISK_SUBMIT));}
    SubmissionContractHttp.Reply post(String key){return post(fixture.command(key),token());}
    SubmissionContractHttp.Reply post(SubmissionCommand command,String token){return http.send(receiver.base(),SubmissionContractHttp.POST,null,null,token,json.writeValueAsString(command));}
    SubmissionContractHttp.Reply status(String key){return http.send(receiver.base(),SubmissionContractHttp.STATUS,fixture.namespace,key,token(),null);}
    static void response(SubmissionContractHttp.Reply reply,int status,String code){assertEquals(status,reply.status());if(code!=null){assertEquals(code,reply.body().get("code"));assertFalse(reply.body().containsKey("result"));}}
    static void safeProjection(Map<String,Object> body){assertTrue(body.keySet().equals(Set.of("assessmentId","rowVersion","status","cutoff","configurationVersionId","configurationRowVersion","completedAt","result","retryAllowed")),"Submission response must contain only the safe projection");}
    @Test void submissionCompletesRealPipelineAndSupportsEveryInternalRead(){
        var reply=post("success");response(reply,200,null);safeProjection(reply.body());assertEquals("COMPLETED",reply.body().get("status"));
        var result=(Map<?,?>)reply.body().get("result");assertEquals("135",result.get("rawScore").toString());assertEquals("100",result.get("finalScore").toString());assertEquals("HIGH",result.get("band"));
        String id=(String)reply.body().get("assessmentId");assertTrue(((String)reply.body().get("completedAt")).endsWith("Z"));
        for(String suffix:List.of("","/factors","/configuration-snapshot")){
            String permission=suffix.equals("/factors")?ServicePermissions.RISK_FACTORS:suffix.equals("/configuration-snapshot")?ServicePermissions.RISK_CONFIGURATION:ServicePermissions.RISK_ASSESSMENT;
            var read=http.send(receiver.base(),SubmissionContractHttp.POST+"/"+id+suffix,null,null,credentials.service(AUDIENCE,"contract-source",List.of(permission)),null);
            response(read,200,null);assertEquals(id,read.body().get("assessmentId"));if(suffix.equals("/factors"))assertEquals(7,((List<?>)read.body().get("factors")).size());
        }
        response(status("success"),200,null);verifyGraph("success",7,6);
    }
    @Test void completedRetriesAreImmutableWithoutAnyDependenciesOrRescoring(){
        response(post("retries"),200,null);var before=state("retries");int facts=fixture.factCalls.get(),config=fixture.configurationCalls.get(),evidence=fixture.evidenceCalls.get(),finalizations=fixture.finalizationCalls.get();
        fixture.forbidDependencies.set(true);try{response(post("retries"),200,null);var command=fixture.command("retries");response(post(new SubmissionCommand(command.contractVersion(),command.submissionSource(),command.submissionKey(),command.paymentReference(),command.validationReference(),command.validationRevision(),fixture.published().versionId()),token()),200,null);response(status("retries"),200,null);}
        finally{fixture.forbidDependencies.set(false);}
        assertTrue(before.equals(state("retries")),"Completed retry changed persisted fields or completion time");assertEquals(facts,fixture.factCalls.get());assertEquals(config,fixture.configurationCalls.get());assertEquals(evidence,fixture.evidenceCalls.get());assertEquals(finalizations,fixture.finalizationCalls.get());
    }
    @Test void conflictingPaymentValidationAndConfigurationCannotMutateAcceptedRecord(){
        response(post("conflict"),200,null);var before=state("conflict");var c=fixture.command("conflict");
        for(String field:List.of("payment","validation","revision","configuration")){
            var changed=new SubmissionCommand(c.contractVersion(),c.submissionSource(),c.submissionKey(),field.equals("payment")?"18":c.paymentReference(),field.equals("validation")?"different":c.validationReference(),field.equals("revision")?"other-revision":c.validationRevision(),field.equals("configuration")?"2":null);
            response(post(changed,token()),409,"SUBMISSION_CONFLICT");assertTrue(before.equals(state("conflict")),"Conflict modified accepted/frozen/completed content");
        }
    }
    @Test void namespaceAndPermissionDenialsCreateNoWork(){
        long before=count();String reader=credentials.service(AUDIENCE,"contract-reader",List.of(ServicePermissions.RISK_ASSESSMENT));String other=credentials.service(AUDIENCE,"other-source",List.of(ServicePermissions.RISK_SUBMIT));
        response(post(fixture.command("success"),reader),403,"FORBIDDEN");response(post(fixture.command("success"),other),403,"SOURCE_FORBIDDEN");
        response(http.send(receiver.base(),SubmissionContractHttp.STATUS,fixture.namespace,"success",reader,null),403,"FORBIDDEN");
        response(http.send(receiver.base(),SubmissionContractHttp.STATUS,fixture.namespace,"success",other,null),403,"SOURCE_FORBIDDEN");assertEquals(before,count());
    }
    @Test void missingInvalidUserAndCrossAudienceCredentialsCannotSubmit(){
        long before=count();for(String credential:Arrays.asList(null,"invalid",credentials.user(),credentials.service("business-configuration-service","contract-source",List.of(ServicePermissions.RISK_SUBMIT))))response(post(fixture.command("success"),credential),401,"UNAUTHORIZED");assertEquals(before,count());
    }
    @Test void strictReferenceOnlyContractIsEnforcedThroughRealHttp(){
        long before=count();String valid=json.writeValueAsString(fixture.command("success"));
        for(String body:List.of("{}",valid.substring(0,valid.length()-1)+",\"score\":0}",valid.replace("risk-submission-v1","risk-submission-v2")))response(http.send(receiver.base(),SubmissionContractHttp.POST,null,null,token(),body),400,"INVALID_SUBMISSION");assertEquals(before,count());
    }
    @Test void dependencyFailureRetainsPinnedFactsAndRecoversAtOriginalCutoff(){
        var first=post("dependency");response(first,202,null);safeProjection(first.body());assertEquals("RETRYABLE_FAILURE",first.body().get("status"));assertNull(first.body().get("result"));
        var parent=parent("dependency");assertNull(parent.get("INPUT_HASH"));verifyGraph("dependency",0,1);
        response(post("dependency"),200,null);var after=parent("dependency");for(String field:List.of("ACCEPTANCE_JSON","ACCEPTANCE_HASH","CONFIGURATION_HASH","EVIDENCE_AS_OF","CONFIGURATION_SNAPSHOT_ID"))assertTrue(Objects.equals(parent.get(field),after.get(field)),"Recovery changed immutable acceptance/configuration/cutoff");verifyGraph("dependency",7,7);response(status("dependency"),200,null);
    }
    @Test void irreproducibleCutoffBlocksWithoutFrozenInputOrScore(){
        var first=post("blocked");response(first,202,null);assertEquals("BLOCKED",first.body().get("status"));assertNull(first.body().get("result"));assertEquals(false,first.body().get("retryAllowed"));verifyGraph("blocked",0,1);
        var before=state("blocked");fixture.forbidDependencies.set(true);try{response(post("blocked"),202,null);response(status("blocked"),200,null);}finally{fixture.forbidDependencies.set(false);}assertTrue(before.equals(state("blocked")),"Blocked retry changed facts/cutoff or attempted new work");
    }
    @Test void frozenInputSurvivesFailureAndRetryDoesNotFetchEvidenceAgain(){
        var first=post("frozen-recovery");response(first,202,null);assertEquals("RETRYABLE_FAILURE",first.body().get("status"));assertNull(first.body().get("result"));verifyGraph("frozen-recovery",0,7);
        var before=parent("frozen-recovery");var frozen=inputs("frozen-recovery");assertNotNull(before.get("INPUT_HASH"));int evidence=fixture.evidenceCalls.get();
        fixture.forbidDependencies.set(true);try{response(post("frozen-recovery"),200,null);}finally{fixture.forbidDependencies.set(false);}
        var after=parent("frozen-recovery");for(String field:List.of("INPUT_HASH","INPUT_FROZEN_AT","FROZEN_INPUT_EVIDENCE_ID","ACCEPTANCE_JSON","EVIDENCE_AS_OF","CONFIGURATION_HASH"))assertTrue(Objects.equals(before.get(field),after.get(field)),"Frozen recovery changed pinned data");
        assertTrue(frozen.equals(inputs("frozen-recovery")),"Frozen evidence rows were overwritten");assertEquals(evidence,fixture.evidenceCalls.get());verifyGraph("frozen-recovery",7,7);
    }
    @Test void concurrentSubmissionsProduceOneAcceptanceSnapshotClaimAndFinalization()throws Exception{
        int finalizations=fixture.finalizationCalls.get();concurrent=Executors.newFixedThreadPool(2);Future<SubmissionContractHttp.Reply> a=null,b=null;
        try{a=concurrent.submit(()->post("concurrent"));b=concurrent.submit(()->post("concurrent"));var first=a.get(45,TimeUnit.SECONDS);var second=b.get(45,TimeUnit.SECONDS);
            assertTrue(Set.of(200,202).contains(first.status())&&Set.of(200,202).contains(second.status()),"Competing requests must return completion or recoverable status");
            assertEquals(first.body().get("assessmentId"),second.body().get("assessmentId"));assertEquals(first.body().get("cutoff"),second.body().get("cutoff"));assertEquals(2,fixture.competingThreads.size());
        }catch(Exception e){safe=false;if(a!=null)a.cancel(true);if(b!=null)b.cancel(true);throw new AssertionError("Concurrent HTTP completion uncertain; cleanup deferred; payloads withheld");}
        finally{stopConcurrent();}
        var status=status("concurrent");response(status,200,null);assertEquals("COMPLETED",status.body().get("status"));assertEquals(1L,PersistenceValues.number(parent("concurrent").get("ATTEMPT_COUNT"),true));assertEquals(finalizations+1,fixture.finalizationCalls.get());verifyGraph("concurrent",7,6);
    }
    @Test void productionAdapterAbsenceFailsClearlyWithoutCreatingAssessment(){
        unavailable=EphemeralReceiver.start(RiskAssessmentApplication.class,credentials,AUDIENCE,GRANTS,true,database(),this::settings);unavailableWorkers=unavailable.bean(DependencyCalls.class);long before=count();
        response(http.send(unavailable.base(),SubmissionContractHttp.POST,null,null,token(),json.writeValueAsString(fixture.command("unavailable"))),503,"ADAPTER_UNAVAILABLE");assertEquals(before,count());
    }
    long count(){return jdbc.queryForObject("SELECT COUNT(*) FROM D2_RISK.RISK_ASSESSMENTS WHERE SUBMISSION_SOURCE=?",Long.class,fixture.namespace);}
    Map<String,Object> parent(String key){return jdbc.queryForObject("SELECT * FROM D2_RISK.RISK_ASSESSMENTS WHERE SUBMISSION_SOURCE=? AND SUBMISSION_KEY=?",(rs,n)->OracleWorkPersistenceTest.values(rs),fixture.namespace,key);}
    List<Map<String,Object>> children(String table,String key,String suffix){if(!Set.of("RISK_INPUT_EVIDENCE","RISK_CONFIGURATION_SNAPSHOTS","RISK_ASSESSMENT_FACTORS").contains(table))throw new IllegalArgumentException();return jdbc.query("SELECT * FROM D2_RISK."+table+" WHERE ASSESSMENT_ID=? "+suffix,(rs,n)->OracleWorkPersistenceTest.values(rs),parent(key).get("ASSESSMENT_ID"));}
    List<Map<String,Object>> inputs(String key){return children("RISK_INPUT_EVIDENCE",key,"AND ATTEMPT_NO=0 ORDER BY EVIDENCE_ID");}
    Map<String,Object> state(String key){return Map.of("parent",parent(key),"snapshots",children("RISK_CONFIGURATION_SNAPSHOTS",key,"ORDER BY SNAPSHOT_ID"),"inputs",children("RISK_INPUT_EVIDENCE",key,"ORDER BY EVIDENCE_ID"),"factors",children("RISK_ASSESSMENT_FACTORS",key,"ORDER BY FACTOR_ID"));}
    void verifyGraph(String key,int factors,int evidence){assertEquals(1,children("RISK_CONFIGURATION_SNAPSHOTS",key,"").size());assertEquals(factors,children("RISK_ASSESSMENT_FACTORS",key,"").size());assertEquals(evidence,children("RISK_INPUT_EVIDENCE",key,"").size());}
    void stopConcurrent(){if(concurrent!=null){concurrent.shutdownNow();try{if(!concurrent.awaitTermination(10,TimeUnit.SECONDS))safe=false;}catch(InterruptedException e){Thread.currentThread().interrupt();safe=false;}}}
    @AfterAll void cleanup(){
        stopConcurrent();safe=VerificationShutdown.closeAll(unavailable,receiver,http)&&safe;
        if(dependencyWorkers!=null&&!dependencyWorkers.terminated()||unavailableWorkers!=null&&!unavailableWorkers.terminated())safe=false;
        if(fixtureStarted&&!safe)throw new AssertionError("Submission cleanup deferred: stop every receiver/client/dependency worker; retain exact namespace");
        if(fixtureStarted){try{new OracleSubmissionFixtureCleaner(jdbc,manager).clean(fixture.namespace);assertEquals(0L,count());}catch(RuntimeException e){throw new AssertionError("Submission cleanup refused/failed; retain namespace; details withheld");}
            System.out.println("Submission receiver/client/dependency/concurrent workers terminated; guarded cleanup remaining parents=0; namespace: "+fixture.namespace);}
    }
}
