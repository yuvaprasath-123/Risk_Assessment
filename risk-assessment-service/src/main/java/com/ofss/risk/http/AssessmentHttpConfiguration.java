package com.ofss.risk.http;

import com.ofss.risk.persistence.*;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;

@Configuration(proxyBeanMethods=false)
public class AssessmentHttpConfiguration {
    @Bean(destroyMethod="close") public HikariDataSource riskDataSource(@Value("${RISK_DB_URL:jdbc:oracle:thin:@//localhost:1521/FREEPDB1}") String url,
            @Value("${RISK_DB_USERNAME}") String username,@Value("${RISK_DB_PASSWORD}") String password) {
        return new RiskPersistenceConfiguration().riskDataSource(url,username,password);
    }
    @Bean public JdbcTransactionManager riskTransactionManager(DataSource source) { return new JdbcTransactionManager(source); }
    @Bean public AssessmentReadRepository assessmentReadRepository(DataSource source) { return new AssessmentReadRepository(new JdbcTemplate(source)); }
    @Bean public AssessmentReadService assessmentReadService(AssessmentReadRepository repository,JdbcTransactionManager manager) { return new AssessmentReadService(repository,manager); }
}
