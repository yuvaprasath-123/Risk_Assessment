package com.ofss.risk.configuration;

import com.ofss.internal.*;
import com.ofss.internal.verification.EphemeralCredentials;
import com.ofss.risk.RiskAssessmentApplication;
import com.ofss.risk.submission.*;
import com.ofss.risk.submission.SubmissionAdapters.*;
import com.sun.net.httpserver.*;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;
import static com.ofss.risk.configuration.ConfigurationHttpFixtures.*;

/** Real native JDK client + temporary IPv4 HTTP listener. No application service, Oracle or mocked transport. */
class NativeConfigurationHttpContractTest {
    static final EphemeralCredentials KEYS=new EphemeralCredentials();
    enum Mode { NORMAL, REDIRECT, CORRUPT, MALFORMED, DECLARED_OVERSIZE, CHUNKED_OVERSIZE, HEADER_TIMEOUT, BODY_TIMEOUT }
    HttpServer server;ExecutorService workers;BusinessConfigurationHttpAdapter client;
    final AtomicReference<Mode> mode=new AtomicReference<>(Mode.NORMAL);
    final AtomicReference<String> activeVersion=new AtomicReference<>("9007199254740993");
    final List<String> paths=new CopyOnWriteArrayList<>();
    final List<String> methods=new CopyOnWriteArrayList<>();
    final CountDownLatch stalled=new CountDownLatch(1),release=new CountDownLatch(1);
    final AtomicBoolean missingPinned=new AtomicBoolean();
    final AtomicBoolean exactAmounts=new AtomicBoolean();
    final InternalServiceVerifier verifier=new InternalServiceVerifier(true,EphemeralCredentials.ISSUER,"configuration-native",KEYS.publicKeys(),
        "{\"risk-assessment-service\":[\"CONFIGURATION_ACTIVE_READ\",\"CONFIGURATION_VERSION_READ\"]}",ServicePermissions.CONFIGURATION);
    @BeforeEach void start()throws IOException{
        // Failure is a failure, never a skipped/assumed test or mocked fallback. Only ephemeral loopback ports are used.
        server=HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0),0);
        workers=Executors.newFixedThreadPool(4,r->new Thread(r,"native-configuration-contract"));
        server.setExecutor(workers);server.createContext("/",this::receive);server.start();
    }
    @AfterEach void stop()throws Exception{
        release.countDown();
        try{if(client!=null)client.close();}finally{
            if(server!=null)server.stop(0);
            if(workers!=null){workers.shutdownNow();assertTrue(workers.awaitTermination(5,TimeUnit.SECONDS),"Temporary receiver workers did not terminate");}
        }
    }
    String origin(){return "http://127.0.0.1:"+server.getAddress().getPort();}
    String token(String permission){return KEYS.service("configuration-native","risk-assessment-service",List.of(permission));}
    String both(){return KEYS.service("configuration-native","risk-assessment-service",List.of(ServicePermissions.CONFIG_ACTIVE,ServicePermissions.CONFIG_VERSION));}
    MockEnvironment settings(String credential,String timeout){return new MockEnvironment().withProperty("risk-configuration-client.enabled","true")
        .withProperty("risk-configuration-client.base-url",origin()).withProperty("risk-configuration-client.connect-timeout","PT1S")
        .withProperty("risk-configuration-client.request-timeout",timeout).withProperty("risk-configuration-client.max-response-bytes","65536")
        .withProperty("risk-configuration-client.service-token",credential);}
    BusinessConfigurationHttpAdapter configure(MockEnvironment env){client=(BusinessConfigurationHttpAdapter)BusinessConfigurationHttpAdapter.configured(env);return client;}
    Deadline deadline(){return new Deadline(System.nanoTime()+Duration.ofSeconds(5).toNanos());}
    void fails(String version){var e=assertThrows(SubmissionFailure.class,()->client.resolve(version,deadline()));assertEquals(SubmissionFailure.Code.DEPENDENCY_UNAVAILABLE,e.code());assertNull(e.getCause());}
    @Test void actualProductionBeanReadsActiveAndPinnedWithExactMapperAndClosesNativeClient(){
        var env=settings(both(),"PT3S");var held=new AtomicReference<BusinessConfigurationHttpAdapter>();
        new WebApplicationContextRunner().withUserConfiguration(RiskAssessmentApplication.class).withInitializer(c->c.getEnvironment().getPropertySources().addFirst(env.getPropertySources().get("mockProperties")))
            .withPropertyValues("RISK_DB_URL=jdbc:oracle:thin:@//invalid.test:1521/FREEPDB1","RISK_DB_USERNAME=TEST_ONLY","RISK_DB_PASSWORD=TEST_ONLY",
                "security.jwt.secret="+KEYS.userSecret(),"risk-submission.enabled=true","risk-submission.sources={\"payment-service\":[\"developer1\"]}",
                "risk-submission.dependency-budget=PT5S","RISK_LEASE_DURATION=PT15S","spring.sql.init.mode=never")
            .run(c->{assertNull(c.getStartupFailure());var adapter=(BusinessConfigurationHttpAdapter)c.getBean(PublishedConfiguration.class);held.set(adapter);
                assertNull(c.getBean(HikariDataSource.class).getHikariPoolMXBean(),"No database connection may be opened");
                var active=adapter.resolve(null,deadline());var pinned=adapter.resolve(active.versionId(),deadline());
                assertEquals(active,pinned);assertEquals("9",active.rowVersion());assertNotEquals("9007199254740993",active.rowVersion());
                var mapped=new PublishedConfigurationMapping().map(active);assertEquals(7,mapped.rules().size());assertEquals(3,mapped.bands().size());
                assertEquals(new BigDecimal("123.00"),mapped.approvalBoundaries().get(2));assertEquals(hash(content(active.versionId())),mapped.contentHash());
                assertTrue(active.canonicalConfiguration().contains("परीक्षण"));assertTrue(active.canonicalConfiguration().contains("999999999999"));
            });
        assertEquals(List.of("/internal/v1/configuration/active","/internal/v1/configuration/versions/9007199254740993"),paths);
        assertEquals(List.of("GET","GET"),methods);int received=paths.size();
        assertThrows(SubmissionFailure.class,()->held.get().resolve(null,deadline()));assertEquals(received,paths.size(),"Closed Spring-owned client must not issue another request");
    }
    @Test void pinnedVersionAndHashNeverChangeWhenActivePointerMovesOrPinnedReadFails(){
        configure(settings(both(),"PT3S"));var original=client.resolve(null,deadline());activeVersion.set("8");
        assertEquals(original,client.resolve(original.versionId(),deadline()));assertEquals("8",client.resolve(null,deadline()).versionId());
        int before=paths.size();missingPinned.set(true);fails(original.versionId());assertEquals(before+1,paths.size());assertTrue(paths.getLast().endsWith("/"+original.versionId()));
    }
    @Test void realHeaderCarriesOnlyExternalServiceCredentialAndSupportsRotation(){
        var env=settings(token(ServicePermissions.CONFIG_VERSION),"PT3S");configure(env);fails(null);
        assertNotNull(client.resolve("9007199254740993",deadline()));
        env.setProperty("risk-configuration-client.service-token",KEYS.service("configuration-native","risk-assessment-service",List.of(ServicePermissions.CONFIG_ACTIVE),"next"));
        assertNotNull(client.resolve(null,deadline()));
        env.setProperty("risk-configuration-client.service-token",KEYS.user());fails(null);
        env.setProperty("risk-configuration-client.service-token",KEYS.service("wrong-audience","risk-assessment-service",List.of(ServicePermissions.CONFIG_ACTIVE)));fails(null);
    }
    @Test void nativeJsonAndMapperPreserveExactMoneyAndMultiplier(){
        exactAmounts.set(true);configure(settings(both(),"PT3S"));var mapped=new PublishedConfigurationMapping().map(client.resolve(null,deadline()));
        assertEquals(new BigDecimal("9007199254740.91"),mapped.approvalBoundaries().get(2));assertEquals(new BigDecimal("9999999999999.99"),mapped.approvalBoundaries().get(3));
        var parameters=(com.ofss.risk.contract.RuleParameters.Amount)mapped.rules().stream().filter(r->r.code()==com.ofss.risk.contract.RuleCode.UNUSUAL_AMOUNT).findFirst().orElseThrow().parameters();
        assertEquals(new BigDecimal("1.0000000000000001"),parameters.meanMultiplier());
    }
    @ParameterizedTest @ValueSource(strings={"REDIRECT","CORRUPT","MALFORMED","DECLARED_OVERSIZE","CHUNKED_OVERSIZE"})
    void unsafeRealResponsesFailClosedWithoutFallback(String scenario){configure(settings(both(),"PT3S"));mode.set(Mode.valueOf(scenario));fails(null);assertEquals(List.of("/internal/v1/configuration/active"),paths);}
    @ParameterizedTest @ValueSource(strings={"HEADER_TIMEOUT","BODY_TIMEOUT"})
    void nativeRequestAndWholeBodyStallsAreBoundedAndCancelled(String scenario)throws Exception{
        configure(settings(both(),"PT0.4S"));mode.set(Mode.valueOf(scenario));
        assertTimeoutPreemptively(Duration.ofSeconds(3),()->fails(null));assertTrue(stalled.await(1,TimeUnit.SECONDS),"Request must reach intended native timeout point");
        release.countDown();mode.set(Mode.NORMAL);assertNotNull(client.resolve("9007199254740993",deadline()),"Client remains usable after cancellation");
    }
    private void receive(HttpExchange exchange)throws IOException{
        try(exchange){String path=exchange.getRequestURI().getPath();paths.add(path);methods.add(exchange.getRequestMethod());
            String permission=path.equals("/internal/v1/configuration/active")?ServicePermissions.CONFIG_ACTIVE:ServicePermissions.CONFIG_VERSION;
            try{
                var values=exchange.getRequestHeaders().get("Authorization");if(values==null||values.size()!=1||!values.getFirst().startsWith("Bearer "))throw new IllegalArgumentException();
                var auth=verifier.authenticate(values.getFirst().substring(7));
                if(auth.getAuthorities().stream().noneMatch(a->a.getAuthority().equals(ServicePermissions.authority(permission)))){exchange.sendResponseHeaders(403,-1);return;}
            }catch(Exception invalid){exchange.sendResponseHeaders(401,-1);return;}
            boolean active=path.equals("/internal/v1/configuration/active");
            if(!"GET".equals(exchange.getRequestMethod())||!active&&!path.matches("/internal/v1/configuration/versions/[1-9][0-9]{0,18}")||missingPinned.get()&&!active){exchange.sendResponseHeaders(404,-1);return;}
            Mode selected=mode.get();String version=active?activeVersion.get():path.substring(path.lastIndexOf('/')+1);
            var configuration=content(version);
            if(exactAmounts.get()){
                var tiers=rows(configuration,"approvalThresholds");tiers.get(0).put("maxAmountExclusive","9007199254740.91");tiers.get(1).put("minAmountInclusive","9007199254740.91");
                tiers.get(1).put("maxAmountExclusive","9999999999999.99");tiers.get(2).put("minAmountInclusive","9999999999999.99");
                @SuppressWarnings("unchecked") var p=(Map<String,Object>)rows(configuration,"riskRules").stream().filter(r->r.get("ruleCode").equals("UNUSUAL_AMOUNT")).findFirst().orElseThrow().get("parameters");
                p.put("meanMultiplier","1.0000000000000001");
            }
            var body=envelope(configuration,active);byte[] payload=bytes(body);
            if(selected==Mode.CORRUPT){body.put("contentHash","0".repeat(64));payload=bytes(body);}
            if(selected==Mode.MALFORMED)payload="{\"configuration\":null,\"configuration\":{}}".getBytes(StandardCharsets.UTF_8);
            if(selected==Mode.REDIRECT){exchange.getResponseHeaders().set("Location",origin()+"/redirect-target");exchange.sendResponseHeaders(307,-1);return;}
            if(selected==Mode.HEADER_TIMEOUT){stalled.countDown();awaitRelease();}
            exchange.getResponseHeaders().set("Content-Type","application/json; charset=UTF-8");
            if(selected==Mode.DECLARED_OVERSIZE||selected==Mode.CHUNKED_OVERSIZE){
                // Otherwise-valid document + legal JSON whitespace: failure must be caused by the byte bound, not invalid JSON.
                var oversized=Arrays.copyOf(payload,65537);Arrays.fill(oversized,payload.length,oversized.length,(byte)' ');
                exchange.sendResponseHeaders(200,selected==Mode.DECLARED_OVERSIZE?oversized.length:0);exchange.getResponseBody().write(oversized);return;
            }
            if(selected==Mode.BODY_TIMEOUT){exchange.sendResponseHeaders(200,0);exchange.getResponseBody().write('{');exchange.getResponseBody().flush();stalled.countDown();awaitRelease();return;}
            exchange.sendResponseHeaders(200,payload.length);exchange.getResponseBody().write(payload);
        }catch(IOException expectedCancellation){/* No headers, tokens, payloads or raw exceptions in diagnostics. */}
    }
    private void awaitRelease(){try{release.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
}
