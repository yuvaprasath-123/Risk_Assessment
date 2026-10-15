package com.ofss.risk.facts;

import com.ofss.risk.submission.*;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class FactsHttpSettingsTest {
    @ParameterizedTest @ValueSource(strings={"https://provider.test/path","https://user:credential@provider.test","https://provider.test?callback=http://other.test","https://provider.test#fragment","http://provider.test","ftp://provider.test","https://provider.test:0","https://provider.test:65536"})
    void arbitraryUrlsAndUnprotectedRemoteOriginsAreRejected(String url){
        assertThrows(IllegalStateException.class,()->new FactsHttpSettings(URI.create(url),"provider",Duration.ofSeconds(1),Duration.ofSeconds(1),Duration.ofSeconds(1),65536));
    }
    @ParameterizedTest @ValueSource(strings={"PT0S","PT0.000000001S","PT31S","PT-1S"})
    void everyPhaseRequiresABoundedPositiveTimeout(String duration){
        var invalid=Duration.parse(duration);var valid=Duration.ofSeconds(1);var origin=URI.create("https://provider.test");
        assertThrows(IllegalStateException.class,()->new FactsHttpSettings(origin,"provider",invalid,valid,valid,65536));
        assertThrows(IllegalStateException.class,()->new FactsHttpSettings(origin,"provider",valid,invalid,valid,65536));
        assertThrows(IllegalStateException.class,()->new FactsHttpSettings(origin,"provider",valid,valid,invalid,65536));
    }
    @Test void disabledNeverConstructsAClientOrSelectsDemonstrationFacts(){
        var adapter=AuthoritativeFactsHttpAdapter.configured(new MockEnvironment().withProperty("risk-facts-client.base-url","invalid"));
        assertFalse(adapter instanceof AuthoritativeFactsHttpAdapter);
        assertEquals(SubmissionFailure.Code.ADAPTER_UNAVAILABLE,assertThrows(SubmissionFailure.class,()->adapter.verify(null,null,null)).code());
    }
    @Test void enabledMissingOrUnsafeConfigurationFailsClosedWithoutCredentialValues(){
        var exception=assertThrows(IllegalStateException.class,()->AuthoritativeFactsHttpAdapter.configured(new MockEnvironment().withProperty("risk-facts-client.enabled","true")));
        assertEquals("Complete valid external Facts HTTP adapter settings are required",exception.getMessage());assertNull(exception.getCause());
    }
    @Test void providerIdentityCapacityAndSafeOriginsAreValidated(){
        var d=Duration.ofSeconds(1);var url=URI.create("https://provider.test");
        for(String id:new String[]{"","UPPER","provider/other"," ","a".repeat(65)})assertThrows(IllegalStateException.class,()->new FactsHttpSettings(url,id,d,d,d,65536));
        for(int size:new int[]{0,-1,4194305})assertThrows(IllegalStateException.class,()->new FactsHttpSettings(url,"provider",d,d,d,size));
        assertDoesNotThrow(()->new FactsHttpSettings(URI.create("http://127.0.0.1:8089"),"provider",d,d,d,65536));
        assertDoesNotThrow(()->new FactsHttpSettings(url,"provider",d,d,d,4194304));
    }
}
