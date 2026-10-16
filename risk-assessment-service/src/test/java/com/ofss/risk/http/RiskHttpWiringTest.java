package com.ofss.risk.http;

import com.ofss.risk.RiskAssessmentApplication;
import com.ofss.risk.persistence.*;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import static org.junit.jupiter.api.Assertions.*;

/** Servlet context only: no listening socket, database connection or service process. */
class RiskHttpWiringTest {
    @Test void actualHttpApplicationWiresOnlyReadOperationsAndExternalConfigurationWithoutOpeningPool() {
        new WebApplicationContextRunner().withUserConfiguration(RiskAssessmentApplication.class)
            .withPropertyValues("RISK_DB_URL=jdbc:oracle:thin:@//invalid.test:1521/FREEPDB1","RISK_DB_USERNAME=TEST_ONLY","RISK_DB_PASSWORD=TEST_ONLY",
                "security.jwt.secret="+RiskAssessmentApiTest.random(),"spring.sql.init.mode=never")
            .run(context->{
                assertNull(context.getStartupFailure()); assertNotNull(context.getBean(AssessmentReadController.class)); assertNotNull(context.getBean(AssessmentReadService.class));
                assertTrue(context.getBeansOfType(RiskWorkService.class).isEmpty()); assertTrue(context.getBeansOfType(RiskAcceptanceService.class).isEmpty());
                assertTrue(context.getBeansOfType(RiskEvidenceService.class).isEmpty()); assertTrue(context.getBeansOfType(RiskFinalizationService.class).isEmpty());
                assertTrue(context.getBeansOfType(PinnedRiskEvaluators.class).isEmpty());
                var ds=context.getBean(HikariDataSource.class); assertNull(ds.getHikariPoolMXBean()); assertNull(ds.getConnectionInitSql());
                assertSame(ds,context.getBean(JdbcTransactionManager.class).getDataSource());
            });
    }
    @Test void httpResourceUsesConfigurableUnusedDefaultPortExternalSigningAndNoInitializers() {
        var loader=new YamlPropertiesFactoryBean(); loader.setResources(new ClassPathResource("application.yml")); var p=loader.getObject();
        assertEquals("${RISK_HTTP_PORT:8087}",p.getProperty("server.port")); assertEquals("${RISK_JWT_SECRET}",p.getProperty("security.jwt.secret"));
        assertEquals("never",p.getProperty("spring.sql.init.mode")); assertEquals("none",p.getProperty("spring.jpa.hibernate.ddl-auto"));
        assertEquals("false",p.getProperty("spring.flyway.enabled")); assertEquals("false",p.getProperty("spring.liquibase.enabled"));
    }
}
