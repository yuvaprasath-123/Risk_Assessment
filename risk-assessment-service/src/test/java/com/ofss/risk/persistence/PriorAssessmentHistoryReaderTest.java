package com.ofss.risk.persistence;

import com.ofss.risk.contract.*;
import com.ofss.risk.engine.*;
import com.ofss.risk.submission.*;
import java.time.*;
import java.util.*;
import java.sql.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.ofss.risk.persistence.PriorHistoryFixtures.*;
import static com.ofss.risk.persistence.PriorAssessmentHistoryRepository.*;

/** Real Spring/JDBC transactions, mock JDBC/repository; no Oracle integration claim. */
class PriorAssessmentHistoryReaderTest {
    DataSource ds=mock(DataSource.class);Connection connection=mock(Connection.class);Statement statement=mock(Statement.class);
    PriorAssessmentHistoryRepository repo=mock(PriorAssessmentHistoryRepository.class);JdbcTransactionManager tx;
    AcceptanceRequest accepted=request();PriorAssessmentHistoryReader reader;int capacity=3;
    SubmissionAdapters.Deadline deadline(){return new SubmissionAdapters.Deadline(System.nanoTime()+Duration.ofSeconds(5).toNanos());}
    PriorAssessmentHistory.Request query(String hash){return new PriorAssessmentHistory.Request(accepted,Set.of(new SubmissionIdentity("other-source","retry-pair")),hash);}
    @BeforeEach void setup()throws Exception{
        when(ds.getConnection()).thenReturn(connection);when(connection.getAutoCommit()).thenReturn(true);when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);when(connection.createStatement()).thenReturn(statement);
        tx=new JdbcTransactionManager(ds);reader=new PriorAssessmentHistoryReader(repo,tx,capacity);
        doAnswer(i->{new RiskFinalizationRepository(new JdbcTemplate(ds)).beginSnapshot();return null;}).when(repo).beginSnapshot();
        when(repo.captureAttempt(any())).thenAnswer(i->{assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());assertTrue(TransactionSynchronizationManager.hasResource(ds));return 1;});
        when(repo.candidates(anyString(),any(),any(),anyInt())).thenReturn(List.of());
    }
    void records(Candidate... values){when(repo.candidates(anyString(),any(),any(),anyInt())).thenReturn(Arrays.asList(values));for(var r:values)when(repo.verify(r)).thenReturn(verified(r,"alias-"+r.id(),Set.of(RuleCode.STRUCTURING,RuleCode.NEW_SUPPLIER)));}
    @Test void emptySupportedPopulationIsAuthoritativeAndUsesOneOracleReadOnlySnapshot()throws Exception{
        var result=reader.read(query(null),deadline());assertEquals(RiskInput.Availability.COMPLETE,result.evidence().availability());assertTrue(result.evidence().records().isEmpty());
        assertEquals(accepted.cutoff().minusSeconds(7776000),result.evidence().coveredFrom());assertEquals(accepted.cutoff(),result.evidence().cutoff());assertEquals(64,result.source().revision().length());
        var order=inOrder(statement,repo,connection);order.verify(statement).executeUpdate("SET TRANSACTION READ ONLY");order.verify(repo).captureAttempt(accepted);order.verify(repo).candidates("456",accepted.cutoff().minusSeconds(7776000),accepted.cutoff(),3);order.verify(connection).commit();
        verify(connection).setReadOnly(true);verify(ds).getConnection();verify(connection,never()).rollback();
    }
    @Test void deterministicNanosecondOrderingAndNamespacedDistinctIdentities(){
        var now=accepted.cutoff();var a=candidate(9,"z","shared",now.minusNanos(1));var b=candidate(10,"a","shared",now.minusNanos(1));var c=candidate(11,"a","earlier",now.minusNanos(2));records(a,b,c);
        var result=reader.read(query(null),deadline());assertEquals(List.of("d2-risk-assessment:11","d2-risk-assessment:10","d2-risk-assessment:9"),result.evidence().records().stream().map(RiskInput.PriorAssessment::evidenceId).toList());
        assertEquals(3,result.evidence().records().stream().map(RiskInput.PriorAssessment::submissionId).distinct().count());assertEquals(now.minusNanos(1),result.evidence().records().getLast().assessedAt());
        records(c,a,b);assertEquals(result,reader.read(query(null),deadline()));
    }
    @Test void startIsInclusiveAndCutoffIsExclusive(){var start=accepted.cutoff().minusSeconds(7776000);records(candidate(1,"a","x",start));assertEquals(start,reader.read(query(null),deadline()).evidence().records().getFirst().assessedAt());}
    @ParameterizedTest @ValueSource(strings={"before-start","at-cutoff","after-cutoff","supplier","currency","duplicate-id","duplicate-identity"})
    void queryOrPersistenceInconsistencyFailsClosed(String defect)throws Exception{
        var c=candidate(1,"a","x",accepted.cutoff().minusNanos(1));
        if(defect.equals("before-start"))c=candidate(1,"a","x",accepted.cutoff().minusSeconds(7776000).minusNanos(1));
        if(defect.equals("at-cutoff"))c=candidate(1,"a","x",accepted.cutoff());if(defect.equals("after-cutoff"))c=candidate(1,"a","x",accepted.cutoff().plusNanos(1));
        if(defect.equals("supplier"))c=new Candidate(c.id(),c.identity(),"999","INR",c.completed());if(defect.equals("currency"))c=new Candidate(c.id(),c.identity(),"456","USD",c.completed());
        if(defect.equals("duplicate-id"))records(c,candidate(1,"b","y",c.completed()));else if(defect.equals("duplicate-identity"))records(c,candidate(2,"a","x",c.completed()));else records(c);
        var e=assertThrows(SubmissionFailure.class,()->reader.read(query(null),deadline()));assertEquals(SubmissionFailure.Code.DEPENDENCY_UNAVAILABLE,e.code());assertNull(e.getCause());verify(connection).rollback();verify(connection,never()).commit();
    }
    @Test void currentNamespacedAndRetryAliasesExcludedWithoutCrossNamespaceCollisions(){
        var t=accepted.cutoff().minusSeconds(1);var current=candidate(1,accepted.identity().source(),accepted.identity().key(),t);var retry=candidate(2,"other-source","retry-pair",t);var alias=candidate(3,accepted.identity().source(),"different",t);
        records(current,retry,alias);when(repo.verify(alias)).thenReturn(verified(alias,"retry-alias",Set.of(RuleCode.NEW_SUPPLIER)));assertTrue(reader.read(query(null),deadline()).evidence().records().isEmpty());
        var independent=candidate(4,"different-namespace",accepted.identity().key(),t);records(independent);when(repo.verify(independent)).thenReturn(verified(independent,"retry-alias",Set.of(RuleCode.NEW_SUPPLIER)));assertEquals(1,reader.read(query(null),deadline()).evidence().records().size());
    }
    @Test void capacityPlusSentinelReturnsIncompleteWithoutAnyPartialResults(){
        var t=accepted.cutoff().minusSeconds(1);records(candidate(1,"a","1",t),candidate(2,"a","2",t),candidate(3,"a","3",t),candidate(4,"a","4",t));
        var result=reader.read(query(null),deadline());assertEquals(RiskInput.Availability.INCOMPLETE,result.evidence().availability());assertTrue(result.evidence().records().isEmpty());assertNull(result.source().revision());verify(repo,never()).verify(any());
    }
    @Test void corruptionEvenInExcludedRowRollsBack()throws Exception{var r=candidate(1,accepted.identity().source(),accepted.identity().key(),accepted.cutoff().minusSeconds(1));records(r);when(repo.verify(r)).thenThrow(new IllegalArgumentException("private result payload"));
        var e=assertThrows(SubmissionFailure.class,()->reader.read(query(null),deadline()));assertNull(e.getCause());verify(connection).rollback();}
    @Test void databaseFailureNeverBecomesEmptyHistory()throws Exception{when(repo.candidates(anyString(),any(),any(),anyInt())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("private database details"));
        var e=assertThrows(SubmissionFailure.class,()->reader.read(query(null),deadline()));assertNull(e.getCause());verify(connection).rollback();verify(connection,never()).commit();}
    @Test void standaloneCollectorDoesNotPromiseDurabilityAndOptionalComparisonDetectsChangedPopulation(){
        var r=candidate(1,"a","x",accepted.cutoff().minusSeconds(1));records(r);var first=reader.read(query(null),deadline());doReturn(2).when(repo).captureAttempt(any());
        assertEquals(first,reader.read(query(null),deadline()));
        assertEquals(first,reader.read(query(first.source().revision()),deadline()));records(r,candidate(2,"b","newly-committed",r.completed()));
        assertEquals(SubmissionFailure.Code.CUTOFF_NOT_REPRODUCIBLE,assertThrows(SubmissionFailure.class,()->reader.read(query(first.source().revision()),deadline())).code());
    }
    @Test void disabledRuleDoesNotRequireHistoryOrDatabase(){var base=accepted.configuration();var disabled=new RiskConfiguration(base.versionId(),base.rowVersion(),base.contentHash(),base.currencyCode(),base.maxFinalScore(),base.rules().stream()
        .map(r->new RiskConfiguration.Rule(r.code(),r.code()!=RuleCode.PREVIOUS_RISK_FLAGS,r.weight(),r.evaluationOrder(),r.parameterSchemaVersion(),r.parameters())).toList(),base.bands(),base.approvalBoundaries());accepted=AcceptanceFixtures.copy(accepted,disabled,accepted.paymentId(),null,accepted.cutoff(),accepted.currentAndRetrySubmissionIds(),null);
        assertEquals(RiskInput.Availability.UNAVAILABLE,reader.read(query(null),deadline()).evidence().availability());verifyNoInteractions(ds,repo);}
    @Test void noHistoryReadsInsideCallerWriteLocks(){new TransactionTemplate(tx).execute(status->{assertThrows(SubmissionFailure.class,()->reader.read(query(null),deadline()));return null;});verifyNoInteractions(repo);}
    @Test void expiredOrInterruptedDependencyBudgetDoesNotOpenConnection(){assertThrows(SubmissionFailure.class,()->reader.read(query(null),new SubmissionAdapters.Deadline(System.nanoTime()-1)));verifyNoInteractions(ds,repo);
        Thread.currentThread().interrupt();try{assertThrows(SubmissionFailure.class,()->reader.read(query(null),deadline()));}finally{Thread.interrupted();}}
    @Test void technicalCapacityIsValidated(){for(int limit:new int[]{0,-1,1001})assertThrows(IllegalArgumentException.class,()->new PriorAssessmentHistoryReader(repo,tx,limit));}
    @Test void actualEngineMatchesConfiguredFactorsOncePerSubmissionAndRejectsIncompleteHistory(){
        var r=candidate(1,"a","x",accepted.cutoff().minusSeconds(1));records(r);var prior=reader.read(query(null),deadline()).evidence();var base=FinalizationFixtures.input(false);var config=onlyPrevious(accepted.configuration());
        var input=new RiskInput(accepted.current(),accepted.cutoff(),accepted.currentAndRetrySubmissionIds(),RiskInput.Evidence.unavailable("456","INR",accepted.cutoff()),RiskInput.Evidence.unavailable("456","INR",accepted.cutoff()),RiskInput.Evidence.unavailable("456","INR",accepted.cutoff()),prior);
        var result=new RiskScoringEngine().evaluate(config,input);assertEquals(RiskResult.Status.COMPLETED,result.status());var factor=result.factors().stream().filter(f->f.ruleCode()==RuleCode.PREVIOUS_RISK_FLAGS).findFirst().orElseThrow();
        assertEquals(RiskResult.Outcome.TRIGGERED,factor.outcome());assertEquals(10,factor.contribution());assertEquals("1",factor.observations().get("flaggedSubmissions"));assertEquals(List.of("d2-risk-assessment:1"),factor.matchedEvidenceIds());
        var incomplete=new RiskInput.Evidence<RiskInput.PriorAssessment>(RiskInput.Availability.INCOMPLETE,null,"456","INR",accepted.cutoff(),prior.coveredFrom(),List.of());
        assertEquals(RiskResult.Status.TECHNICAL_FAILURE,new RiskScoringEngine().evaluate(config,new RiskInput(input.current(),input.cutoff(),input.currentAndRetrySubmissionIds(),input.payments(),input.supplier(),input.supplierTransitions(),incomplete)).status());
    }
    @Test void configuredWindowAndQualifiersDriveSelectionAndSingleSubmissionCounting(){
        var base=onlyPrevious(accepted.configuration());var configured=new RiskConfiguration(base.versionId(),base.rowVersion(),base.contentHash(),base.currencyCode(),base.maxFinalScore(),base.rules().stream()
            .map(r->r.code()==RuleCode.PREVIOUS_RISK_FLAGS?new RiskConfiguration.Rule(r.code(),true,r.weight(),r.evaluationOrder(),r.parameterSchemaVersion(),new RuleParameters.PreviousFlags(100,2,List.of(RuleCode.NEW_SUPPLIER))):r).toList(),base.bands(),base.approvalBoundaries());
        accepted=AcceptanceFixtures.copy(accepted,configured,accepted.paymentId(),null,accepted.cutoff(),accepted.currentAndRetrySubmissionIds(),null);
        var one=candidate(1,"a","one",accepted.cutoff().minusSeconds(1));var nonqualifying=candidate(2,"a","two",accepted.cutoff().minusSeconds(2));records(one,nonqualifying);
        when(repo.verify(one)).thenReturn(verified(one,"one",Set.of(RuleCode.NEW_SUPPLIER,RuleCode.STRUCTURING)));
        when(repo.verify(nonqualifying)).thenReturn(verified(nonqualifying,"two",Set.of(RuleCode.STRUCTURING)));
        var prior=reader.read(query(null),deadline()).evidence();verify(repo).candidates("456",accepted.cutoff().minusSeconds(100),accepted.cutoff(),capacity);
        var unavailable=RiskInput.Evidence.<RiskInput.Payment>unavailable("456","INR",accepted.cutoff());
        var input=new RiskInput(accepted.current(),accepted.cutoff(),accepted.currentAndRetrySubmissionIds(),unavailable,RiskInput.Evidence.unavailable("456","INR",accepted.cutoff()),RiskInput.Evidence.unavailable("456","INR",accepted.cutoff()),prior);
        var factor=new RiskScoringEngine().evaluate(configured,input).factors().stream().filter(f->f.ruleCode()==RuleCode.PREVIOUS_RISK_FLAGS).findFirst().orElseThrow();
        assertEquals(RiskResult.Outcome.NOT_TRIGGERED,factor.outcome());assertEquals("1",factor.observations().get("flaggedSubmissions"));assertEquals(0,factor.contribution());
    }
    @Test void requestRejectsMalformedReplayHash(){assertThrows(IllegalArgumentException.class,()->new PriorAssessmentHistory.Request(accepted,Set.of(),"caller-invented"));}
}
