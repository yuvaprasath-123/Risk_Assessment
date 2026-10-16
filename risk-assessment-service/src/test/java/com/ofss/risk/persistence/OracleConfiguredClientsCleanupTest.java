package com.ofss.risk.persistence;

import com.ofss.risk.upstream.ConfiguredClientFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.support.JdbcTransactionManager;
import static org.junit.jupiter.api.Assertions.*;

/** Recovery only after explicit confirmation that every receiver/client/provider/worker is stopped. No listeners start. */
@EnabledIfEnvironmentVariable(named="D2_VERIFY_ORACLE_CONFIGURED_CLIENTS",matches="true")
@EnabledIfEnvironmentVariable(named="D2_CONFIGURED_CLIENTS_ISOLATED",matches="true")
@EnabledIfEnvironmentVariable(named="D2_CONFIGURED_CLIENTS_CLEANUP_ONLY",matches="true")
@EnabledIfEnvironmentVariable(named="D2_CONFIGURED_CLIENTS_WORKERS_STOPPED",matches="true")
class OracleConfiguredClientsCleanupTest {
    @Test void cleanupExactKnownNamespace(){
        String namespace=System.getenv("D2_CONFIGURED_CLIENTS_NAMESPACE");ConfiguredClientFixtures.requireNamespace(namespace); // Before connecting.
        OracleInternalRiskHttpTest.localTarget(System.getenv("RISK_DB_URL"));
        var risk=OracleAcceptanceConnection.externalDataSource();var config=OracleConfiguredClientsFlowTest.configurationSource();
        var r=OracleConfiguredClientsFlowTest.jdbc(risk);var c=OracleConfiguredClientsFlowTest.jdbc(config);var helper=new OracleConfiguredClientConfiguration(namespace,c,new JdbcTransactionManager(config));
        helper.discover();helper.preflightCleanup();var hashes=helper.verifiedHashes();
        for(long id:helper.ids.values())assertEquals(0L,r.queryForObject("SELECT COUNT(*) FROM D2_RISK.RISK_ASSESSMENTS WHERE CONFIGURATION_VERSION_ID=? AND SUBMISSION_SOURCE<>?",Long.class,id,namespace));
        new OracleConfiguredClientRiskCleaner(r,new JdbcTransactionManager(risk)).clean(namespace,hashes);helper.clean();
        System.out.println("Guarded configured-client recovery cleanup: zero fixture parents in both schemas; namespace="+namespace);
    }
}
