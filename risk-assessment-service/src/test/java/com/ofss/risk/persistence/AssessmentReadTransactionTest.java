package com.ofss.risk.persistence;

import com.ofss.risk.engine.*;
import java.sql.*;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real Spring/JDBC transaction path, mocked driver/repository collaborators; not live Oracle verification. */
class AssessmentReadTransactionTest {
    final DataSource ds=mock(DataSource.class); final Connection connection=mock(Connection.class); final Statement begin=mock(Statement.class);
    final PreparedStatement metadata=mock(PreparedStatement.class);
    final RiskAcceptanceRepository acceptance=mock(RiskAcceptanceRepository.class); final RiskWorkRepository work=mock(RiskWorkRepository.class);
    final RiskEvidenceRepository evidence=mock(RiskEvidenceRepository.class); final RiskFinalizationRepository results=mock(RiskFinalizationRepository.class);
    FrozenAssessmentLoader.Snapshot s=FinalizationFixtures.completed(FinalizationFixtures.snapshot());
    AssessmentReadService service; JdbcTransactionManager manager; AssessmentReadRepository repository;
    @BeforeEach void setup() throws Exception {
        when(ds.getConnection()).thenReturn(connection); when(connection.getAutoCommit()).thenReturn(true); when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        when(connection.createStatement()).thenReturn(begin); when(connection.prepareStatement(AssessmentReadRepository.METADATA)).thenReturn(metadata);
        var jdbc=new JdbcTemplate(ds); manager=new JdbcTransactionManager(ds); repository=new AssessmentReadRepository(jdbc,acceptance,work,evidence,results);
        service=new AssessmentReadService(repository,manager);
        doAnswer(c->{new RiskFinalizationRepository(jdbc).beginSnapshot(); return null;}).when(results).beginSnapshot();
        when(acceptance.findById(17)).thenAnswer(c->{assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly()); assertTrue(TransactionSynchronizationManager.hasResource(ds)); return Optional.of(s.accepted());});
        when(work.read(17)).thenAnswer(c->s.work()); when(evidence.header(17)).thenAnswer(c->s.header()); when(evidence.children(17)).thenAnswer(c->s.evidence());
        when(metadata.executeQuery()).thenAnswer(c->metadata());
        when(results.readPersisted(any())).thenAnswer(c->{
            var frozen=(FrozenAssessmentLoader.Snapshot)c.getArgument(0); var result=new RiskScoringEngine().evaluate(frozen.config(),frozen.input());
            return new CompletedAssessment("17",s.accepted().rowVersion(),s.evaluator(),s.accepted().acceptanceHash(),s.accepted().configurationHash(),s.header().hash(),FreezeFixtures.NOW,FreezeFixtures.NOW,result);
        });
    }
    ResultSet metadata() throws Exception {
        var rs=mock(ResultSet.class); when(rs.next()).thenReturn(true,false);
        when(rs.getObject("ROW_VERSION")).thenReturn(BigDecimal.valueOf(s.work().revision())); when(rs.getString("WORK_STATE")).thenReturn(s.work().state()); return rs;
    }
    void state(String state) {
        var old=s.accepted(); var r=s.work(); var row=new RiskWorkRepository.Row(r.id(),r.snapshot(),state,state.equals("IN_PROGRESS")?"private-lease":null,r.epoch(),r.attempt(),r.revision(),state.equals("IN_PROGRESS")?FreezeFixtures.NOW.plusSeconds(60):null,
            r.created(),r.cutoff(),Set.of("BLOCKED","RETRYABLE_FAILURE").contains(state)?"PRIVATE_DIAGNOSTIC":null,null);
        var accepted=new AcceptedAssessment(old.assessmentId(),old.identity(),old.configurationSnapshotId(),old.configurationVersionId(),old.configurationRowVersion(),old.rowVersion(),state,old.cutoff(),old.createdAt(),old.acceptanceHash(),old.configurationHash(),old.acceptanceJson(),old.configurationJson());
        s=new FrozenAssessmentLoader.Snapshot(accepted,row,s.header(),s.evidence(),s.config(),s.input(),s.evaluator());
    }
    @Test void verifiedCompletedProjectionUsesOneReadOnlySnapshotAndNoLocksOrWrites() throws Exception {
        var v=service.get("17"); assertEquals(135,v.result().rawScore()); assertEquals(100,v.result().finalScore()); assertEquals("HIGH",v.result().band());
        assertEquals(7,v.factors().size()); assertEquals("2026-10-14T10:00:00.123456789Z",v.completedAt());
        var parameters=(Map<?,?>)((List<?>)v.configuration().get("rules")).stream().map(x->(Map<?,?>)x).filter(x->x.get("code").equals("UNUSUAL_AMOUNT")).findFirst().orElseThrow().get("parameters");
        assertEquals("1.0000000000000001",parameters.get("meanMultiplier"));
        verify(ds).getConnection(); verify(connection).setReadOnly(true); verify(connection).commit(); verify(connection,never()).rollback();
        var order=inOrder(begin,acceptance,metadata,connection); order.verify(begin).executeUpdate("SET TRANSACTION READ ONLY"); order.verify(acceptance).findById(17); order.verify(metadata).executeQuery(); order.verify(connection).commit();
        verify(work,never()).lock(anyLong()); verify(results,never()).insert(any(),any(),anyString(),any()); verify(results,never()).complete(any(),any(),any(),any());
        assertFalse(v.toString().contains("synthetic-lease")); assertFalse(v.toString().contains("original-flags-"));
    }
    @ParameterizedTest @ValueSource(strings={"ACCEPTED","IN_PROGRESS","RETRYABLE_FAILURE","BLOCKED"}) void unfinishedStatesHaveNoInventedResultEvenWithFrozenEvidence(String state) {
        state(state); var v=service.get("17"); assertEquals(state,v.status()); assertNull(v.result()); assertNull(v.factors()); assertNull(v.completedAt()); assertNotNull(v.configuration());
        verify(results,never()).readPersisted(any()); assertFalse(v.toString().contains("PRIVATE_DIAGNOSTIC")); assertFalse(v.toString().contains("private-lease"));
    }
    @Test void acceptedWithoutInputsStillHasAnImmutableConfigurationSnapshot() {
        state("ACCEPTED"); when(evidence.header(17)).thenReturn(new RiskEvidenceRepository.Header(null,null,null)); when(evidence.children(17)).thenReturn(List.of());
        var v=service.get("17"); assertNull(v.result()); assertEquals(s.accepted().configurationSnapshotId(),v.snapshotId());
    }
    @Test void knownDurablePriorCaptureIsReadableAsUnfinishedAndIsNotMistakenForFrozenInput(){
        state("IN_PROGRESS");var bridge=new SubmissionSnapshotReader(acceptance,work,evidence,manager);var original=bridge.restore(s.accepted());
        var request=new PriorAssessmentHistory.Request(original,Set.of(),null);var capture=PriorHistoryCaptureCodec.encode(request,PriorCaptureFixtures.history(request,1),FreezeFixtures.NOW).withId(100);
        when(evidence.header(17)).thenReturn(new RiskEvidenceRepository.Header(null,null,null));when(evidence.children(17)).thenReturn(List.of(capture));
        when(acceptance.find(s.accepted().identity())).thenReturn(Optional.of(s.accepted()));
        var view=service.get("17");assertEquals("IN_PROGRESS",view.status());assertNull(view.result());assertNull(view.factors());assertNull(bridge.frozenHash(s.accepted().identity()));
        assertFalse(view.toString().contains("old-ಕನ್ನಡ"));verify(results,never()).readPersisted(any());
    }
    @ParameterizedTest @NullSource @ValueSource(strings={"0","-1","1.0","1e2","bad","9223372036854775808","17 OR 1=1"}) void invalidIdsNeverOpenConnection(String id) {
        assertEquals(AssessmentReadFailure.Code.INVALID_ASSESSMENT_ID,assertThrows(AssessmentReadFailure.class,()->service.get(id)).code()); verifyNoInteractions(ds,acceptance,results);
    }
    @Test void missingAssessmentIs404RatherThanCorruptAssociation() throws Exception {
        doReturn(Optional.empty()).when(acceptance).findById(17);
        assertEquals(AssessmentReadFailure.Code.ASSESSMENT_NOT_FOUND,assertThrows(AssessmentReadFailure.class,()->service.get("17")).code()); verify(connection).rollback();
    }
    @ParameterizedTest @ValueSource(strings={"result-columns","orphan-factors","orphan-input","missing-completed-input","changed-child","metadata-version"}) void corruptAssociationsFailClosedWithoutReturningPartialData(String defect) throws Exception {
        if(defect.equals("result-columns")) {state("BLOCKED"); when(metadata.executeQuery()).thenAnswer(c->{var rs=metadata(); when(rs.getObject("FINAL_SCORE")).thenReturn(BigDecimal.ZERO); return rs;});}
        if(defect.equals("orphan-factors")) {state("ACCEPTED"); when(results.hasFactors(17)).thenReturn(true);}
        if(defect.equals("orphan-input")) {state("ACCEPTED"); when(evidence.header(17)).thenReturn(new RiskEvidenceRepository.Header(null,null,null));}
        if(defect.equals("missing-completed-input")) {when(evidence.header(17)).thenReturn(new RiskEvidenceRepository.Header(null,null,null));when(evidence.children(17)).thenReturn(List.of());}
        if(defect.equals("changed-child")) when(evidence.children(17)).thenReturn(s.evidence().subList(1,6));
        if(defect.equals("metadata-version")) when(metadata.executeQuery()).thenAnswer(c->{var rs=metadata(); when(rs.getObject("ROW_VERSION")).thenReturn(BigDecimal.valueOf(99)); return rs;});
        assertEquals(AssessmentReadFailure.Code.ASSESSMENT_UNAVAILABLE,assertThrows(AssessmentReadFailure.class,()->service.get("17")).code()); verify(connection).rollback(); verify(connection,never()).commit();
    }
    @Test void databaseFailureDoesNotExposeItsCauseAndRollsBackTheReadTransaction() throws Exception {
        when(metadata.executeQuery()).thenThrow(new SQLException("ORA-private credential payload"));
        var failure=assertThrows(AssessmentReadFailure.class,()->service.get("17")); assertEquals(AssessmentReadFailure.Code.ASSESSMENT_UNAVAILABLE,failure.code()); assertNull(failure.getCause()); verify(connection).rollback();
    }
    @Test void repositoryRequiresItsBoundReadOnlyTransaction() {
        assertThrows(AssessmentReadFailure.class,()->repository.read(17));
        new TransactionTemplate(manager).execute(status->{assertThrows(AssessmentReadFailure.class,()->repository.read(17)); return null;}); verifyNoInteractions(acceptance);
    }
}
