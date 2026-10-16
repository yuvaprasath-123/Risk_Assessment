package com.ofss.risk.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import static org.junit.jupiter.api.Assertions.*;

/** Manual recovery ONLY for a recorded exact namespace after interrupted tests; same guarded cleaner. */
@EnabledIfEnvironmentVariable(named = "RISK_VERIFY_ORACLE_WRITES", matches = "true")
@EnabledIfEnvironmentVariable(named = "RISK_ORACLE_CLEANUP_ONLY", matches = "true")
class OracleAcceptanceFixtureCleanupTest {
    @Test void removesOnlyRecordedSyntheticNamespaceFixtures() {
        String namespace = System.getenv("RISK_ORACLE_FIXTURE_NAMESPACE");
        OracleAcceptanceFixtures.requireNamespace(namespace);
        var source = OracleAcceptanceConnection.externalDataSource(); var jdbc = new JdbcTemplate(source); jdbc.setQueryTimeout(15);
        try {
            new OracleAcceptanceFixtureCleaner(jdbc, new JdbcTransactionManager(source)).clean(namespace);
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM D2_RISK.RISK_ASSESSMENTS WHERE SUBMISSION_SOURCE = ?", Integer.class, namespace));
        } catch (RuntimeException failure) { throw new AssertionError("Synthetic fixture cleanup failed/refused; no broader cleanup attempted"); }
    }
}
