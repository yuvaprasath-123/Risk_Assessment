package com.ofss.risk.upstream;

import com.ofss.risk.facts.FactsHttpSettings;
import com.ofss.risk.submission.*;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class EvidenceHttpSettingsTest {
    FactsHttpSettings transport(){var t=Duration.ofSeconds(1);return new FactsHttpSettings(URI.create("https://provider.test"),"provider",t,t,t,65536);}
    @Test void disabledDoesNotInitializeTransportOrUseDemoData(){var bean=UpstreamEvidenceHttpAdapter.configured(new MockEnvironment());assertFalse(bean instanceof UpstreamEvidenceHttpAdapter);assertEquals(SubmissionFailure.Code.ADAPTER_UNAVAILABLE,assertThrows(SubmissionFailure.class,()->bean.retrieve(null,null)).code());}
    @Test void missingEnabledSettingsFailSanitized(){var e=assertThrows(IllegalStateException.class,()->UpstreamEvidenceHttpAdapter.configured(new MockEnvironment().withProperty("risk-evidence-client.enabled","true")));assertEquals(EvidenceHttpSettings.invalid().getMessage(),e.getMessage());assertNull(e.getCause());}
    @ParameterizedTest @ValueSource(ints={0,-1,1001,Integer.MAX_VALUE}) void pageSizeBounded(int size){assertThrows(IllegalStateException.class,()->new EvidenceHttpSettings(transport(),size,10,100));}
    @ParameterizedTest @ValueSource(ints={0,-1,101,Integer.MAX_VALUE}) void pagesBounded(int size){assertThrows(IllegalStateException.class,()->new EvidenceHttpSettings(transport(),2,size,100));}
    @ParameterizedTest @ValueSource(ints={0,-1,9001,Integer.MAX_VALUE}) void recordsBounded(int size){assertThrows(IllegalStateException.class,()->new EvidenceHttpSettings(transport(),2,10,size));}
}
