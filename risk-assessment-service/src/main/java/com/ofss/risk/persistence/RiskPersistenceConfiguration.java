package com.ofss.risk.persistence;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;

/** Import explicitly into a host context; no main method, HTTP server, initializer or schema DDL. */
@Configuration(proxyBeanMethods = false)
public class RiskPersistenceConfiguration {
    @Bean(destroyMethod = "close")
    public HikariDataSource riskDataSource(
            @Value("${RISK_DB_URL:jdbc:oracle:thin:@//localhost:1521/FREEPDB1}") String url,
            @Value("${RISK_DB_USERNAME}") String username, @Value("${RISK_DB_PASSWORD}") String password) {
        var ds = new HikariDataSource(); ds.setJdbcUrl(url); ds.setUsername(username); ds.setPassword(password);
        ds.setDriverClassName("oracle.jdbc.OracleDriver"); ds.setMaximumPoolSize(3); ds.setConnectionTimeout(5000);
        return ds;
    }
    @Bean public JdbcTransactionManager riskTransactionManager(@Qualifier("riskDataSource") DataSource ds) {
        return new JdbcTransactionManager(ds);
    }
    @Bean public RiskAcceptanceRepository riskAcceptanceRepository(@Qualifier("riskDataSource") DataSource ds) {
        return new RiskAcceptanceRepository(new JdbcTemplate(ds));
    }
    @Bean public RiskAcceptanceService riskAcceptanceService(RiskAcceptanceRepository repository,
            @Qualifier("riskTransactionManager") JdbcTransactionManager manager) {
        return new RiskAcceptanceService(repository, new CanonicalAcceptance(), manager, Clock.systemUTC());
    }
    @Bean public RiskWorkRepository riskWorkRepository(@Qualifier("riskDataSource") DataSource ds) {
        return new RiskWorkRepository(new JdbcTemplate(ds));
    }
    @Bean public Clock riskLeaseClock(@Qualifier("riskDataSource") DataSource ds) {
        return new OracleLeaseClock(new JdbcTemplate(ds));
    }
    @Bean public RiskEvidenceRepository riskEvidenceRepository(@Qualifier("riskDataSource") DataSource ds) {
        return new RiskEvidenceRepository(new JdbcTemplate(ds));
    }
    @Bean public RiskEvidenceService riskEvidenceService(RiskWorkRepository work,RiskAcceptanceRepository acceptance,
            RiskEvidenceRepository evidence,@Qualifier("riskTransactionManager") JdbcTransactionManager manager,
            @Qualifier("riskLeaseClock") Clock clock) {
        return new RiskEvidenceService(work,acceptance,evidence,manager,clock);
    }
    @Bean public RiskWorkService riskWorkService(RiskWorkRepository repository,
            @Qualifier("riskTransactionManager") JdbcTransactionManager manager,
            @Qualifier("riskLeaseClock") Clock clock,
            @Value("${RISK_LEASE_DURATION}") String duration) {
        return new RiskWorkService(repository,manager,clock,Duration.parse(duration));
    }
    @Bean public RiskFinalizationRepository riskFinalizationRepository(@Qualifier("riskDataSource") DataSource ds) {
        return new RiskFinalizationRepository(new JdbcTemplate(ds));
    }
    @Bean public PinnedRiskEvaluators pinnedRiskEvaluators() { return PinnedRiskEvaluators.current(); }
    @Bean public RiskFinalizationService riskFinalizationService(RiskAcceptanceRepository acceptance,RiskWorkRepository work,
            RiskEvidenceRepository evidence,RiskFinalizationRepository repository,PinnedRiskEvaluators evaluators,
            @Qualifier("riskTransactionManager") JdbcTransactionManager manager,@Qualifier("riskLeaseClock") Clock clock) {
        return new RiskFinalizationService(acceptance,work,evidence,repository,evaluators,manager,clock);
    }
}
