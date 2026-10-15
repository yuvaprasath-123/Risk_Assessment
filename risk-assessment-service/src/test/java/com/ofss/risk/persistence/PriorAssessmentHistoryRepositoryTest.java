package com.ofss.risk.persistence;

import com.ofss.risk.contract.RuleCode;
import com.ofss.risk.submission.SubmissionFailure;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.ofss.risk.persistence.PriorHistoryFixtures.*;
import static com.ofss.risk.persistence.PriorAssessmentHistoryRepository.*;

class PriorAssessmentHistoryRepositoryTest {
    DataSource ds=mock(DataSource.class);Connection connection=mock(Connection.class);PreparedStatement ps=mock(PreparedStatement.class);ResultSet rs=mock(ResultSet.class);
    RiskAcceptanceRepository acceptance=mock(RiskAcceptanceRepository.class);RiskWorkRepository work=mock(RiskWorkRepository.class);AssessmentReadRepository reads=mock(AssessmentReadRepository.class);
    PriorAssessmentHistoryRepository repo;TransactionTemplate tx;Candidate candidate=candidate(17,"original","key",AcceptanceFixtures.CUTOFF.minusNanos(1));AcceptedAssessment stored;
    @BeforeEach void setup()throws Exception{
        when(ds.getConnection()).thenReturn(connection);when(connection.getAutoCommit()).thenReturn(true);when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        when(connection.prepareStatement(CANDIDATES)).thenReturn(ps);when(ps.executeQuery()).thenReturn(rs);when(rs.next()).thenReturn(true,false);
        when(rs.getObject("ASSESSMENT_ID")).thenReturn(BigDecimal.valueOf(17));when(rs.getObject("SUPPLIER_ID")).thenReturn(new BigDecimal("456"));when(rs.getString("CURRENCY_CODE")).thenReturn("INR");
        when(rs.getString("SUBMISSION_SOURCE")).thenReturn("original");when(rs.getString("SUBMISSION_KEY")).thenReturn("key");when(rs.getString("WORK_STATE")).thenReturn("COMPLETED");when(rs.getObject("COMPLETED_AT",OffsetDateTime.class)).thenReturn(candidate.completed().atOffset(ZoneOffset.ofHoursMinutes(5,30)));
        repo=new PriorAssessmentHistoryRepository(new JdbcTemplate(ds),acceptance,work,reads);tx=new TransactionTemplate(new JdbcTransactionManager(ds));tx.setReadOnly(true);
        var a=request();var canon=new CanonicalAcceptance();var config=canon.configuration(a.configuration());
        var changed=new AcceptanceRequest(candidate.identity(),a.paymentId(),a.requesterId(),a.validationReference(),null,a.current(),a.cutoff(),a.currentAndRetrySubmissionIds(),a.evaluatorVersion(),a.configuration(),null);
        var facts=canon.acceptance(changed,a.configuration().versionId(),a.configuration().rowVersion(),config.hash());
        stored=new AcceptedAssessment("17",candidate.identity(),"2",a.configuration().versionId(),a.configuration().rowVersion(),"3","COMPLETED",a.cutoff(),a.cutoff(),facts.hash(),config.hash(),facts.json(),config.json());
        when(acceptance.findById(17)).thenAnswer(i->Optional.of(stored));when(reads.read(17)).thenReturn(view(candidate));
    }
    @Test void bindsSupplierAndExactUtcNanosecondWindowAndCapacitySentinel()throws Exception{
        var from=AcceptanceFixtures.CUTOFF.minusSeconds(7776000);var rows=tx.execute(status->repo.candidates("456",from,AcceptanceFixtures.CUTOFF,3));assertEquals(List.of(candidate),rows);
        verify(ps).setLong(1,456);verify(ps).setObject(2,from.atOffset(ZoneOffset.UTC),Types.TIMESTAMP_WITH_TIMEZONE);verify(ps).setObject(3,AcceptanceFixtures.CUTOFF.atOffset(ZoneOffset.UTC),Types.TIMESTAMP_WITH_TIMEZONE);verify(ps).setInt(4,4);
        assertTrue(CANDIDATES.contains("WORK_STATE='COMPLETED'"));assertTrue(CANDIDATES.contains("COMPLETED_AT>=? AND COMPLETED_AT<?"));assertTrue(CANDIDATES.contains("ORDER BY COMPLETED_AT,SUBMISSION_SOURCE,SUBMISSION_KEY,ASSESSMENT_ID"));
        assertFalse(CANDIDATES.contains("REQUESTER_ID"));assertFalse(CANDIDATES.contains("FOR UPDATE"));assertFalse(CANDIDATES.contains("EVALUATED_AT"));
    }
    @Test void preservesZeroWeightTriggeredFactorsAndRemovesRecursiveFlag(){
        var before=view(candidate);var factors=before.factors().stream().map(f->new AssessmentReadView.Factor(f.ruleCode(),f.evaluationOrder(),f.ruleCode().equals("NEW_SUPPLIER")||f.ruleCode().equals("PREVIOUS_RISK_FLAGS")?"TRIGGERED":"NOT_TRIGGERED",0,0,f.explanation())).toList();
        when(reads.read(17)).thenReturn(new AssessmentReadView(before.assessmentId(),before.rowVersion(),before.status(),before.createdAt(),before.cutoff(),before.evaluatedAt(),before.completedAt(),before.configurationVersionId(),before.configurationRowVersion(),before.evaluatorVersion(),before.result(),factors,before.snapshotId(),before.configurationHash(),before.configuration()));
        var result=tx.execute(status->repo.verify(candidate));assertEquals(Set.of(RuleCode.NEW_SUPPLIER),result.prior().triggeredFactors());assertEquals(candidate.completed(),result.prior().assessedAt());assertEquals("opaque-engine-id",result.originalSubmissionId());assertEquals("d2-risk-assessment:17",result.prior().evidenceId());
        verify(reads).read(17);assertFalse(result.toString().contains("invoiceNumber"));assertFalse(result.toString().contains("lease"));
    }
    @ParameterizedTest @ValueSource(strings={"wrong-supplier","wrong-currency","wrong-identity","wrong-time","missing-parent","verifier-failure"})
    void inconsistentPersistedResultsNeverBecomeUsablePriorEvidence(String defect){
        var row=switch(defect){case "wrong-supplier"->new Candidate(17,candidate.identity(),"999","INR",candidate.completed());case "wrong-currency"->new Candidate(17,candidate.identity(),"456","USD",candidate.completed());
            case "wrong-identity"->candidate(17,"different","key",candidate.completed());case "wrong-time"->candidate(17,"original","key",candidate.completed().plusNanos(1));default->candidate;};
        if(defect.equals("missing-parent"))when(acceptance.findById(17)).thenReturn(Optional.empty());if(defect.equals("verifier-failure"))when(reads.read(17)).thenThrow(new AssessmentReadFailure(AssessmentReadFailure.Code.ASSESSMENT_UNAVAILABLE));
        assertThrows(RuntimeException.class,()->tx.execute(status->repo.verify(row)));
    }
    @ParameterizedTest @ValueSource(strings={"fractional-id","overflow-id","null-time","noncompleted"})
    void invalidJdbcValuesRejected(String defect)throws Exception{
        if(defect.equals("fractional-id"))when(rs.getObject("ASSESSMENT_ID")).thenReturn(new BigDecimal("17.1"));if(defect.equals("overflow-id"))when(rs.getObject("ASSESSMENT_ID")).thenReturn(new BigDecimal("9223372036854775808"));
        if(defect.equals("null-time"))when(rs.getObject("COMPLETED_AT",OffsetDateTime.class)).thenReturn(null);if(defect.equals("noncompleted"))when(rs.getString("WORK_STATE")).thenReturn("BLOCKED");
        assertThrows(RuntimeException.class,()->tx.execute(status->repo.candidates("456",AcceptanceFixtures.CUTOFF.minusSeconds(10),AcceptanceFixtures.CUTOFF,3)));
    }
    @Test void currentCaptureRequiresVerifiedPinnedFactsAndActiveAttempt(){
        var a=request();var canonical=new CanonicalAcceptance();var c=canonical.configuration(a.configuration());var facts=canonical.acceptance(a,a.configuration().versionId(),a.configuration().rowVersion(),c.hash());
        var active=new AcceptedAssessment("19",a.identity(),"2",a.configuration().versionId(),a.configuration().rowVersion(),"4","IN_PROGRESS",a.cutoff(),a.cutoff(),facts.hash(),c.hash(),facts.json(),c.json());
        when(acceptance.find(a.identity())).thenReturn(Optional.of(active));when(work.read(19)).thenReturn(new RiskWorkRepository.Row(19,2,"IN_PROGRESS","not-exposed",1,1,4,a.cutoff().plusSeconds(10),a.cutoff(),a.cutoff(),null,null));
        assertEquals(1,tx.execute(status->repo.captureAttempt(a)).intValue());
        when(work.read(19)).thenReturn(new RiskWorkRepository.Row(19,2,"COMPLETED",null,1,1,4,null,a.cutoff(),a.cutoff(),null,null));assertThrows(SubmissionFailure.class,()->tx.execute(status->repo.captureAttempt(a)));
    }
    @Test void repositoryRejectsUnboundAndWriteTransactions(){assertThrows(SubmissionFailure.class,()->repo.verify(candidate));tx.setReadOnly(false);assertThrows(SubmissionFailure.class,()->tx.execute(status->repo.verify(candidate)));verifyNoInteractions(reads,acceptance);}
}
