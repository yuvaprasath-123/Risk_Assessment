package com.ofss.risk.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="RISK_VERIFY_ORACLE_FINALIZATION_WRITES",matches="true")
@EnabledIfEnvironmentVariable(named="RISK_ORACLE_FINALIZATION_CLEANUP_ONLY",matches="true")
@EnabledIfEnvironmentVariable(named="RISK_ORACLE_FINALIZATION_WORKERS_STOPPED",matches="true")
class OracleFinalizationFixtureCleanupTest {
    @Test void removesOnlyVerifiedNamespaceAfterUserConfirmsWorkersHaveStopped() {
        String namespace=System.getenv("RISK_ORACLE_FINALIZATION_NAMESPACE"); OracleFinalizationFixtures.requireNamespace(namespace);
        var source=OracleAcceptanceConnection.externalDataSource(); var diagnostics=new OracleAcceptanceDiagnostics(); var jdbc=diagnostics.jdbc(source);
        try {
            new OracleFinalizationFixtureCleaner(jdbc,new JdbcTransactionManager(source)).clean(namespace);
            assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM D2_RISK.RISK_ASSESSMENTS WHERE SUBMISSION_SOURCE=?",Long.class,namespace));
            System.out.println("Finalization fixture recovery cleanup completed; remaining parents=0; namespace: "+namespace);
        } catch(RuntimeException failure) {
            diagnostics.record("FINALIZATION_CLEANUP_RECOVERY",failure); diagnostics.print(); throw new AssertionError("Finalization cleanup failed/refused; no broader cleanup attempted");
        }
    }
}
