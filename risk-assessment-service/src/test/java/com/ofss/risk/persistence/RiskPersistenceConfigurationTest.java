package com.ofss.risk.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import static org.junit.jupiter.api.Assertions.*;

class RiskPersistenceConfigurationTest {
    @Test void constructingExternalDataSourceDoesNotOpenPoolOrInitializeSchema() {
        // Dummy test-only credentials, never a live connection or environment read.
        try (var ds = new RiskPersistenceConfiguration().riskDataSource("jdbc:oracle:thin:@//invalid.test:1521/FREEPDB1", "TEST_ONLY", "TEST_ONLY")) {
            assertEquals("TEST_ONLY", ds.getUsername()); assertEquals(3, ds.getMaximumPoolSize());
            assertNull(ds.getHikariPoolMXBean()); assertNull(ds.getConnectionInitSql());
        }
    }
    @Test void resourceDisablesInitializersAndContainsOnlyExternalCredentialReferences() {
        var loader = new YamlPropertiesFactoryBean(); loader.setResources(new ClassPathResource("application.yml"));
        var p = loader.getObject(); assertNotNull(p);
        assertEquals("${RISK_DB_USERNAME}", p.getProperty("spring.datasource.username"));
        assertEquals("${RISK_DB_PASSWORD}", p.getProperty("spring.datasource.password"));
        assertEquals("never", p.getProperty("spring.sql.init.mode"));
        assertEquals("none", p.getProperty("spring.jpa.hibernate.ddl-auto"));
        assertEquals("false", p.getProperty("spring.jpa.generate-ddl"));
        assertEquals("false", p.getProperty("spring.flyway.enabled"));
        assertEquals("false", p.getProperty("spring.liquibase.enabled"));
    }
}
