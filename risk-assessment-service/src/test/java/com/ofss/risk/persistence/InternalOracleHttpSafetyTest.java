package com.ofss.risk.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** Offline only: confirms the real-server suite cannot execute by default. */
class InternalOracleHttpSafetyTest {
    @Test void remoteOrDifferentOracleTargetsAreRejectedBeforeConnection(){
        assertDoesNotThrow(()->OracleInternalRiskHttpTest.localTarget(null));
        assertDoesNotThrow(()->OracleInternalRiskHttpTest.localTarget("jdbc:oracle:thin:@//localhost:1521/FREEPDB1"));
        assertThrows(IllegalArgumentException.class,()->OracleInternalRiskHttpTest.localTarget("jdbc:oracle:thin:@//other-host:1521/FREEPDB1"));
    }
    @Test void riskServerAndFixtureWritesRequireExplicitOptIn(){
        var condition=OracleInternalRiskHttpTest.class.getAnnotation(EnabledIfEnvironmentVariable.class);
        assertNotNull(condition);assertEquals("D2_VERIFY_INTERNAL_HTTP_ORACLE_WRITES",condition.named());assertEquals("true",condition.matches());
        assertEquals(java.util.Set.of("success","insufficient"),OracleRiskReadHttpTest.KEYS);
    }
}
