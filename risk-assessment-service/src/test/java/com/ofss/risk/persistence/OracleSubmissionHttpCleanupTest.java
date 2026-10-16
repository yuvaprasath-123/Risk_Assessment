package com.ofss.risk.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="RISK_VERIFY_ORACLE_SUBMISSION_HTTP_WRITES",matches="true")
@EnabledIfEnvironmentVariable(named="RISK_SUBMISSION_HTTP_CLEANUP_ONLY",matches="true")
@EnabledIfEnvironmentVariable(named="RISK_SUBMISSION_HTTP_WORKERS_STOPPED",matches="true")
class OracleSubmissionHttpCleanupTest {
    @Test void recoverOnlyVerifiedExactNamespaceAfterAllWorkersStopped(){
        String namespace=System.getenv("RISK_SUBMISSION_HTTP_NAMESPACE");OracleSubmissionFixtures.requireNamespace(namespace);
        OracleSubmissionHttpTest.localTarget(System.getenv("RISK_DB_URL"));var source=OracleAcceptanceConnection.externalDataSource();var jdbc=new JdbcTemplate(source);jdbc.setQueryTimeout(10);
        try{new OracleSubmissionFixtureCleaner(jdbc,new JdbcTransactionManager(source)).clean(namespace);assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM D2_RISK.RISK_ASSESSMENTS WHERE SUBMISSION_SOURCE=?",Long.class,namespace));
            System.out.println("Submission recovery cleanup completed; remaining parents=0; namespace: "+namespace);
        }catch(RuntimeException e){throw new AssertionError("Submission cleanup failed/refused; no broader deletion attempted; retain namespace");}
    }
}
