package com.ofss.risk.persistence;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Clock;
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
}
