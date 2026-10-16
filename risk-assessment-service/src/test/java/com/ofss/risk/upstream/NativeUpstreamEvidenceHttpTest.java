package com.ofss.risk.upstream;

import com.ofss.internal.InternalServiceVerifier;
import com.ofss.internal.verification.EphemeralCredentials;
import com.ofss.risk.RiskAssessmentApplication;
import com.ofss.risk.engine.*;
import com.ofss.risk.engine.RiskInput.*;
import com.ofss.risk.persistence.*;
import com.ofss.risk.submission.*;
import com.ofss.risk.submission.SubmissionAdapters.*;
import com.sun.net.httpserver.*;
import com.zaxxer.hikari.HikariDataSource;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static com.ofss.risk.upstream.EvidenceHttpFixtures.*;

/** Real native client, temporary authenticated provider and immutable in-memory receipt ledger. No Oracle or app listeners. */
class NativeUpstreamEvidenceHttpTest {
    static final EphemeralCredentials KEYS=new EphemeralCredentials();static final String AUD="evidence-test-provider";
    final InternalServiceVerifier verifier=new InternalServiceVerifier(true,EphemeralCredentials.ISSUER,AUD,KEYS.publicKeys(),
        "{\"risk-assessment-service\":[\"RISK_EVIDENCE_READ\"]}",Set.of(EvidenceProviderProtocol.PERMISSION));
    HttpServer server;ExecutorService workers;UpstreamEvidenceHttpAdapter client;
    final AtomicReference<String> scenario=new AtomicReference<>("normal"),live=new AtomicReference<>("initial");
    final List<Map<String,Object>> requests=new CopyOnWriteArrayList<>();final List<String> paths=new CopyOnWriteArrayList<>();
    final Map<String,List<byte[]>> ledger=new ConcurrentHashMap<>();final CountDownLatch stalled=new CountDownLatch(1),release=new CountDownLatch(1);
    @BeforeEach void start()throws IOException{server=HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0),0);workers=Executors.newFixedThreadPool(4,r->new Thread(r,"upstream-test-provider"));server.setExecutor(workers);server.createContext("/",this::receive);server.start();}
    @AfterEach void stop()throws Exception{release.countDown();try{if(client!=null)client.close();}finally{server.stop(0);workers.shutdownNow();assertTrue(workers.awaitTermination(5,TimeUnit.SECONDS),"Provider workers must terminate");}}
    String origin(){return "http://127.0.0.1:"+server.getAddress().getPort();}
    String token(){return KEYS.service(AUD,"risk-assessment-service",List.of(EvidenceProviderProtocol.PERMISSION));}
    MockEnvironment settings(){return new MockEnvironment().withProperty("risk-evidence-client.enabled","true").withProperty("risk-evidence-client.base-url",origin())
        .withProperty("risk-evidence-client.provider-service-id",PROVIDER).withProperty("risk-evidence-client.connect-timeout","PT1S").withProperty("risk-evidence-client.header-timeout","PT2S")
        .withProperty("risk-evidence-client.body-timeout","PT2S").withProperty("risk-evidence-client.max-response-bytes","65536").withProperty("risk-evidence-client.page-size","2")
        .withProperty("risk-evidence-client.max-pages","10").withProperty("risk-evidence-client.max-records","100").withProperty("risk-evidence-client.service-token",token());}
    UpstreamEvidenceHttpAdapter configure(MockEnvironment env){return client=(UpstreamEvidenceHttpAdapter)UpstreamEvidenceHttpAdapter.configured(env);}
    Deadline deadline(){return new Deadline(System.nanoTime()+Duration.ofSeconds(5).toNanos());}
    UpstreamBundle fetch(){return client.retrieve(accepted(),deadline());}
    void rejected(SubmissionFailure.Code code){var e=assertThrows(SubmissionFailure.class,this::fetch);assertEquals(code,e.code());assertNull(e.getCause());}
    @Test void realProductionBeanPagesAndComposesWithOnlyLocalPriorHistory(){
        var env=settings();new WebApplicationContextRunner().withUserConfiguration(RiskAssessmentApplication.class)
            .withInitializer(c->c.getEnvironment().getPropertySources().addFirst(env.getPropertySources().get("mockProperties")))
            .withPropertyValues("risk-submission.enabled=true","risk-submission.sources={\"payment-service\":[\"developer1.payment.v1\"]}","risk-submission.dependency-budget=PT5S","RISK_LEASE_DURATION=PT15S",
                "security.jwt.secret="+KEYS.userSecret(),"RISK_DB_URL=jdbc:oracle:thin:@//invalid.test:1521/FREEPDB1","RISK_DB_USERNAME=TEST_ONLY","RISK_DB_PASSWORD=TEST_ONLY","spring.sql.init.mode=never")
            .run(c->{assertNull(c.getStartupFailure());assertNull(c.getBean(HikariDataSource.class).getHikariPoolMXBean());var upstream=c.getBean(UpstreamEvidence.class);assertInstanceOf(UpstreamEvidenceHttpAdapter.class,upstream);
                var local=mock(PriorHistoryCaptureService.class);var prior=new RiskInput.Evidence<PriorAssessment>(Availability.COMPLETE,"local-only","456","INR",accepted().cutoff(),protocol().paymentFrom,List.of());
                var source=new EvidenceFreezeRequest.Source("risk-assessment-service","local-only","1");when(local.loadRequired(any())).thenReturn(new PriorAssessmentHistory(prior,source));
                var result=new ProductionEvidenceAdapter(upstream,local).retrieve(accepted(),deadline());assertSame(prior,result.input().previousAssessments());assertEquals(source,result.sources().get(EvidenceFreezeRequest.Kind.PRIOR_ASSESSMENTS));
                assertEquals(3,result.input().payments().records().size());assertEquals(RiskResult.Status.COMPLETED,new RiskScoringEngine().evaluate(accepted().configuration(),result.input()).status());
                verify(local).loadRequired(new PriorAssessmentHistory.Request(accepted(),upstream.retrieve(accepted(),deadline()).retryIdentities(),null));
            });assertEquals(4,paths.size());assertTrue(paths.stream().allMatch(EvidenceProviderProtocol.PATH::equals));
    }
    @Test void replayRetainsOriginalPopulationAfterProviderLiveDataChanges(){configure(settings());var first=fetch();live.set("new-after-capture");var replayed=fetch();assertEquals(first,replayed);assertEquals(1,ledger.size());
        assertEquals(requests.get(0).get("replayReference"),requests.get(2).get("replayReference"));assertEquals(requests.get(0).get("request"),requests.get(3).get("request"));
        assertEquals(new java.math.BigDecimal("9999999999999.99"),first.payments().records().get(1).amount());assertTrue(first.payments().records().get(1).invoiceNumber().contains("ಕನ್ನಡ😀"));
    }
    @Test void missingPageRecoveryUsesOriginalReceiptRatherThanChangedLivePopulation(){configure(settings());scenario.set("missing-page");var partial=fetch();assertEquals(Availability.INCOMPLETE,partial.payments().availability());
        live.set("changed-during-recovery");scenario.set("normal");var recovered=fetch();assertEquals(partial.sources(),recovered.sources());assertEquals(Availability.COMPLETE,recovered.payments().availability());
        assertTrue(recovered.payments().records().stream().allMatch(p->p.invoiceNumber().endsWith("-initial")));assertEquals(1,ledger.size());}
    @Test void nativeTlsHandshakeStallIsBoundedByConnectTimeout()throws Exception{
        try(var listener=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){listener.setSoTimeout(2000);var connected=new CountDownLatch(1);
            var peer=workers.submit(()->{try(var socket=listener.accept()){connected.countDown();release.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}catch(IOException closed){/* Test peer only. */}});
            try{configure(settings().withProperty("risk-evidence-client.base-url","https://127.0.0.1:"+listener.getLocalPort()).withProperty("risk-evidence-client.connect-timeout","PT0.25S"));
                assertTimeoutPreemptively(Duration.ofSeconds(2),()->rejected(SubmissionFailure.Code.DEPENDENCY_UNAVAILABLE));assertTrue(connected.await(1,TimeUnit.SECONDS));
            }finally{release.countDown();peer.get(4,TimeUnit.SECONDS);}
        }
    }
    @ParameterizedTest @ValueSource(strings={"provider","reference","revision","cutoff","supplier","exclusion","missing-cert","unknown-prior","malformed","duplicate","utf8","numeric-money","redirect","content-type","encoding","declared-size","chunked-size","mixed-page","repeated-cursor","corrupt-population"})
    void untrustedMalformedOrOversizedDataFailSafely(String mode){scenario.set(mode);configure(settings());rejected(SubmissionFailure.Code.DEPENDENCY_UNAVAILABLE);if(mode.equals("redirect"))assertEquals(1,paths.size());}
    @ParameterizedTest @ValueSource(strings={"missing-page","body-missing-page","record-capacity","page-capacity","declared-incomplete","unavailable","complete-empty"})
    void missingPagesAndLimitsCannotBecomeAuthoritativeEmpty(String mode){var env=settings();if(mode.equals("record-capacity"))env.setProperty("risk-evidence-client.max-records","2");if(mode.equals("page-capacity"))env.setProperty("risk-evidence-client.max-pages","1");
        if(mode.equals("body-missing-page"))env.setProperty("risk-evidence-client.body-timeout","PT0.25S");configure(env);scenario.set(mode);var result=fetch();
        var expected=mode.equals("complete-empty")?Availability.COMPLETE:mode.equals("unavailable")?Availability.UNAVAILABLE:Availability.INCOMPLETE;
        assertEquals(expected,result.payments().availability());assertTrue(result.payments().records().isEmpty());
        if(expected!=Availability.COMPLETE){var input=new RiskInput(accepted().current(),accepted().cutoff(),accepted().currentAndRetrySubmissionIds(),result.payments(),result.supplier(),result.transitions(),
            new RiskInput.Evidence<PriorAssessment>(Availability.COMPLETE,"local-prior","456","INR",accepted().cutoff(),protocol().paymentFrom,List.of()));
            var evaluated=new RiskScoringEngine().evaluate(accepted().configuration(),input);assertEquals(RiskResult.Status.TECHNICAL_FAILURE,evaluated.status());assertNull(evaluated.rawTotal());assertNull(evaluated.finalScore());}
        if(mode.equals("record-capacity")||mode.equals("page-capacity"))assertEquals(1,paths.size());
    }
    @ParameterizedTest @ValueSource(strings={"gone-first","gone-later"})
    void irreproducibleOriginalCutoffRequiresIntervention(String mode){scenario.set(mode);configure(settings());rejected(SubmissionFailure.Code.CUTOFF_NOT_REPRODUCIBLE);}
    @ParameterizedTest @ValueSource(strings={"wrong-key","unknown-kid","issuer","expired","missing-sub","missing-exp","missing-type","missing-permissions","audience","identity","permission","user"})
    void actualProviderRequiresDedicatedPermissionAndServicePrincipal(String variation){var value=switch(variation){
        case "audience"->KEYS.service("wrong","risk-assessment-service",List.of(EvidenceProviderProtocol.PERMISSION));case "identity"->KEYS.service(AUD,"not-allowed",List.of(EvidenceProviderProtocol.PERMISSION));
        case "permission"->KEYS.service(AUD,"risk-assessment-service",List.of("RISK_FACTS_READ"));case "user"->KEYS.user();default->KEYS.service(AUD,"risk-assessment-service",List.of(EvidenceProviderProtocol.PERMISSION),variation);};
        configure(settings().withProperty("risk-evidence-client.service-token",value));rejected(SubmissionFailure.Code.DEPENDENCY_UNAVAILABLE);assertTrue(requests.isEmpty());}
    @ParameterizedTest @ValueSource(strings={"headers","body","deadline"})
    void nativeHeaderBodyAndWholeOperationTimeoutsCancel(String mode)throws Exception{var env=settings();if(!mode.equals("deadline"))env.setProperty("risk-evidence-client."+(mode.equals("headers")?"header-timeout":"body-timeout"),"PT0.25S");configure(env);scenario.set(mode.equals("headers")?"timeout-headers":"timeout-body");
        assertTimeoutPreemptively(Duration.ofSeconds(3),()->{var d=mode.equals("deadline")?new Deadline(System.nanoTime()+Duration.ofMillis(250).toNanos()):deadline();assertThrows(SubmissionFailure.class,()->client.retrieve(accepted(),d));});
        assertTrue(stalled.await(1,TimeUnit.SECONDS));release.countDown();scenario.set("normal");assertEquals(Availability.COMPLETE,fetch().payments().availability());}
    @Test void missingCredentialsAndAmbientTransactionsSendNothing(){var env=settings();configure(env);env.setProperty("risk-evidence-client.service-token","");rejected(SubmissionFailure.Code.ADAPTER_UNAVAILABLE);assertTrue(paths.isEmpty());
        env.setProperty("risk-evidence-client.service-token",token());TransactionSynchronizationManager.setActualTransactionActive(true);try{rejected(SubmissionFailure.Code.DEPENDENCY_UNAVAILABLE);}finally{TransactionSynchronizationManager.clear();}assertTrue(paths.isEmpty());
        assertThrows(SubmissionFailure.class,()->client.retrieve(accepted(),new Deadline(System.nanoTime()-1)));assertTrue(paths.isEmpty());}
    @SuppressWarnings("unchecked") void receive(HttpExchange ex)throws IOException{
        try(ex){paths.add(ex.getRequestURI().toString());try{var a=ex.getRequestHeaders().get("Authorization");if(a==null||a.size()!=1||!a.getFirst().startsWith("Bearer "))throw new IllegalArgumentException();
            var principal=verifier.authenticate(a.getFirst().substring(7));if(principal.getAuthorities().stream().noneMatch(p->p.getAuthority().equals("SERVICE_PERMISSION_"+EvidenceProviderProtocol.PERMISSION))){ex.sendResponseHeaders(403,-1);return;}
        }catch(Exception denied){ex.sendResponseHeaders(401,-1);return;}
        if(!ex.getRequestMethod().equals("POST")||!ex.getRequestURI().toString().equals(EvidenceProviderProtocol.PATH)){ex.sendResponseHeaders(404,-1);return;}
        var data=ex.getRequestBody().readNBytes(32769);if(data.length>32768){ex.sendResponseHeaders(413,-1);return;}var request=(Map<String,Object>)JSON.readValue(data,Map.class);requests.add(request);int index=((Number)request.get("pageIndex")).intValue();
        if(!Objects.equals(request.get("cursor"),index==0?null:"opaque-page-1")){ex.sendResponseHeaders(400,-1);return;}
        var frozen=ledger.computeIfAbsent((String)request.get("replayReference"),key->{var list=new ArrayList<Map<String,Object>>();String value=live.get();for(int i=0;i<2;i++){var r=response(request,i);for(var row:rows(r,"paymentHistory"))row.put("invoiceNumber",row.get("invoiceNumber")+"-"+value);list.add(r);}rehash(list);return list.stream().map(EvidenceHttpFixtures::bytes).toList();});
        var result=(Map<String,Object>)JSON.readValue(frozen.get(index),Map.class);var mode=scenario.get();
        if(mode.equals("gone-first")||mode.equals("gone-later")&&index==1){ex.sendResponseHeaders(410,-1);return;}
        if(mode.equals("missing-page")&&index==1){ex.sendResponseHeaders(404,-1);return;}
        switch(mode){case "provider"->result.put("providerServiceId","other");case "reference"->object(object(result,"request"),"factsReceipt").put("recordReference","wrong");case "revision"->object(result,"supplier").put("sourceRevision","changed");
            case "cutoff"->object(result,"request").put("cutoff",accepted().cutoff().plusNanos(1).toString());case "supplier"->rows(result,"paymentHistory").getFirst().put("supplierId","9");
            case "exclusion"->rows(result,"paymentHistory").getFirst().put("submission",map("source",accepted().identity().source(),"key",accepted().identity().key()));
            case "missing-cert"->result.remove("certification");case "unknown-prior"->result.put("priorAssessments",List.of());case "numeric-money"->rows(result,"paymentHistory").getFirst().put("amount",1.01);
            case "mixed-page"->{if(index==1)result.put("replayReference","another-snapshot");}case "repeated-cursor"->{if(index==1)object(result,"pagination").put("nextCursor","opaque-page-1");}
            case "corrupt-population"->{if(index==1)rows(result,"paymentHistory").getFirst().put("amount","1.00");}
        }
        if(Set.of("declared-incomplete","unavailable","complete-empty").contains(mode)){object(result,"pagination").putAll(map("totalPages",1,"totalPayments",0,"totalTransitions",0,"nextCursor",null));
            for(String name:List.of("paymentHistory","supplierTransitions")){var section=object(result,name);section.put("records",List.of());section.put("availability",mode.equals("complete-empty")?"COMPLETE":mode.equals("unavailable")?"UNAVAILABLE":"INCOMPLETE");}rehash(List.of(result));}
        if(mode.equals("redirect")){ex.getResponseHeaders().set("Location",origin()+"/other");ex.sendResponseHeaders(307,-1);return;}
        if(mode.equals("timeout-headers")){stalled.countDown();awaitRelease();}
        byte[] payload=bytes(result);if(mode.equals("malformed"))payload="not-json".getBytes(StandardCharsets.UTF_8);if(mode.equals("duplicate"))payload=new String(payload,StandardCharsets.UTF_8).replace("\"immutable\":true","\"immutable\":true,\"immutable\":false").getBytes(StandardCharsets.UTF_8);
        if(mode.equals("utf8"))payload=new byte[]{'{',(byte)0xff,'}'};
        ex.getResponseHeaders().set("Content-Type",mode.equals("content-type")?"text/html":"application/json; charset=UTF-8");if(mode.equals("encoding"))ex.getResponseHeaders().set("Content-Encoding","gzip");
        if(mode.equals("timeout-body")||mode.equals("body-missing-page")&&index==1){ex.sendResponseHeaders(200,0);ex.getResponseBody().write('{');ex.getResponseBody().flush();stalled.countDown();awaitRelease();return;}
        if(mode.equals("declared-size")||mode.equals("chunked-size")){var large=Arrays.copyOf(payload,65537);Arrays.fill(large,payload.length,large.length,(byte)' ');ex.sendResponseHeaders(200,mode.equals("chunked-size")?0:large.length);ex.getResponseBody().write(large);return;}
        ex.sendResponseHeaders(200,payload.length);ex.getResponseBody().write(payload);
        }catch(IOException cancelled){/* No credentials, raw payloads or transport diagnostics are logged. */}
    }
    void awaitRelease(){try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
}
