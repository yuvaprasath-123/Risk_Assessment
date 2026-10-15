package com.ofss.risk.http.internal;

import com.ofss.risk.persistence.*;
import com.ofss.risk.submission.*;
import com.ofss.risk.submission.SubmissionAdapters.*;
import java.time.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;

@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name="risk-submission.enabled",havingValue="true")
public class SubmissionConfiguration {
    @Bean SubmissionSources submissionSources(@Value("${risk-submission.sources}")String json){return new SubmissionSources(json);}
    @Bean(destroyMethod="close") DependencyCalls dependencyCalls(@Value("${risk-submission.dependency-budget}")String duration){return new DependencyCalls(Duration.parse(duration));}
    @Bean @ConditionalOnMissingBean(PublishedConfiguration.class)
    PublishedConfiguration unavailablePublishedConfiguration(){return (version,deadline)->{throw SubmissionAdapters.unavailable();};}
    static JdbcTemplate jdbc(DataSource ds){var jdbc=new JdbcTemplate(ds);jdbc.setQueryTimeout(5);return jdbc;}
    @Bean RiskAcceptanceRepository submissionAcceptanceRepository(DataSource ds){return new RiskAcceptanceRepository(jdbc(ds));}
    @Bean RiskWorkRepository submissionWorkRepository(DataSource ds){return new RiskWorkRepository(jdbc(ds));}
    @Bean RiskEvidenceRepository submissionEvidenceRepository(DataSource ds){return new RiskEvidenceRepository(jdbc(ds));}
    @Bean RiskFinalizationRepository submissionFinalizationRepository(DataSource ds){return new RiskFinalizationRepository(jdbc(ds));}
    @Bean Clock submissionLeaseClock(DataSource ds){return new OracleLeaseClock(jdbc(ds));}
    @Bean RiskAcceptanceService submissionAcceptance(RiskAcceptanceRepository r,JdbcTransactionManager tx){return new RiskAcceptanceService(r,new CanonicalAcceptance(),tx,Clock.systemUTC());}
    @Bean RiskWorkService submissionWork(RiskWorkRepository r,JdbcTransactionManager tx,@Qualifier("submissionLeaseClock")Clock clock,
            @Value("${RISK_LEASE_DURATION}")String duration){return new RiskWorkService(r,tx,clock,Duration.parse(duration));}
    @Bean RiskEvidenceService submissionEvidence(RiskWorkRepository w,RiskAcceptanceRepository a,RiskEvidenceRepository e,JdbcTransactionManager tx,
            @Qualifier("submissionLeaseClock")Clock clock){return new RiskEvidenceService(w,a,e,tx,clock);}
    @Bean RiskFinalizationService submissionFinalization(RiskAcceptanceRepository a,RiskWorkRepository w,RiskEvidenceRepository e,
            RiskFinalizationRepository r,JdbcTransactionManager tx,@Qualifier("submissionLeaseClock")Clock clock){return new RiskFinalizationService(a,w,e,r,PinnedRiskEvaluators.current(),tx,clock);}
    @Bean SubmissionSnapshotReader submissionSnapshots(RiskAcceptanceRepository a,RiskWorkRepository w,RiskEvidenceRepository e,JdbcTransactionManager tx){return new SubmissionSnapshotReader(a,w,e,tx);}
    @Bean RiskSubmissionService riskSubmissionService(SubmissionSources sources,Facts facts,Evidence evidence,PublishedConfiguration config,
            RiskAcceptanceService a,RiskWorkService w,RiskEvidenceService e,RiskFinalizationService f,SubmissionSnapshotReader s,
            AssessmentReadService reads,DependencyCalls calls,JdbcTransactionManager tx){return new RiskSubmissionService(sources,facts,evidence,config,a,w,e,f,s,reads,calls,Clock.systemUTC(),tx);}
    @Configuration(proxyBeanMethods=false) @Profile("!risk-submission-demo")
    @ConditionalOnProperty(name="risk-submission.enabled",havingValue="true")
    static class ProductionAdapters {
        @Bean @ConditionalOnMissingBean(Facts.class) Facts unavailableFacts(){return (command,cutoff,deadline)->{throw SubmissionAdapters.unavailable();};}
        @Bean @ConditionalOnMissingBean(Evidence.class) Evidence unavailableEvidence(){return (accepted,deadline)->{throw SubmissionAdapters.unavailable();};}
    }
    @Configuration(proxyBeanMethods=false) @Profile("risk-submission-demo")
    @ConditionalOnProperty(name="risk-submission.enabled",havingValue="true")
    static class DemoAdapters {
        @Bean Facts demonstrationFacts(){return DemoSubmissionAdapters::facts;}
        @Bean Evidence demonstrationEvidence(){return DemoSubmissionAdapters::evidence;}
    }
}
