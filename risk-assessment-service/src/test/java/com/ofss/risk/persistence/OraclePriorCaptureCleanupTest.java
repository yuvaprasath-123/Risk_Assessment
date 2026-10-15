package com.ofss.risk.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="RISK_VERIFY_ORACLE_PRIOR_CAPTURE_WRITES",matches="true")
@EnabledIfEnvironmentVariable(named="RISK_PRIOR_CAPTURE_CLEANUP_ONLY",matches="true")
class OraclePriorCaptureCleanupTest {
    @Test void guardedRecoveryOnlyAfterWorkersHaveStopped(){
        assertEquals("true",System.getenv("RISK_PRIOR_CAPTURE_WORKERS_STOPPED"),"Stop all test workers before cleanup");String namespace=System.getenv("RISK_PRIOR_CAPTURE_NAMESPACE");OraclePriorCaptureFixtures.validate(namespace);
        var ds=OracleAcceptanceConnection.externalDataSource();new OraclePriorCaptureCleaner(new JdbcTemplate(ds),new JdbcTransactionManager(ds)).clean(namespace);
        System.out.println("Prior fixture recovery cleanup completed; remaining parents=0; namespace: "+namespace);
    }
}
