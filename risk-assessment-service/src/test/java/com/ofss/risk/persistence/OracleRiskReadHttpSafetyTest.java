package com.ofss.risk.persistence;

import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Offline transport mocks only. No issuer token creation, socket, credential lookup or Oracle connection. */
@SuppressWarnings({"unchecked","rawtypes"})
class OracleRiskReadHttpSafetyTest {
    final HttpClient client=mock(HttpClient.class); final AtomicBoolean uncertain=new AtomicBoolean();
    @BeforeEach void setup() {when(client.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);}
    ExternalRiskReadHttp transport() {return new ExternalRiskReadHttp(client,ExternalRiskReadHttp.base(null),()->uncertain.set(true));}
    HttpResponse<String> reply(String body,String type) {
        var response=(HttpResponse<String>)mock(HttpResponse.class); when(response.statusCode()).thenReturn(200); when(response.body()).thenReturn(body);
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of("Content-Type",List.of(type)),(a,b)->true)); return response;
    }
    @ParameterizedTest @ValueSource(strings={"http://example.com:8087","http://localhost.attacker:8087","http://127.0.0.1","file:///tmp/file",
        "http://user:withheld@127.0.0.1:8087","http://127.0.0.1:8087/other","http://127.0.0.1:8087?token=withheld","http://127.0.0.1:8087/#withheld"})
    void unsafeOrAmbiguousTargetsRejectBeforeHttp(String url) {assertThrows(IllegalArgumentException.class,()->ExternalRiskReadHttp.base(url));verify(client,never()).followRedirects();}
    @ParameterizedTest @ValueSource(strings={"http://127.0.0.1:8087","http://localhost:9090/","http://[::1]:8087"}) void explicitLoopbackTargetsAreAllowed(String url) {assertNotNull(ExternalRiskReadHttp.base(url));}
    @ParameterizedTest @NullSource @ValueSource(strings={"","Bearer a.b.c","a.b","a.b.c\n","withheld-value"}) void tokensMustBeExternalRawCompactValuesWithoutEchoingInput(String token) {
        var failure=assertThrows(IllegalArgumentException.class,()->ExternalRiskReadHttp.requiredToken(token)); assertFalse(failure.getMessage().contains("withheld-value"));
    }
    @Test void optionalWrongRoleTokenCanBeAbsentButIsNeverSynthesized() {assertNull(ExternalRiskReadHttp.optionalToken(null));assertNull(ExternalRiskReadHttp.optionalToken(""));assertEquals("a.b.c",ExternalRiskReadHttp.optionalToken("a.b.c"));}
    @Test void evenCallerProvidedClientsMustRejectRedirects() {when(client.followRedirects()).thenReturn(HttpClient.Redirect.ALWAYS);assertThrows(IllegalArgumentException.class,this::transport);}
    @ParameterizedTest @ValueSource(strings={"//attacker/api/v1/risk-assessments/1","https://attacker","/api/v1/risk-assessments/../private","/other/1"})
    void pathCannotOverrideOriginOrReadUnrelatedEndpoints(String path) throws Exception {assertThrows(IllegalArgumentException.class,()->transport().get(path,"a.b.c","Bearer"));verify(client,never()).send(any(),any(HttpResponse.BodyHandler.class));}
    @Test void suppliedOpaqueTokenIsForwardedExactlyAndOnlyGetIsSent() throws Exception {
        var response=reply("{\"assessmentId\":\"9007199254740993\"}","application/json;charset=UTF-8");
        when(client.send(any(),any(HttpResponse.BodyHandler.class))).thenReturn(response);
        var value=transport().get(ExternalRiskReadHttp.ROOT+"9007199254740993","a.b.c","bearer"); assertEquals("9007199254740993",value.body().get("assessmentId"));
        var request=org.mockito.ArgumentCaptor.forClass(HttpRequest.class); verify(client).send(request.capture(),any(HttpResponse.BodyHandler.class));
        assertEquals("GET",request.getValue().method()); assertEquals("bearer a.b.c",request.getValue().headers().firstValue("Authorization").orElseThrow());
        assertEquals("127.0.0.1",request.getValue().uri().getHost()); assertEquals(8087,request.getValue().uri().getPort()); assertFalse(uncertain.get());
    }
    @Test void missingCredentialsSendNoAuthorizationHeader() throws Exception {
        var response=reply("{}","application/json"); when(client.send(any(),any(HttpResponse.BodyHandler.class))).thenReturn(response); transport().get(ExternalRiskReadHttp.ROOT+"1",null,"Bearer");
        var request=org.mockito.ArgumentCaptor.forClass(HttpRequest.class); verify(client).send(request.capture(),any(HttpResponse.BodyHandler.class));assertTrue(request.getValue().headers().firstValue("Authorization").isEmpty());
    }
    @Test void timeoutDefersCleanupAndDoesNotReflectExceptionDetails() throws Exception {
        when(client.send(any(),any(HttpResponse.BodyHandler.class))).thenThrow(new IOException("withheld payload/token"));
        var e=assertThrows(AssertionError.class,()->transport().get(ExternalRiskReadHttp.ROOT+"1","a.b.c","Bearer")); assertTrue(uncertain.get());assertNull(e.getCause());assertFalse(e.getMessage().contains("withheld payload"));
    }
    @ParameterizedTest @ValueSource(strings={"<html>withheld-payload</html>","[]","not-json"}) void unexpectedResponseBodiesAreWithheld(String body) throws Exception {
        var response=reply(body,body.startsWith("<")?"text/html":"application/json"); when(client.send(any(),any(HttpResponse.BodyHandler.class))).thenReturn(response);
        var e=assertThrows(AssertionError.class,()->transport().get(ExternalRiskReadHttp.ROOT+"1",null,"Bearer"));assertNull(e.getCause());assertFalse(e.getMessage().contains(body));
    }
    @Test void exactNumbersNeverAcceptFloatingPointRounding() {
        var invalid=new HashMap<String,Object>(Map.of("rawScore",135.9,"finalScore",100,"maxFinalScore",100,"band","HIGH"));
        assertThrows(RuntimeException.class,()->OracleRiskReadHttpTest.score(invalid));
    }
    @Test void httpCleanerRefusesOtherReviewedMilestoneKeysBeforeAnyMutation() {
        var jdbc=mock(JdbcTemplate.class); var manager=mock(PlatformTransactionManager.class);when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        String namespace=OracleFinalizationFixtures.PREFIX+"12345678-1234-1234-1234-123456789abc";
        when(jdbc.query(eq(OracleFinalizationFixtureCleaner.LOCK),any(RowMapper.class),eq(namespace))).thenReturn(List.of(Map.of("SUBMISSION_KEY","nanoseconds")));
        assertThrows(IllegalStateException.class,()->new OracleFinalizationFixtureCleaner(jdbc,manager).clean(namespace,OracleRiskReadHttpTest.KEYS));
        verify(jdbc,never()).update(anyString(),any(Object[].class));verify(manager).rollback(any());
    }
    @Test void liveAndRecoveryChecksRequireExplicitOptInsAndConfirmedRequestTermination() {
        assertEquals("RISK_VERIFY_ORACLE_HTTP_WRITES",OracleRiskReadHttpTest.class.getAnnotation(EnabledIfEnvironmentVariable.class).named());
        var flags=Arrays.stream(OracleRiskReadHttpCleanupTest.class.getAnnotationsByType(EnabledIfEnvironmentVariable.class)).map(EnabledIfEnvironmentVariable::named).collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("RISK_VERIFY_ORACLE_HTTP_WRITES","RISK_ORACLE_HTTP_CLEANUP_ONLY","RISK_HTTP_TEST_REQUESTS_STOPPED"),flags);
    }
}
