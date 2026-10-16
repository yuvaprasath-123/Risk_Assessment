package com.ofss.risk.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit recovery only after BOTH workers are known terminated. No wildcard cleanup. */
@EnabledIfEnvironmentVariable(named="RISK_VERIFY_ORACLE_WORK_WRITES",matches="true")
@EnabledIfEnvironmentVariable(named="RISK_ORACLE_WORK_CLEANUP_ONLY",matches="true")
class OracleWorkFixtureCleanupTest {
    @Test void cleansExactRecordedWorkNamespace() {
        String namespace=System.getenv("RISK_ORACLE_WORK_NAMESPACE"); OracleWorkFixtures.requireNamespace(namespace);
        var ds=OracleAcceptanceConnection.externalDataSource(); var jdbc=new JdbcTemplate(ds); jdbc.setQueryTimeout(15);
        var diagnostics=new OracleAcceptanceDiagnostics();
        try {
            new OracleWorkFixtureCleaner(jdbc,new JdbcTransactionManager(ds)).clean(namespace);
            assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM D2_RISK.RISK_ASSESSMENTS WHERE SUBMISSION_SOURCE=?",Long.class,namespace));
        } catch(RuntimeException e) {
            diagnostics.record("WORK_CLEANUP",e); diagnostics.print();
            throw new AssertionError("Work cleanup failed/refused; no broader cleanup attempted");
        }
    }
}
