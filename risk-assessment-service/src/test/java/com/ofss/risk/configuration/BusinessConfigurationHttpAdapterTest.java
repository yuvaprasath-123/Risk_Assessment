package com.ofss.risk.configuration;

import com.ofss.risk.submission.*;
import com.ofss.risk.submission.SubmissionAdapters.Deadline;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class BusinessConfigurationHttpAdapterTest {
    ConfigurationHttpHarness http=new ConfigurationHttpHarness();
    AtomicReference<String> token=new AtomicReference<>("header.fixture.signature");
    ConfigurationHttpSettings settings=new ConfigurationHttpSettings(URI.create("https://configuration.test"),Duration.ofMillis(50),Duration.ofMillis(300),65536);
    BusinessConfigurationHttpAdapter adapter(){return new BusinessConfigurationHttpAdapter(settings,token::get,http.client);}
    static Deadline deadline(){return new Deadline(System.nanoTime()+Duration.ofSeconds(2).toNanos());}
    void rejected(){var e=assertThrows(SubmissionFailure.class,()->adapter().resolve(null,deadline()));assertEquals(SubmissionFailure.Code.DEPENDENCY_UNAVAILABLE,e.code());assertNull(e.getCause());}
    @Test void activeAndPinnedSelectionAuthHeadersAndExactMapping(){
        var a=adapter().resolve(null,deadline());assertEquals("9",a.rowVersion());assertEquals("9007199254740993",a.versionId());
        http.body=ConfigurationHttpFixtures.response(false);adapter().resolve("9007199254740993",deadline());
        assertEquals(List.of("/internal/v1/configuration/active","/internal/v1/configuration/versions/9007199254740993"),http.requests.stream().map(r->r.uri().getPath()).toList());
        for(var req:http.requests){assertEquals("GET",req.method());assertEquals("Bearer "+token.get(),req.headers().firstValue("Authorization").orElseThrow());
            assertEquals("application/json",req.headers().firstValue("Accept").orElseThrow());assertEquals("identity",req.headers().firstValue("Accept-Encoding").orElseThrow());
            assertTrue(req.timeout().orElseThrow().compareTo(settings.requestTimeout())<=0);assertTrue(req.bodyPublisher().isEmpty());}
    }
    @Test void externallyRotatedTokenReadPerCallWithoutCaching(){adapter().resolve(null,deadline());token.set("header.rotated.signature");adapter().resolve(null,deadline());assertNotEquals(http.requests.getFirst().headers().firstValue("Authorization"),http.requests.getLast().headers().firstValue("Authorization"));}
    @ParameterizedTest @ValueSource(ints={204,301,302,307,400,401,403,404,429,500,503})
    void nonSuccessNeverFallsBackOrLeaksPayload(int status){http.status=status;http.body="sensitive rejected body".getBytes(java.nio.charset.StandardCharsets.UTF_8);rejected();assertEquals(1,http.requests.size());assertTrue(http.cancelled);}
    @Test void pinnedResponseCannotReturnDifferentVersionOrActiveShape(){http.body=ConfigurationHttpFixtures.response(false);assertThrows(SubmissionFailure.class,()->adapter().resolve("8",deadline()));assertEquals(1,http.requests.size());
        http.body=ConfigurationHttpFixtures.response(true);assertThrows(SubmissionFailure.class,()->adapter().resolve("9007199254740993",deadline()));assertEquals(2,http.requests.size());}
    @ParameterizedTest @ValueSource(strings={"0","01","-1","9223372036854775808","1?active=true","../active"})
    void malformedPinnedIdsNeverReachNetwork(String id){assertThrows(SubmissionFailure.class,()->adapter().resolve(id,deadline()));assertTrue(http.requests.isEmpty());}
    @ParameterizedTest @ValueSource(strings={"","Bearer header.fixture.signature","header\n.fixture.signature","header.fixture","header.fixture.signature.extra"})
    void missingOrMalformedExternalCredentialsNeverBecomeUserImpersonation(String bad){token.set(bad);var e=assertThrows(SubmissionFailure.class,()->adapter().resolve(null,deadline()));assertEquals(SubmissionFailure.Code.ADAPTER_UNAVAILABLE,e.code());assertTrue(http.requests.isEmpty());assertNull(e.getCause());}
    @Test void rejectsNetworkInsideTransaction(){TransactionSynchronizationManager.setActualTransactionActive(true);try{rejected();assertTrue(http.requests.isEmpty());}finally{TransactionSynchronizationManager.setActualTransactionActive(false);}}
    @Test void expiredDeadlineRejectsBeforeNetwork(){assertThrows(SubmissionFailure.class,()->adapter().resolve(null,new Deadline(System.nanoTime()-1)));assertTrue(http.requests.isEmpty());}
    @Test void wholeBodyStallIsBoundedAndCancelled(){settings=new ConfigurationHttpSettings(settings.origin(),settings.connectTimeout(),Duration.ofMillis(40),65536);http.stalled=true;
        assertTimeoutPreemptively(Duration.ofSeconds(2),this::rejected);assertTrue(http.last.isCancelled());}
    @Test void callerBudgetFurtherBoundsRequestTimeout(){adapter().resolve(null,new Deadline(System.nanoTime()+Duration.ofMillis(150).toNanos()));assertTrue(http.requests.getFirst().timeout().orElseThrow().compareTo(Duration.ofMillis(150))<=0);}
    @SuppressWarnings({"unchecked","rawtypes"}) @Test void transportErrorsRemainSanitized(){
        for(Exception cause:List.of(new java.io.IOException("sensitive upstream context"),new HttpConnectTimeoutException("connect"),new HttpTimeoutException("request"))){
            reset(http.client);when(http.client.sendAsync(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(CompletableFuture.failedFuture(cause));rejected();
        }
    }
    @Test void interruptPreservesFlagAndCancelsOutstandingCall(){http.stalled=true;Thread.currentThread().interrupt();try{rejected();assertTrue(Thread.currentThread().isInterrupted());assertTrue(http.last.isCancelled());}finally{Thread.interrupted();}}
    @Test void declaredAndActualBodyLimitsEnforced(){
        http.headers.put("Content-Length",List.of("65537"));rejected();assertTrue(http.cancelled);
        http.headers.remove("Content-Length");http.body=new byte[65537];rejected();assertTrue(http.cancelled);
    }
    @Test void exactSizeLimitAndChunkedResponseAccepted(){http.headers.remove("Content-Length");settings=new ConfigurationHttpSettings(settings.origin(),settings.connectTimeout(),settings.requestTimeout(),http.body.length);
        assertNotNull(adapter().resolve(null,deadline()));assertFalse(http.cancelled);http.headers.put("Content-Length",List.of(Integer.toString(http.body.length)));assertNotNull(adapter().resolve(null,deadline()));}
    @ParameterizedTest @ValueSource(strings={"text/html","application/json; charset=ISO-8859-1","application/problem+json","*/*","broken"})
    void incorrectContentTypesRejected(String value){http.headers.put("Content-Type",List.of(value));rejected();assertTrue(http.cancelled);}
    @Test void malformedDuplicateHeadersCompressionAndTruncationRejected(){
        http.headers.put("Content-Type",List.of("application/json","application/json"));rejected();
        http.headers.put("Content-Type",List.of("application/json"));http.headers.put("Content-Encoding",List.of("gzip"));rejected();http.headers.remove("Content-Encoding");
        for(var value:List.of(List.of("-1"),List.of("invalid"),List.of("1","2"),List.of("9223372036854775808"),List.of("65536"))){http.headers.put("Content-Length",value);rejected();}
        http.headers.remove("Content-Length");http.headers.remove("Content-Type");rejected();
    }
    @Test void transportHasConnectDeadlineNoRedirectsAndNoAmbientCredentials(){
        var builder=mock(HttpClient.Builder.class,RETURNS_SELF);when(builder.build()).thenReturn(http.client);
        try(var construction=mockStatic(HttpClient.class)){construction.when(HttpClient::newBuilder).thenReturn(builder);
            assertSame(http.client,BusinessConfigurationHttpAdapter.transport(settings));verify(builder).connectTimeout(settings.connectTimeout());verify(builder).followRedirects(HttpClient.Redirect.NEVER);
            verify(builder).build();verifyNoMoreInteractions(builder);
        }
    }
}
