package com.ofss.risk.persistence;

import com.ofss.configuration.BusinessConfigurationApplication;
import com.ofss.internal.*;
import com.ofss.internal.verification.*;
import com.ofss.risk.RiskAssessmentApplication;
import com.ofss.risk.configuration.BusinessConfigurationHttpAdapter;
import com.ofss.risk.facts.*;
import com.ofss.risk.submission.*;
import com.ofss.risk.submission.SubmissionAdapters.*;
import com.ofss.risk.upstream.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.*;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import static com.ofss.risk.upstream.ConfiguredClientFixtures.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

/** USER-RUN ONLY: real readiness/Oracle + actual three HTTP clients. Ephemeral issuer/providers are NOT D1/D3 integration. */
@EnabledIfEnvironmentVariable(named="D2_VERIFY_ORACLE_CONFIGURED_CLIENTS",matches="true")
@EnabledIfEnvironmentVariable(named="D2_CONFIGURED_CLIENTS_ISOLATED",matches="true")
@DisabledIfEnvironmentVariable(named="D2_CONFIGURED_CLIENTS_CLEANUP_ONLY",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS) @TestMethodOrder(MethodOrderer.OrderAnnotation.class) @Execution(ExecutionMode.SAME_THREAD)
class OracleConfiguredClientsFlowTest {
    final ConfiguredClientFixtures fixture=new ConfiguredClientFixtures("d2-clients-"+UUID.randomUUID());final EphemeralCredentials credentials=new EphemeralCredentials();
    final AtomicInteger configurationCalls=new AtomicInteger(),finalizations=new AtomicInteger();final AtomicBoolean forbid=new AtomicBoolean();
    final CyclicBarrier acceptanceBarrier=new CyclicBarrier(2);final Set<String> concurrentThreads=ConcurrentHashMap.newKeySet();
    JdbcTemplate riskJdbc,configJdbc;JdbcTransactionManager riskTx,configTx;OracleConfiguredClientConfiguration configurations;
    EphemeralReceiver configReceiver,riskReceiver;ConfiguredClientsProvider provider;DependencyCalls dependencies;HttpClient http;ExecutorService concurrent;
    boolean guarded,safe=true,receiverStarting;
    static String secret(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalArgumentException("External "+name+" required");return value;}
    static DataSource configurationSource(){
        if(!"D2_CONFIGURATION".equals(System.getenv("CONFIG_DB_USERNAME")))throw new IllegalArgumentException("CONFIG_DB_USERNAME must be D2_CONFIGURATION");
        var ds=new DriverManagerDataSource("jdbc:oracle:thin:@//localhost:1521/FREEPDB1","D2_CONFIGURATION",secret("CONFIG_DB_PASSWORD"));
        try(var c=ds.getConnection();var statement=c.createStatement();var rows=statement.executeQuery(OracleReadOnlyVerificationTest.IDENTITY)){
            assertTrue(rows.next());assertEquals("FREEPDB1",rows.getString(1));assertEquals("D2_CONFIGURATION",rows.getString(2));assertEquals("D2_CONFIGURATION",rows.getString(3));
        }catch(java.sql.SQLException failure){throw new AssertionError("Configuration connection guard failed; no fixture writes allowed");}return ds;
    }
    @BeforeAll void prepare()throws Exception{
        OracleInternalRiskHttpTest.localTarget(System.getenv("RISK_DB_URL"));
        var risk=OracleAcceptanceConnection.externalDataSource();var config=configurationSource();riskJdbc=jdbc(risk);configJdbc=jdbc(config);riskTx=new JdbcTransactionManager(risk);configTx=new JdbcTransactionManager(config);
        configurations=new OracleConfiguredClientConfiguration(fixture.namespace,configJdbc,configTx);configurations.emptyPointerGuard();
        assertEquals(0L,riskJdbc.queryForObject("SELECT COUNT(*) FROM D2_RISK.RISK_ASSESSMENTS WHERE SUPPLIER_ID=?",Long.class,new java.math.BigDecimal(fixture.supplier)));
        guarded=true;System.out.println("Configured-client fixture namespace: "+fixture.namespace);
        http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
        receiverStarting=true;configReceiver=EphemeralReceiver.start(BusinessConfigurationApplication.class,credentials,"business-configuration-service","{\"risk-assessment-service\":[\"CONFIGURATION_ACTIVE_READ\",\"CONFIGURATION_VERSION_READ\"]}",true,db("D2_CONFIGURATION",secret("CONFIG_DB_PASSWORD")),c->{
            var registration=new FilterRegistrationBean<jakarta.servlet.Filter>((request,response,chain)->{var incoming=(jakarta.servlet.http.HttpServletRequest)request;
                if(incoming.getRequestURI().startsWith("/internal/v1/configuration/")){configurationCalls.incrementAndGet();if(forbid.get()){var reply=(jakarta.servlet.http.HttpServletResponse)response;reply.setStatus(503);reply.setContentType("application/json");reply.getWriter().write("{\"code\":\"TEST_DEPENDENCY_FORBIDDEN\"}");return;}}chain.doFilter(request,response);});
            c.getBeanFactory().registerSingleton("configuredClientCountFilter",registration);
        });receiverStarting=false;
        configurations.create(configReceiver,"A");configurations.create(configReceiver,"B");configurations.activate(configReceiver,"A");
        provider=new ConfiguredClientsProvider(fixture,credentials);
        String configToken=credentials.service("business-configuration-service","risk-assessment-service",List.of(ServicePermissions.CONFIG_ACTIVE,ServicePermissions.CONFIG_VERSION));
        String providerToken=credentials.service(ConfiguredClientsProvider.AUDIENCE,"risk-assessment-service",List.of(FactsProviderProtocol.PERMISSION,EvidenceProviderProtocol.PERMISSION));
        receiverStarting=true;riskReceiver=EphemeralReceiver.start(RiskAssessmentApplication.class,credentials,"risk-assessment-service","{\"configured-source\":[\"RISK_ASSESSMENT_SUBMIT\",\"RISK_ASSESSMENT_READ\",\"RISK_FACTORS_READ\",\"RISK_CONFIGURATION_READ\"]}",true,db("D2_RISK",secret("RISK_DB_PASSWORD")),c->{
            var settings=new HashMap<String,Object>();settings.put("risk-submission.enabled",true);settings.put("risk-submission.sources","{\"configured-source\":[\""+fixture.namespace+"\"]}");settings.put("risk-submission.dependency-budget","PT25S");settings.put("RISK_LEASE_DURATION","PT60S");
            settings.put("risk-configuration-client.enabled",true);settings.put("risk-configuration-client.base-url",configReceiver.base().toString());settings.put("risk-configuration-client.connect-timeout","PT3S");settings.put("risk-configuration-client.request-timeout","PT5S");settings.put("risk-configuration-client.max-response-bytes",1048576);settings.put("risk-configuration-client.service-token",configToken);
            for(String kind:List.of("facts","evidence")){String prefix="risk-"+kind+"-client.";settings.put(prefix+"enabled",true);settings.put(prefix+"base-url",provider.base().toString());settings.put(prefix+"provider-service-id",kind.equals("facts")?FACTS:EVIDENCE);settings.put(prefix+"connect-timeout","PT3S");settings.put(prefix+"header-timeout","PT5S");settings.put(prefix+"body-timeout","PT5S");settings.put(prefix+"max-response-bytes",1048576);settings.put(prefix+"service-token",providerToken);}
            settings.put("risk-evidence-client.page-size",2);settings.put("risk-evidence-client.max-pages",10);settings.put("risk-evidence-client.max-records",100);
            c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("configured-client-opt-in-test",settings));
            c.getBeanFactory().addBeanPostProcessor(new BeanPostProcessor(){@Override public Object postProcessAfterInitialization(Object bean,String name){
                if(bean instanceof RiskAcceptanceService target){var spy=spy(target);doAnswer(call->{AcceptanceRequest request=call.getArgument(0);if(request.identity().key().equals("concurrent")&&concurrentThreads.add(Thread.currentThread().getName()))acceptanceBarrier.await(10,TimeUnit.SECONDS);return call.callRealMethod();}).when(spy).accept(any());return spy;}
                if(bean instanceof RiskFinalizationService target){var spy=spy(target);doAnswer(call->{finalizations.incrementAndGet();return call.callRealMethod();}).when(spy).finalizeAssessment(any());return spy;}return bean;}});
        });receiverStarting=false;
        dependencies=riskReceiver.bean(DependencyCalls.class);assertInstanceOf(BusinessConfigurationHttpAdapter.class,riskReceiver.bean(PublishedConfiguration.class));assertInstanceOf(AuthoritativeFactsHttpAdapter.class,riskReceiver.bean(Facts.class));assertInstanceOf(UpstreamEvidenceHttpAdapter.class,riskReceiver.bean(UpstreamEvidence.class));
    }
    static JdbcTemplate jdbc(DataSource source){var jdbc=new JdbcTemplate(source);jdbc.setQueryTimeout(10);return jdbc;}
    static Map<String,Object> db(String username,String password){return Map.of("spring.datasource.url","jdbc:oracle:thin:@//localhost:1521/FREEPDB1","spring.datasource.username",username,"spring.datasource.password",password,"spring.datasource.hikari.maximum-pool-size",6,"spring.datasource.hikari.connection-init-sql","");}
    record Reply(int code,Map<String,Object> body){}
    String token(){return credentials.service("risk-assessment-service","configured-source",List.of(ServicePermissions.RISK_SUBMIT,ServicePermissions.RISK_ASSESSMENT,ServicePermissions.RISK_FACTORS,ServicePermissions.RISK_CONFIGURATION));}
    @SuppressWarnings("unchecked") Reply send(String path,String body){
        if(!path.matches("/internal/v1/risk-assessments(?:/[1-9][0-9]*(?:/factors|/configuration-snapshot)?)?")&&!path.equals("/internal/v1/risk-assessment-submissions/status?submissionSource="+fixture.namespace+"&submissionKey=concurrent"))throw new IllegalArgumentException("Unknown live fixture route");
        var builder=HttpRequest.newBuilder(riskReceiver.base().resolve(path)).timeout(Duration.ofSeconds(45)).header("Accept","application/json").header("Authorization","Bearer "+token());if(body==null)builder.GET();else builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        try{var response=http.send(builder.build(),HttpResponse.BodyHandlers.ofString());if(response.body().length()>2097152)throw new IllegalArgumentException();return new Reply(response.statusCode(),(Map<String,Object>)JSON.readValue(response.body(),Map.class));}
        catch(Exception failure){safe=false;if(failure instanceof InterruptedException)Thread.currentThread().interrupt();throw new AssertionError("HTTP completion uncertain; defer cleanup; diagnostics withheld");}
    }
    Reply post(String key){return send("/internal/v1/risk-assessments",JSON.writeValueAsString(fixture.command(key)));}
    void completed(Reply reply){assertEquals(200,reply.code());assertEquals("COMPLETED",reply.body().get("status"));assertNotNull(reply.body().get("result"));String id=(String)reply.body().get("assessmentId");assertEquals(7,((List<?>)send("/internal/v1/risk-assessments/"+id+"/factors",null).body().get("factors")).size());
        assertEquals(configurations.revisions.get(Long.parseLong((String)reply.body().get("configurationVersionId"))),reply.body().get("configurationRowVersion"),"Use published version revision, never pointer revision");
        for(String suffix:List.of("","/configuration-snapshot")){var read=send("/internal/v1/risk-assessments/"+id+suffix,null);assertEquals(200,read.code());assertEquals(id,read.body().get("assessmentId"));}}
    Map<String,Object> parent(String key){return riskJdbc.queryForObject("SELECT * FROM D2_RISK.RISK_ASSESSMENTS WHERE SUBMISSION_SOURCE=? AND SUBMISSION_KEY=?",(rs,n)->OracleWorkPersistenceTest.values(rs),fixture.namespace,key);}
    Map<String,Object> state(String key){var parent=parent(key);var result=new TreeMap<String,Object>();result.put("parent",parent);for(String table:List.of("RISK_CONFIGURATION_SNAPSHOTS","RISK_INPUT_EVIDENCE","RISK_ASSESSMENT_FACTORS"))result.put(table,riskJdbc.query("SELECT * FROM D2_RISK."+table+" WHERE ASSESSMENT_ID=? ORDER BY 1",(rs,n)->OracleWorkPersistenceTest.values(rs),parent.get("ASSESSMENT_ID")));return result;}
    @Test @Order(1) void completeProductionClientsAndCaptureActualLocalPriorHistory()throws Exception{
        completed(post("seed"));Instant completed=(Instant)parent("seed").get("COMPLETED_AT");long until=System.nanoTime()+Duration.ofSeconds(2).toNanos();while(!Instant.now().isAfter(completed)&&System.nanoTime()<until)TimeUnit.MILLISECONDS.sleep(10);assertTrue(Instant.now().isAfter(completed),"Oracle/app clock alignment required for this fixture");
        var reply=post("history");completed(reply);assertEquals(configurations.ids.get("A").toString(),reply.body().get("configurationVersionId"));
        var stored=riskReceiver.bean(RiskAcceptanceRepository.class).find(new SubmissionIdentity(fixture.namespace,"history")).orElseThrow();var loaded=riskReceiver.bean(SubmissionSnapshotReader.class).restore(stored);
        var history=riskReceiver.bean(PriorHistoryCaptureService.class).loadRequired(new PriorAssessmentHistory.Request(loaded,Set.of(fixture.command("history").identity(),new SubmissionIdentity(fixture.namespace,"retry-of-history")),null));
        assertEquals(1,history.evidence().records().size());assertEquals("d2-risk-assessment:"+parent("seed").get("ASSESSMENT_ID"),history.evidence().records().getFirst().evidenceId());
        assertEquals("risk-assessment-service",history.source().service());assertTrue(provider.evidenceCalls.get()>=6);assertTrue(configurationCalls.get()>=2);
    }
    @Test @Order(2) void completedRetriesAvoidEveryDependencyAndFinalization(){completed(post("retry"));var before=state("retry");int facts=provider.factsCalls.get(),evidence=provider.evidenceCalls.get(),config=configurationCalls.get(),scored=finalizations.get();
        forbid.set(true);provider.forbid.set(true);try{completed(post("retry"));}finally{forbid.set(false);provider.forbid.set(false);}assertTrue(before.equals(state("retry")),"Completed rows changed; payload withheld");assertEquals(facts,provider.factsCalls.get());assertEquals(evidence,provider.evidenceCalls.get());assertEquals(config,configurationCalls.get());assertEquals(scored,finalizations.get());}
    @Test @Order(3) void conflictingRetriesLeaveOriginalGraphUnchanged(){completed(post("conflict"));var before=state("conflict");var command=fixture.command("conflict");
        for(String field:List.of("paymentReference","validationReference","validationRevision","configurationVersionId")){var body=new TreeMap<String,Object>(JSON.readValue(JSON.writeValueAsString(command),Map.class));body.put(field,field.equals("configurationVersionId")?configurations.ids.get("B").toString():field.equals("paymentReference")?"18":"different");var reply=send("/internal/v1/risk-assessments",JSON.writeValueAsString(body));assertEquals(409,reply.code());assertEquals("SUBMISSION_CONFLICT",reply.body().get("code"));assertTrue(before.equals(state("conflict")),"Conflict changed stored rows");}}
    @Test @Order(4) void incompleteCertifiedPopulationNeverFreezesOrScores(){var reply=post("incomplete");assertEquals(202,reply.code());assertEquals("RETRYABLE_FAILURE",reply.body().get("status"));assertNull(reply.body().get("result"));var parent=parent("incomplete");assertNull(parent.get("INPUT_HASH"));assertNull(parent.get("FINAL_SCORE"));assertEquals(0L,riskJdbc.queryForObject("SELECT COUNT(*) FROM D2_RISK.RISK_ASSESSMENT_FACTORS WHERE ASSESSMENT_ID=?",Long.class,parent.get("ASSESSMENT_ID")));}
    @Test @Order(5) void transientProviderFailureRecoversWithFrozenAcceptanceAndOriginalCutoff(){provider.failOnce.add("dependency");var first=post("dependency");assertEquals(202,first.code());assertNull(first.body().get("result"));var before=parent("dependency");completed(post("dependency"));var after=parent("dependency");for(String field:List.of("ACCEPTANCE_JSON","ACCEPTANCE_HASH","CONFIGURATION_HASH","CONFIGURATION_SNAPSHOT_ID","EVIDENCE_AS_OF"))assertTrue(Objects.equals(before.get(field),after.get(field)),"Recovery changed frozen "+field);}
    @Test @Order(6) void activationChangeCannotReplaceAnAcceptedPinnedConfiguration(){provider.failOnce.add("pinned");assertEquals(202,post("pinned").code());var before=parent("pinned");int configCalls=configurationCalls.get();configurations.activate(configReceiver,"B");var recovered=post("pinned");completed(recovered);assertEquals(configurations.ids.get("A").toString(),recovered.body().get("configurationVersionId"));assertEquals(configCalls,configurationCalls.get());assertEquals(before.get("CONFIGURATION_SNAPSHOT_ID"),parent("pinned").get("CONFIGURATION_SNAPSHOT_ID"));
        var next=post("after-activation");completed(next);assertEquals(configurations.ids.get("B").toString(),next.body().get("configurationVersionId"));
        var original=fixture.command("explicit-pinned");var pinned=new SubmissionCommand(original.contractVersion(),original.submissionSource(),original.submissionKey(),original.paymentReference(),original.validationReference(),original.validationRevision(),configurations.ids.get("A").toString());
        var explicit=send("/internal/v1/risk-assessments",JSON.writeValueAsString(pinned));completed(explicit);assertEquals(configurations.ids.get("A").toString(),explicit.body().get("configurationVersionId"));}
    @Test @Order(7) void concurrentConfiguredSubmissionsCommitOneLogicalAssessment()throws Exception{int before=finalizations.get();concurrent=Executors.newFixedThreadPool(2);Future<Reply> a=null,b=null;try{a=concurrent.submit(()->post("concurrent"));b=concurrent.submit(()->post("concurrent"));var first=a.get(50,TimeUnit.SECONDS);var second=b.get(50,TimeUnit.SECONDS);assertTrue(Set.of(200,202).contains(first.code())&&Set.of(200,202).contains(second.code()));assertEquals(first.body().get("assessmentId"),second.body().get("assessmentId"));assertEquals(first.body().get("cutoff"),second.body().get("cutoff"));assertEquals(2,concurrentThreads.size());}
        catch(Exception failure){safe=false;if(a!=null)a.cancel(true);if(b!=null)b.cancel(true);throw new AssertionError("Concurrent completion uncertain; cleanup deferred");}finally{stopConcurrent();}
        completed(post("concurrent"));var status=send("/internal/v1/risk-assessment-submissions/status?submissionSource="+fixture.namespace+"&submissionKey=concurrent",null);assertEquals(200,status.code());assertEquals("COMPLETED",status.body().get("status"));assertEquals(1L,riskJdbc.queryForObject("SELECT COUNT(*) FROM D2_RISK.RISK_ASSESSMENTS WHERE SUBMISSION_SOURCE=? AND SUBMISSION_KEY='concurrent'",Long.class,fixture.namespace));
        assertEquals(before+1,finalizations.get());assertEquals(1L,riskJdbc.queryForObject("SELECT COUNT(*) FROM D2_RISK.RISK_CONFIGURATION_SNAPSHOTS WHERE ASSESSMENT_ID=?",Long.class,parent("concurrent").get("ASSESSMENT_ID")));assertEquals("1",parent("concurrent").get("ATTEMPT_COUNT").toString());}
    void stopConcurrent(){if(concurrent!=null){concurrent.shutdownNow();try{safe=concurrent.awaitTermination(10,TimeUnit.SECONDS)&&safe;}catch(InterruptedException e){Thread.currentThread().interrupt();safe=false;}}}
    @AfterAll void shutdownAndClean(){stopConcurrent();safe=VerificationShutdown.closeAll(riskReceiver,provider,configReceiver)&&safe&&!receiverStarting;if(dependencies!=null&&!dependencies.terminated())safe=false;if(provider!=null&&!provider.terminated())safe=false;
        if(http!=null){http.shutdown();try{if(!http.awaitTermination(Duration.ofSeconds(10))){http.shutdownNow();safe=http.awaitTermination(Duration.ofSeconds(5))&&safe;}}catch(InterruptedException e){Thread.currentThread().interrupt();safe=false;}}
        if(!guarded)return;if(!safe)throw new AssertionError("Cleanup deferred: confirm all workers stopped; retain exact fixture namespace");
        configurations.discover();configurations.preflightCleanup();var hashes=configurations.verifiedHashes();for(long id:configurations.ids.values())assertEquals(0L,riskJdbc.queryForObject("SELECT COUNT(*) FROM D2_RISK.RISK_ASSESSMENTS WHERE CONFIGURATION_VERSION_ID=? AND SUBMISSION_SOURCE<>?",Long.class,id,fixture.namespace),"External consumer references fixture; cleanup refused");
        new OracleConfiguredClientRiskCleaner(riskJdbc,riskTx).clean(fixture.namespace,hashes);configurations.clean();System.out.println("Configured-client receivers/client/providers/dependency/concurrent workers terminated; both schemas have zero fixture parents; namespace="+fixture.namespace);
    }
}
