package com.ofss.risk.facts;

import com.ofss.internal.InternalServiceVerifier;
import com.ofss.internal.verification.EphemeralCredentials;
import com.ofss.risk.RiskAssessmentApplication;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.ofss.risk.facts.FactsHttpFixtures.*;

/** Real JDK transport and explicit temporary provider with real RS256 test authentication. No Oracle/app services. */
class NativeFactsHttpContractTest {
    static final EphemeralCredentials KEYS=new EphemeralCredentials();
    static final String AUDIENCE="facts-test-provider";
    final InternalServiceVerifier verifier=new InternalServiceVerifier(true,EphemeralCredentials.ISSUER,AUDIENCE,KEYS.publicKeys(),
        "{\"risk-assessment-service\":[\"RISK_FACTS_READ\"]}",Set.of(FactsProviderProtocol.PERMISSION));
    HttpServer server;ExecutorService workers;AuthoritativeFactsHttpAdapter client;
    final AtomicReference<String> scenario=new AtomicReference<>("normal");
    final List<String> paths=new CopyOnWriteArrayList<>();final List<Map<?,?>> requests=new CopyOnWriteArrayList<>();
    final CountDownLatch stalled=new CountDownLatch(1),release=new CountDownLatch(1);
    @BeforeEach void listen()throws IOException {
        server=HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0),0);
        workers=Executors.newFixedThreadPool(4,r->new Thread(r,"native-facts-provider"));server.setExecutor(workers);server.createContext("/",this::receive);server.start();
    }
    @AfterEach void close()throws Exception {
        release.countDown();try{if(client!=null)client.close();}finally{if(server!=null)server.stop(0);if(workers!=null){workers.shutdownNow();assertTrue(workers.awaitTermination(5,TimeUnit.SECONDS),"Temporary facts provider workers remain");}}
    }
    String origin(){return "http://127.0.0.1:"+server.getAddress().getPort();}
    String token(){return KEYS.service(AUDIENCE,"risk-assessment-service",List.of(FactsProviderProtocol.PERMISSION));}
    MockEnvironment settings(){return new MockEnvironment().withProperty("risk-facts-client.enabled","true").withProperty("risk-facts-client.base-url",origin())
        .withProperty("risk-facts-client.provider-service-id",PROVIDER).withProperty("risk-facts-client.connect-timeout","PT1S")
        .withProperty("risk-facts-client.header-timeout","PT2S").withProperty("risk-facts-client.body-timeout","PT2S")
        .withProperty("risk-facts-client.max-response-bytes","65536").withProperty("risk-facts-client.service-token",token());}
    AuthoritativeFactsHttpAdapter configure(MockEnvironment env){client=(AuthoritativeFactsHttpAdapter)AuthoritativeFactsHttpAdapter.configured(env);return client;}
    Deadline deadline(){return new Deadline(System.nanoTime()+Duration.ofSeconds(5).toNanos());}
    VerifiedFacts facts(){return client.verify(command(),CUTOFF,deadline());}
    void rejected(){var error=assertThrows(SubmissionFailure.class,this::facts);assertEquals(SubmissionFailure.Code.DEPENDENCY_UNAVAILABLE,error.code());assertNull(error.getCause());}
    @Test void realConfiguredSpringBeanUsesFixedPostReferencesExactFactsAndClosesWithoutDatabase(){
        var env=settings();var held=new AtomicReference<AuthoritativeFactsHttpAdapter>();
        new WebApplicationContextRunner().withUserConfiguration(RiskAssessmentApplication.class)
            .withInitializer(c->c.getEnvironment().getPropertySources().addFirst(env.getPropertySources().get("mockProperties")))
            .withPropertyValues("RISK_DB_URL=jdbc:oracle:thin:@//invalid.test:1521/FREEPDB1","RISK_DB_USERNAME=TEST_ONLY","RISK_DB_PASSWORD=TEST_ONLY",
                "security.jwt.secret="+KEYS.userSecret(),"risk-submission.enabled=true","risk-submission.sources={\"payment-service\":[\"developer1.payment.v1\"]}",
                "risk-submission.dependency-budget=PT5S","RISK_LEASE_DURATION=PT15S","spring.sql.init.mode=never")
            .run(c->{assertNull(c.getStartupFailure());var bean=(AuthoritativeFactsHttpAdapter)c.getBean(Facts.class);held.set(bean);
                assertNull(c.getBean(HikariDataSource.class).getHikariPoolMXBean());var f=bean.verify(command(),CUTOFF,deadline());
                assertEquals("9007199254740.91",f.current().amount().toPlainString());assertEquals(CUTOFF.minusSeconds(10),f.current().submittedAt());assertEquals(2,f.currentAndRetrySubmissionIds().size());
            });
        assertEquals(List.of(FactsProviderProtocol.PATH),paths);assertEquals(command().paymentReference(),requests.getFirst().get("paymentId"));
        assertEquals(CUTOFF.toString(),requests.getFirst().get("cutoff"));assertFalse(requests.getFirst().containsKey("configurationVersionId"));
        assertThrows(SubmissionFailure.class,()->held.get().verify(command(),CUTOFF,deadline()));assertEquals(1,paths.size());
    }
    @ParameterizedTest @ValueSource(strings={"fail","reference","revision","payment","identity","provider","certification","currency","timestamp","retry","hash-only","malformed","duplicate","utf8","declared-size","chunked-size","redirect","content-type","encoding"})
    void realUntrustedOrUnboundedResponsesFailClosed(String mode){scenario.set(mode);configure(settings());rejected();assertEquals(List.of(FactsProviderProtocol.PATH),paths);}
    @ParameterizedTest @ValueSource(strings={"wrong-key","unknown-kid","issuer","expired","missing-sub","missing-exp","missing-type","missing-permissions","wrong-audience","identity","permission","user"})
    void actualProviderEnforcesDedicatedServiceAuthentication(String variation){
        String credential=switch(variation){
            case "wrong-audience"->KEYS.service("wrong-audience","risk-assessment-service",List.of(FactsProviderProtocol.PERMISSION));
            case "identity"->KEYS.service(AUDIENCE,"unlisted-service",List.of(FactsProviderProtocol.PERMISSION));
            case "permission"->KEYS.service(AUDIENCE,"risk-assessment-service",List.of("OTHER_PERMISSION"));
            case "user"->KEYS.user();default->KEYS.service(AUDIENCE,"risk-assessment-service",List.of(FactsProviderProtocol.PERMISSION),variation);
        };configure(settings().withProperty("risk-facts-client.service-token",credential));rejected();assertEquals(1,paths.size());assertTrue(requests.isEmpty());
    }
    @Test void externalCredentialsRotatePerRequestAndMissingCredentialsNeverSendARequest(){
        var env=settings();configure(env);assertNotNull(facts());env.setProperty("risk-facts-client.service-token",KEYS.service(AUDIENCE,"risk-assessment-service",List.of(FactsProviderProtocol.PERMISSION),"next"));
        assertNotNull(facts());int sent=paths.size();env.setProperty("risk-facts-client.service-token","");
        assertEquals(SubmissionFailure.Code.ADAPTER_UNAVAILABLE,assertThrows(SubmissionFailure.class,this::facts).code());assertEquals(sent,paths.size());
    }
    @ParameterizedTest @ValueSource(strings={"header-timeout","body-timeout","deadline"})
    void eachNativeTimeBoundIsEnforcedWithoutLosingCancellationOrClientReuse(String mode)throws Exception {
        var env=settings();if(!mode.equals("deadline"))env.setProperty("risk-facts-client."+(mode.equals("header-timeout")?"header-timeout":"body-timeout"),"PT0.25S");
        configure(env);scenario.set(mode.equals("deadline")?"body-timeout":mode);
        assertTimeoutPreemptively(Duration.ofSeconds(3),()->{
            var d=mode.equals("deadline")?new Deadline(System.nanoTime()+Duration.ofMillis(250).toNanos()):deadline();
            assertEquals(SubmissionFailure.Code.DEPENDENCY_UNAVAILABLE,assertThrows(SubmissionFailure.class,()->client.verify(command(),CUTOFF,d)).code());
        });assertTrue(stalled.await(1,TimeUnit.SECONDS),"Must reach the intended native timeout point");
        release.countDown();scenario.set("normal");assertNotNull(facts());
    }
    @Test void ambientTransactionAndExpiredDeadlineCannotIssueNetworkRequests(){
        configure(settings());TransactionSynchronizationManager.setActualTransactionActive(true);
        try{rejected();}finally{TransactionSynchronizationManager.clear();}
        assertThrows(SubmissionFailure.class,()->client.verify(command(),CUTOFF,new Deadline(System.nanoTime()-1)));assertTrue(paths.isEmpty());
    }
    @Test void completedRetryDoesNotCallTheActualAdapterEvenIfItsCredentialIsNowMissing(){
        var env=settings();configure(env);var verified=facts();int sent=paths.size();env.setProperty("risk-facts-client.service-token","");
        var original=verified.acceptance(CUTOFF,SubmissionFixtures.request().configuration());var canonical=new CanonicalAcceptance();var configDoc=canonical.configuration(original.configuration());
        var body=canonical.acceptance(original,original.configuration().versionId(),original.configuration().rowVersion(),configDoc.hash());
        var stored=new AcceptedAssessment("1",original.identity(),"2",original.configuration().versionId(),original.configuration().rowVersion(),"9","COMPLETED",CUTOFF,CUTOFF,body.hash(),configDoc.hash(),body.json(),configDoc.json());
        var acceptance=mock(RiskAcceptanceService.class);var work=mock(RiskWorkService.class);var evidence=mock(Evidence.class);var configs=mock(PublishedConfiguration.class);
        var freeze=mock(RiskEvidenceService.class);var finalization=mock(RiskFinalizationService.class);var snapshots=mock(SubmissionSnapshotReader.class);var reads=mock(AssessmentReadService.class);
        var tx=mock(PlatformTransactionManager.class);when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(acceptance.find(command().identity())).thenReturn(Optional.of(stored));when(snapshots.restore(stored)).thenReturn(original);when(reads.get("1")).thenReturn(SubmissionFixtures.view("COMPLETED"));
        try(var calls=new DependencyCalls(Duration.ofSeconds(1))){
            var service=new RiskSubmissionService(new SubmissionSources("{\"payment-service\":[\"developer1.payment.v1\"]}"),client,evidence,configs,acceptance,work,freeze,finalization,snapshots,reads,calls,Clock.systemUTC(),tx);
            assertEquals("COMPLETED",service.submit("payment-service",command()).status());assertEquals(sent,paths.size());verifyNoInteractions(work,evidence,configs,freeze,finalization);
        }
    }
    @SuppressWarnings("unchecked") private void receive(HttpExchange exchange)throws IOException {
        try(exchange){paths.add(exchange.getRequestURI().toString());
            try{var auth=exchange.getRequestHeaders().get("Authorization");if(auth==null||auth.size()!=1||!auth.getFirst().startsWith("Bearer "))throw new IllegalArgumentException();
                var principal=verifier.authenticate(auth.getFirst().substring(7));
                if(principal.getAuthorities().stream().noneMatch(a->a.getAuthority().equals("SERVICE_PERMISSION_"+FactsProviderProtocol.PERMISSION))){exchange.sendResponseHeaders(403,-1);return;}
            }catch(Exception denied){exchange.sendResponseHeaders(401,-1);return;}
            if(!"POST".equals(exchange.getRequestMethod())||!FactsProviderProtocol.PATH.equals(exchange.getRequestURI().toString())){exchange.sendResponseHeaders(404,-1);return;}
            var input=exchange.getRequestBody().readNBytes(32769);if(input.length>32768){exchange.sendResponseHeaders(413,-1);return;}
            var request=JSON.readValue(input,Map.class);requests.add(request);
            var result=response(command(),CUTOFF);String mode=scenario.get();
            switch(mode){
                case "fail"->change(result,"validation.outcome","FAIL");case "reference"->change(result,"validation.reference","other");case "revision"->change(result,"validation.revision","18");
                case "payment"->result.put("paymentId","2");case "identity"->change(result,"submission.key","different");case "provider"->change(result,"provenance.serviceId","impostor");
                case "certification"->result.remove("certification");case "currency"->change(result,"payment.currencyCode","USD");case "timestamp"->change(result,"payment.submittedAt","2026-10-15T10:20:00");
                case "retry"->result.put("currentAndRetryIdentities",List.of(map("source","other","key","different")));case "hash-only"->{result.remove("certification");result.put("hash","a".repeat(64));}
            }
            byte[] payload=bytes(result);
            if(mode.equals("malformed"))payload="not-json".getBytes(StandardCharsets.UTF_8);
            if(mode.equals("duplicate"))payload=new String(payload,StandardCharsets.UTF_8).replace("\"outcome\":\"PASS\"","\"outcome\":\"PASS\",\"outcome\":\"FAIL\"").getBytes(StandardCharsets.UTF_8);
            if(mode.equals("utf8"))payload=new byte[]{'{','"',(byte)0xff,'"',':','1','}'};
            if(mode.equals("redirect")){exchange.getResponseHeaders().set("Location",origin()+"/arbitrary-callback");exchange.sendResponseHeaders(307,-1);return;}
            if(mode.equals("header-timeout")){stalled.countDown();awaitRelease();}
            exchange.getResponseHeaders().set("Content-Type",mode.equals("content-type")?"text/html":"application/json; charset=UTF-8");
            if(mode.equals("encoding"))exchange.getResponseHeaders().set("Content-Encoding","gzip");
            if(mode.equals("declared-size")||mode.equals("chunked-size")){var large=Arrays.copyOf(payload,65537);Arrays.fill(large,payload.length,large.length,(byte)' ');exchange.sendResponseHeaders(200,mode.equals("declared-size")?large.length:0);exchange.getResponseBody().write(large);return;}
            if(mode.equals("body-timeout")){exchange.sendResponseHeaders(200,0);exchange.getResponseBody().write('{');exchange.getResponseBody().flush();stalled.countDown();awaitRelease();return;}
            exchange.sendResponseHeaders(200,payload.length);exchange.getResponseBody().write(payload);
        }catch(IOException cancelled){/* Deliberately no token, payload or raw transport diagnostics. */}
    }
    void awaitRelease(){try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}}
}
