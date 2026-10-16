package com.ofss.risk.configuration;

import com.ofss.risk.submission.*;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConfigurationHttpSettingsTest {
    @ParameterizedTest @ValueSource(strings={"http://remote.test","ftp://configuration.test","https://user:password@configuration.test","https://configuration.test/path","https://configuration.test?token=redacted","https://configuration.test/#fragment","https://configuration.test:0","https://configuration.test:65536"})
    void rejectsUnsafeOriginsWithoutEchoingInput(String uri){var e=assertThrows(IllegalStateException.class,()->new ConfigurationHttpSettings(URI.create(uri),Duration.ofSeconds(1),Duration.ofSeconds(2),65536));assertNull(e.getCause());assertFalse(e.getMessage().contains(uri));}
    @ParameterizedTest @ValueSource(strings={"https://configuration.test","https://configuration.test/","http://localhost:8086","http://127.0.0.1:8086","http://[::1]:8086"})
    void explicitTrustedOriginsAccepted(String uri){assertNotNull(new ConfigurationHttpSettings(URI.create(uri),Duration.ofMillis(1),Duration.ofSeconds(30),4*1024*1024));}
    @Test void requiresFiniteExplicitTimeoutsAndSize(){
        for(var d:new Duration[]{null,Duration.ZERO,Duration.ofNanos(1),Duration.ofSeconds(31)}){
            assertThrows(IllegalStateException.class,()->new ConfigurationHttpSettings(URI.create("https://configuration.test"),d,Duration.ofSeconds(1),65536));
            assertThrows(IllegalStateException.class,()->new ConfigurationHttpSettings(URI.create("https://configuration.test"),Duration.ofSeconds(1),d,65536));
        }
        for(int size:new int[]{0,-1,4194305})assertThrows(IllegalStateException.class,()->new ConfigurationHttpSettings(URI.create("https://configuration.test"),Duration.ofSeconds(1),Duration.ofSeconds(1),size));
    }
    @Test void absentOrExplicitlyDisabledRuntimeConfigRetainsUnavailableAdapter(){for(var env:new MockEnvironment[]{new MockEnvironment(),new MockEnvironment().withProperty("risk-configuration-client.enabled","false").withProperty("risk-configuration-client.base-url","invalid")}){
        var adapter=BusinessConfigurationHttpAdapter.configured(env);assertEquals(SubmissionFailure.Code.ADAPTER_UNAVAILABLE,assertThrows(SubmissionFailure.class,()->adapter.resolve(null,BusinessConfigurationHttpAdapterTest.deadline())).code());}}
    static MockEnvironment configured(){return new MockEnvironment().withProperty("risk-configuration-client.enabled","true")
        .withProperty("risk-configuration-client.base-url","https://configuration.test").withProperty("risk-configuration-client.connect-timeout","PT1S")
        .withProperty("risk-configuration-client.request-timeout","PT2S").withProperty("risk-configuration-client.max-response-bytes","65536")
        .withProperty("risk-configuration-client.service-token","header.fixture.signature");}
    @ParameterizedTest @ValueSource(strings={"base-url","connect-timeout","request-timeout","max-response-bytes","service-token"})
    void enabledIncompleteConfigurationFailsStartup(String setting){var env=configured();env.setProperty("risk-configuration-client."+setting,"");var e=assertThrows(IllegalStateException.class,()->BusinessConfigurationHttpAdapter.configured(env));assertNull(e.getCause());assertFalse(e.getMessage().contains("header.fixture"));}
    @Test void configuredAdapterClosesWithoutOpeningAnyConnection()throws Exception{
        var client=mock(java.net.http.HttpClient.class);when(client.awaitTermination(Duration.ofSeconds(5))).thenReturn(true);
        var builder=mock(java.net.http.HttpClient.Builder.class,RETURNS_SELF);when(builder.build()).thenReturn(client);
        try(var construction=mockStatic(java.net.http.HttpClient.class)){construction.when(java.net.http.HttpClient::newBuilder).thenReturn(builder);
            var adapter=BusinessConfigurationHttpAdapter.configured(configured());assertInstanceOf(BusinessConfigurationHttpAdapter.class,adapter);((AutoCloseable)adapter).close();
            verify(client).shutdownNow();verify(client).awaitTermination(Duration.ofSeconds(5));
        }
    }
    @Test void nativeInitializationFailureIsDistinctFromInvalidSettingsAndSanitized(){
        try(var construction=mockStatic(java.net.http.HttpClient.class)){
            construction.when(java.net.http.HttpClient::newBuilder).thenThrow(new java.io.UncheckedIOException(new java.io.IOException("sensitive native context")));
            var e=assertThrows(IllegalStateException.class,()->BusinessConfigurationHttpAdapter.configured(configured()));
            assertEquals("Configuration HTTP transport initialization failed",e.getMessage());assertNull(e.getCause());
        }
    }
}
