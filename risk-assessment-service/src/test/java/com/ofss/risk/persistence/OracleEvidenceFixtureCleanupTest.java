package com.ofss.risk.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="RISK_VERIFY_ORACLE_EVIDENCE_WRITES",matches="true")
@EnabledIfEnvironmentVariable(named="RISK_ORACLE_EVIDENCE_CLEANUP_ONLY",matches="true")
class OracleEvidenceFixtureCleanupTest {
    @Test void cleansOnlyExactRecordedNamespaceAfterWorkerTerminationIsConfirmed() {
        String namespace=System.getenv("RISK_ORACLE_EVIDENCE_NAMESPACE"); OracleEvidenceFixtures.requireNamespace(namespace);
        var source=OracleAcceptanceConnection.externalDataSource(); var jdbc=new JdbcTemplate(source); jdbc.setQueryTimeout(15);
        var diagnostics=new OracleAcceptanceDiagnostics();
        try {
            new OracleEvidenceFixtureCleaner(jdbc,new JdbcTransactionManager(source)).clean(namespace);
            assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM D2_RISK.RISK_ASSESSMENTS WHERE SUBMISSION_SOURCE=?",Long.class,namespace));
        } catch(RuntimeException e) { diagnostics.record("EVIDENCE_CLEANUP",e); diagnostics.print(); throw new AssertionError("Freeze fixture cleanup failed/refused; no broader cleanup attempted"); }
    }
}
