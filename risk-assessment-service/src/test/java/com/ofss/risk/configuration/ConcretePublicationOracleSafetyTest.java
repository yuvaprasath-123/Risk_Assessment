package com.ofss.risk.configuration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

/** Offline harness guards only; does not construct a Spring context or access Oracle. */
class ConcretePublicationOracleSafetyTest {
    @Test void terminatedWorkersPermitProgressButFailedShutdownCannotBeResetByANewFixture(){
        assertDoesNotThrow(()->OracleConcretePublicationFlowTest.requireWorkersStopped(true));
        assertThrows(IllegalStateException.class,()->OracleConcretePublicationFlowTest.requireWorkersStopped(false));
    }
    @Test void liveClassRequiresBothWriteOptInAndIsolationConsent(){
        var flags=OracleConcretePublicationFlowTest.class.getAnnotationsByType(EnabledIfEnvironmentVariable.class);
        assertEquals(Set.of("CONFIG_VERIFY_ORACLE_CONCRETE_PUBLICATION","CONFIG_ORACLE_PUBLICATION_ISOLATED"),
            Arrays.stream(flags).map(EnabledIfEnvironmentVariable::named).collect(Collectors.toSet()));
        assertTrue(Arrays.stream(flags).allMatch(f->f.matches().equals("true")));
    }
}
