package com.ofss.risk.persistence;

import com.ofss.risk.RiskAssessmentApplication;
import com.ofss.risk.submission.*;
import com.ofss.risk.submission.SubmissionAdapters.*;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** No socket, Oracle or live test invocation; verifies harness guardrails and real bean construction only. */
class SubmissionLiveHarnessSafetyTest {
    @Test void liveSuiteAndRecoveryRequireExplicitWriteOptIns(){
        var gates=OracleSubmissionHttpTest.class.getAnnotationsByType(EnabledIfEnvironmentVariable.class);assertEquals(1,gates.length);assertEquals("RISK_VERIFY_ORACLE_SUBMISSION_HTTP_WRITES",gates[0].named());assertEquals("true",gates[0].matches());
        var recovery=OracleSubmissionHttpCleanupTest.class.getAnnotationsByType(EnabledIfEnvironmentVariable.class);assertEquals(Set.of("RISK_VERIFY_ORACLE_SUBMISSION_HTTP_WRITES","RISK_SUBMISSION_HTTP_CLEANUP_ONLY","RISK_SUBMISSION_HTTP_WORKERS_STOPPED"),Arrays.stream(recovery).map(EnabledIfEnvironmentVariable::named).collect(java.util.stream.Collectors.toSet()));
    }
    @ParameterizedTest @ValueSource(strings={"","D2_RISK_FINAL_00000000-0000-0000-0000-000000000000","d2-risk-submit-%","d2-risk-submit-00000000-0000-0000-0000-000000000000/extra","D2-RISK-SUBMIT-00000000-0000-0000-0000-000000000000"})
    void malformedRecoveryNamespaceRejectedBeforeAnyConnection(String namespace){var jdbc=mock(org.springframework.jdbc.core.JdbcTemplate.class);var tx=mock(org.springframework.transaction.PlatformTransactionManager.class);
        assertThrows(IllegalArgumentException.class,()->new OracleSubmissionFixtures(namespace));assertThrows(IllegalArgumentException.class,()->new OracleSubmissionFixtureCleaner(jdbc,tx).clean(namespace));verifyNoInteractions(jdbc,tx);}
    @Test void cleanupSqlHasExactSourceAndNoWildcardOrReset(){assertTrue(OracleSubmissionFixtureCleaner.LOCK.contains("SUBMISSION_SOURCE=?"));assertFalse(OracleSubmissionFixtureCleaner.LOCK.contains("LIKE"));assertTrue(OracleSubmissionFixtureCleaner.MARKER.contains("CORRELATION_ID IS NULL"));assertTrue(OracleSubmissionFixtureCleaner.MARKER.contains("ROW_VERSION=?"));}
    @ParameterizedTest @ValueSource(strings={"http://example.com:80","https://127.0.0.1:80","http://user:pass@127.0.0.1:80","http://127.0.0.1:80/other","http://127.0.0.1:80?extra=yes"})
    void clientCannotSendCredentialsOffLoopback(String base){assertThrows(IllegalArgumentException.class,()->SubmissionContractHttp.target(URI.create(base),SubmissionContractHttp.POST,null,null));}
    @Test void clientConstrainsStatusReferencesAndPaths(){var fixture=new OracleSubmissionFixtures();var base=URI.create("http://127.0.0.1:1234");
        assertTrue(SubmissionContractHttp.target(base,SubmissionContractHttp.STATUS,fixture.namespace,"success").getRawQuery().contains(fixture.namespace));
        for(String path:List.of("//example.com","/internal/v1/risk-assessments/../else","/api/v1/unknown"))assertThrows(IllegalArgumentException.class,()->SubmissionContractHttp.target(base,path,null,null));
        assertThrows(IllegalArgumentException.class,()->SubmissionContractHttp.target(base,SubmissionContractHttp.STATUS,fixture.namespace,"success&other=1"));}
    @Test void controlledSnapshotRecomputesAndNamesItsNamespaceWithoutPublishing(){var fixture=new OracleSubmissionFixtures();var p=fixture.published();assertTrue(p.canonicalConfiguration().contains(fixture.namespace));assertEquals(p.contentHash(),new PublishedConfigurationMapping().map(p).contentHash());assertEquals("PUBLISHED",p.publicationState());}
    @Test void actualContractAdaptersAndTestInterceptorsWireWithoutOpeningOracle(){
        var fixtureTest=new OracleSubmissionHttpTest();
        new WebApplicationContextRunner().withUserConfiguration(RiskAssessmentApplication.class).withInitializer(fixtureTest::contractAdapters)
            .withPropertyValues("RISK_DB_URL=jdbc:oracle:thin:@//invalid.test:1521/FREEPDB1","RISK_DB_USERNAME=TEST_ONLY","RISK_DB_PASSWORD=TEST_ONLY","security.jwt.secret="+fixtureTest.credentials.userSecret(),"spring.sql.init.mode=never")
            .run(c->{assertNull(c.getStartupFailure());assertEquals(1,c.getBeansOfType(Facts.class).size());assertEquals(1,c.getBeansOfType(Evidence.class).size());assertEquals(1,c.getBeansOfType(PublishedConfiguration.class).size());
                assertTrue(mockingDetails(c.getBean(RiskAcceptanceService.class)).isSpy());assertTrue(mockingDetails(c.getBean(RiskFinalizationService.class)).isSpy());assertNull(c.getBean(HikariDataSource.class).getHikariPoolMXBean());});
    }
}
