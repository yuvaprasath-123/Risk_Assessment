package com.ofss.risk.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import static org.junit.jupiter.api.Assertions.*;

class RiskPersistenceConfigurationTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.NullSource
    @org.junit.jupiter.params.provider.ValueSource(strings={"","invalid","PT0S","PT-1S"})
    void workContextRefusesMissingMalformedOrNonpositiveDuration(String duration) {
        try(var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().remove(org.springframework.core.env.StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
            context.getEnvironment().getPropertySources().remove(org.springframework.core.env.StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
            var properties=new java.util.HashMap<String,Object>();
            properties.put("RISK_DB_USERNAME","TEST_ONLY"); properties.put("RISK_DB_PASSWORD","TEST_ONLY");
            properties.put("RISK_DB_URL","jdbc:oracle:thin:@//invalid.test:1521/FREEPDB1");
            if(duration!=null) properties.put("RISK_LEASE_DURATION",duration);
            context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("testOnly",properties));
            context.register(RiskPersistenceConfiguration.class);
            assertThrows(org.springframework.beans.factory.BeanCreationException.class,context::refresh);
        }
    }
    @Test void workBeansUseSameDataSourceTransactionManagerAndOracleClockWithoutOpeningPool() {
        try(var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource(
                "testOnly",java.util.Map.of("RISK_DB_USERNAME","TEST_ONLY","RISK_DB_PASSWORD","TEST_ONLY",
                "RISK_DB_URL","jdbc:oracle:thin:@//invalid.test:1521/FREEPDB1","RISK_LEASE_DURATION","PT30S")));
            context.register(RiskPersistenceConfiguration.class); context.refresh();
            assertNotNull(context.getBean(RiskWorkService.class));
            assertNotNull(context.getBean(RiskEvidenceService.class));
            assertInstanceOf(OracleLeaseClock.class,context.getBean("riskLeaseClock"));
            var ds=context.getBean(com.zaxxer.hikari.HikariDataSource.class);
            assertSame(ds,context.getBean(org.springframework.jdbc.support.JdbcTransactionManager.class).getDataSource());
            assertNull(ds.getHikariPoolMXBean()); assertNull(ds.getConnectionInitSql());
        }
    }
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
