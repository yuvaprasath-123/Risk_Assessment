package com.ofss.risk.http;

import com.ofss.risk.RiskAssessmentApplication;
import com.ofss.risk.submission.*;
import com.ofss.risk.submission.SubmissionAdapters.*;
import com.ofss.risk.persistence.*;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual opt-in bean path, no socket or database connection. */
class SubmissionWiringTest {
    WebApplicationContextRunner context(){return new WebApplicationContextRunner().withUserConfiguration(RiskAssessmentApplication.class)
        .withPropertyValues("RISK_DB_URL=jdbc:oracle:thin:@//invalid.test:1521/FREEPDB1","RISK_DB_USERNAME=TEST_ONLY","RISK_DB_PASSWORD=TEST_ONLY",
            "security.jwt.secret="+RiskAssessmentApiTest.random(),"spring.sql.init.mode=never");}
    WebApplicationContextRunner enabled(){return context().withPropertyValues("risk-submission.enabled=true","risk-submission.sources={\"payment-service\":[\"payment.v1\"]}",
        "risk-submission.dependency-budget=PT2S","RISK_LEASE_DURATION=PT15S");}
    @Test void disabledPreservesReadHostAndDoesNotWireWriteServices(){context().run(c->{assertNull(c.getStartupFailure());assertTrue(c.getBeansOfType(RiskSubmissionService.class).isEmpty());assertNotNull(c.getBean(AssessmentReadService.class));assertNull(c.getBean(HikariDataSource.class).getHikariPoolMXBean());});}
    @Test void productionAdaptersExplicitlyFailUnavailableWithoutCreatingMockData(){enabled().run(c->{
        assertNull(c.getStartupFailure());assertNotNull(c.getBean(RiskSubmissionService.class));assertNotNull(c.getBean(RiskWorkService.class));
        assertInstanceOf(ProductionEvidenceAdapter.class,c.getBean(Evidence.class));assertNotNull(c.getBean(PriorAssessmentHistoryReader.class));
        var d=c.getBean(DependencyCalls.class).deadline();assertEquals(SubmissionFailure.Code.ADAPTER_UNAVAILABLE,assertThrows(SubmissionFailure.class,()->c.getBean(Facts.class).verify(null,null,d)).code());
        assertThrows(SubmissionFailure.class,()->c.getBean(Evidence.class).retrieve(null,d));assertThrows(SubmissionFailure.class,()->c.getBean(PublishedConfiguration.class).resolve(null,d));
        assertNull(c.getBean(HikariDataSource.class).getHikariPoolMXBean());
    });}
    @Test void demoProfileDoesNotBypassRealPublishedConfiguration(){enabled().withPropertyValues("spring.profiles.active=risk-submission-demo").run(c->{
        assertNull(c.getStartupFailure());var command=new SubmissionCommand(SubmissionCommand.VERSION,"demo.payment","key","1","DEMO-PASS-key",null,null);
        assertNotNull(c.getBean(Facts.class).verify(command,java.time.Instant.parse("2026-10-15T00:00:00Z"),c.getBean(DependencyCalls.class).deadline()));
        assertThrows(SubmissionFailure.class,()->c.getBean(PublishedConfiguration.class).resolve(null,c.getBean(DependencyCalls.class).deadline()));assertNull(c.getBean(HikariDataSource.class).getHikariPoolMXBean());
    });}
    @Test void malformedEnabledOwnershipFailsStartup(){enabled().withPropertyValues("risk-submission.sources={}").run(c->assertNotNull(c.getStartupFailure()));}
    @Test void explicitIntegrationBeansReplaceUnavailableAdapters(){
        Facts facts=(command,cutoff,deadline)->{throw new UnsupportedOperationException();};Evidence evidence=(accepted,deadline)->{throw new UnsupportedOperationException();};PublishedConfiguration config=(version,deadline)->{throw new UnsupportedOperationException();};
        enabled().withBean(Facts.class,()->facts).withBean(Evidence.class,()->evidence).withBean(PublishedConfiguration.class,()->config).run(c->{
            assertNull(c.getStartupFailure());assertSame(facts,c.getBean(Facts.class));assertSame(evidence,c.getBean(Evidence.class));assertSame(config,c.getBean(PublishedConfiguration.class));
        });
    }
    @Test void missingLeaseConfigurationFailsStartup(){context().withPropertyValues("risk-submission.enabled=true","risk-submission.sources={\"payment-service\":[\"payment.v1\"]}","risk-submission.dependency-budget=PT2S").run(c->assertNotNull(c.getStartupFailure()));}
    @Test void explicitHttpConfigurationWiresRealAdapterWithoutConnecting()throws Exception{
        var client=mock(java.net.http.HttpClient.class);when(client.awaitTermination(java.time.Duration.ofSeconds(5))).thenReturn(true);
        var builder=mock(java.net.http.HttpClient.Builder.class,RETURNS_SELF);when(builder.build()).thenReturn(client);
        try(var construction=mockStatic(java.net.http.HttpClient.class)){construction.when(java.net.http.HttpClient::newBuilder).thenReturn(builder);
        enabled().withPropertyValues("risk-configuration-client.enabled=true",
        "risk-configuration-client.base-url=https://configuration.test","risk-configuration-client.connect-timeout=PT1S","risk-configuration-client.request-timeout=PT2S",
        "risk-configuration-client.max-response-bytes=65536","risk-configuration-client.service-token=header.fixture.signature").run(c->{
            assertNull(c.getStartupFailure());assertInstanceOf(com.ofss.risk.configuration.BusinessConfigurationHttpAdapter.class,c.getBean(PublishedConfiguration.class));
            assertNull(c.getBean(HikariDataSource.class).getHikariPoolMXBean());assertThrows(SubmissionFailure.class,()->c.getBean(Facts.class).verify(null,null,c.getBean(DependencyCalls.class).deadline()));
        });verify(client).shutdownNow();verify(client).awaitTermination(java.time.Duration.ofSeconds(5));}}
    @Test void enabledIncompleteHttpConfigurationFailsActualBeanPath(){enabled().withPropertyValues("risk-configuration-client.enabled=true").run(c->assertNotNull(c.getStartupFailure()));}
    @Test void enabledIncompleteFactsConfigurationFailsActualProductionWiring(){enabled().withPropertyValues("risk-facts-client.enabled=true").run(c->assertNotNull(c.getStartupFailure()));}
    @Test void enabledIncompleteEvidenceConfigurationFailsActualProductionWiring(){enabled().withPropertyValues("risk-evidence-client.enabled=true").run(c->assertNotNull(c.getStartupFailure()));}
    @Test void disabledSubmissionPreservesReadHostWithUnprovisionedEvidenceSettings(){context().withPropertyValues("risk-evidence-client.enabled=true").run(c->{assertNull(c.getStartupFailure());assertTrue(c.getBeansOfType(UpstreamEvidence.class).isEmpty());});}
    @Test void disabledSubmissionPreservesManagerReadHostEvenWithUnprovisionedFactsSettings(){context().withPropertyValues("risk-facts-client.enabled=true").run(c->{
        assertNull(c.getStartupFailure());assertTrue(c.getBeansOfType(Facts.class).isEmpty());assertNotNull(c.getBean(AssessmentReadService.class));assertNull(c.getBean(HikariDataSource.class).getHikariPoolMXBean());});}
    @Test void explicitUpstreamBeanIsComposedWithLocalHistoryWithoutConnecting(){
        UpstreamEvidence supplied=(accepted,deadline)->{throw SubmissionAdapters.unavailable();};
        enabled().withBean(UpstreamEvidence.class,()->supplied).run(c->{assertNull(c.getStartupFailure());assertSame(supplied,c.getBean(UpstreamEvidence.class));
            assertInstanceOf(ProductionEvidenceAdapter.class,c.getBean(Evidence.class));assertNull(c.getBean(HikariDataSource.class).getHikariPoolMXBean());});
    }
    @Test void invalidHistoryCapacityFailsStartup(){enabled().withPropertyValues("risk-history.max-assessments=0").run(c->assertNotNull(c.getStartupFailure()));}
}
