package com.ofss.risk.persistence;

import com.ofss.risk.engine.*;
import com.ofss.risk.submission.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real transaction manager; mock driver/store with rollback restoration. Not live Oracle proof. */
class PriorHistoryCaptureServiceTest {
    DataSource ds=mock(DataSource.class);Connection connection=mock(Connection.class);Statement begin=mock(Statement.class);
    PriorAssessmentHistoryReader reader=mock(PriorAssessmentHistoryReader.class);RiskAcceptanceRepository acceptance=mock(RiskAcceptanceRepository.class);
    RiskWorkRepository work=mock(RiskWorkRepository.class);RiskEvidenceRepository evidence=mock(RiskEvidenceRepository.class);
    AcceptanceRequest a=FreezeFixtures.acceptance();PriorAssessmentHistory.Request request=new PriorAssessmentHistory.Request(a,Set.of(),null);
    List<RiskEvidenceRepository.Stored> rows=new ArrayList<>(),beforeRows;RiskWorkRepository.Row parent=FreezeFixtures.row(),beforeParent;
    RiskEvidenceRepository.Header header=new RiskEvidenceRepository.Header(null,null,null),beforeHeader;JdbcTransactionManager tx;PriorHistoryCaptureService service;
    SubmissionAdapters.Deadline deadline(){return new SubmissionAdapters.Deadline(System.nanoTime()+Duration.ofSeconds(5).toNanos());}
    AcceptedAssessment stored(){var s=FreezeFixtures.stored(a,parent.revision());return new AcceptedAssessment(s.assessmentId(),s.identity(),s.configurationSnapshotId(),s.configurationVersionId(),s.configurationRowVersion(),s.rowVersion(),parent.state(),s.cutoff(),s.createdAt(),s.acceptanceHash(),s.configurationHash(),s.acceptanceJson(),s.configurationJson());}
    RiskWorkRepository.Row revision(long revision){var p=parent;return new RiskWorkRepository.Row(p.id(),p.snapshot(),p.state(),p.token(),p.epoch(),p.attempt(),revision,p.expiry(),p.created(),p.cutoff(),p.errorCode(),p.errorMessage());}
    @BeforeEach void setup()throws Exception{
        when(ds.getConnection()).thenReturn(connection);when(connection.getAutoCommit()).thenReturn(true);when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);when(connection.createStatement()).thenReturn(begin);
        doAnswer(i->{beforeRows=new ArrayList<>(rows);beforeParent=parent;beforeHeader=header;return null;}).when(connection).setAutoCommit(false);
        doAnswer(i->{rows=new ArrayList<>(beforeRows);parent=beforeParent;header=beforeHeader;return null;}).when(connection).rollback();
        tx=new JdbcTransactionManager(ds);service=new PriorHistoryCaptureService(reader,acceptance,work,evidence,tx,Clock.fixed(FreezeFixtures.NOW,ZoneOffset.UTC));
        doAnswer(i->{new RiskFinalizationRepository(new JdbcTemplate(ds)).beginSnapshot();return null;}).when(work).beginSnapshot();
        when(acceptance.find(a.identity())).thenAnswer(i->Optional.of(stored()));when(work.lock(17)).thenAnswer(i->parent);when(work.read(17)).thenAnswer(i->parent);
        when(evidence.children(17)).thenAnswer(i->List.copyOf(rows));when(evidence.header(17)).thenAnswer(i->header);
        when(evidence.insert(eq(17L),any())).thenAnswer(i->{RiskEvidenceRepository.Stored row=i.getArgument(1);var saved=row.withId(100+rows.size());rows.add(saved);return saved;});
        doAnswer(i->{parent=revision(i.getArgument(1));return null;}).when(evidence).captureFence(any(),anyLong());
        when(reader.read(eq(request),any())).thenReturn(PriorCaptureFixtures.history(request,1));
    }
    PriorHistoryCaptureService.Captured capture(){var candidate=service.loadOrCollect(request,deadline());return service.persist(parent.lease(),request,candidate);}
    @Test void capturePersistsFullPopulationBeforeReturningAndDoesNotFreezeFinalInput()throws Exception{
        var saved=capture();assertEquals("2",saved.lease().rowVersion());assertEquals(1,rows.size());assertEquals("LOCAL_PRIOR_POPULATION",rows.getFirst().key());
        assertEquals(saved.history(),PriorHistoryCaptureCodec.decode(rows.getFirst(),stored()));assertTrue(rows.getFirst().json().contains("old-ಕನ್ನಡ😀"));assertNull(header.hash());assertNull(header.manifest());
        verify(evidence).captureFence(any(),eq(2L));verify(evidence,never()).associate(any(),any(),anyLong());verify(connection,times(2)).commit();
    }
    @Test void replayAfterChangedHistoryAndLeaseTakeoverNeverQueriesPopulationAgain(){
        var first=capture();doThrow(new AssertionError("History must never be queried after capture")).when(reader).read(any(),any());
        parent=new RiskWorkRepository.Row(17,23,"IN_PROGRESS","replacement-worker",2,2,4,FreezeFixtures.NOW.plusSeconds(120),parent.created(),parent.cutoff(),null,null);
        var replay=service.loadOrCollect(request,deadline());assertEquals(first.history(),replay);assertEquals(first.history(),service.loadRequired(request));
        var second=service.persist(parent.lease(),request,replay);assertEquals("4",second.lease().rowVersion());assertEquals(first.history(),second.history());assertEquals(1,rows.size());
        verify(reader,times(1)).read(eq(request),any());verify(evidence,times(1)).insert(anyLong(),any());
    }
    @ParameterizedTest @ValueSource(strings={"expired-before","superseded","expired-after-insert","readback"})
    void failedCaptureCannotLeavePartialWritesOrSuccessfulResponse(String fault)throws Exception{
        var lease=parent.lease();var candidate=PriorCaptureFixtures.history(request,1);
        if(fault.equals("expired-before"))parent=new RiskWorkRepository.Row(17,23,"IN_PROGRESS",parent.token(),1,1,1,FreezeFixtures.NOW,parent.created(),parent.cutoff(),null,null);
        if(fault.equals("superseded"))parent=new RiskWorkRepository.Row(17,23,"IN_PROGRESS","other-worker",2,2,3,FreezeFixtures.NOW.plusSeconds(60),parent.created(),parent.cutoff(),null,null);
        if(fault.equals("expired-after-insert"))doThrow(new WorkFailure(WorkFailure.Code.LEASE_REJECTED)).when(evidence).captureFence(any(),anyLong());
        if(fault.equals("readback"))when(work.read(17)).thenThrow(new IllegalStateException("private failure"));
        assertThrows(RuntimeException.class,()->service.persist(lease,request,candidate));assertTrue(rows.isEmpty());assertNull(header.hash());verify(connection).rollback();verify(connection,never()).commit();
        if(fault.equals("expired-after-insert")||fault.equals("readback"))verify(evidence).insert(anyLong(),any());else verify(evidence,never()).insert(anyLong(),any());
    }
    @Test void rejectedConcurrentOldRevisionCannotReplaceFirstWinner(){var old=parent.lease();var first=capture();var before=List.copyOf(rows);assertThrows(WorkFailure.class,()->service.persist(old,request,PriorCaptureFixtures.history(request,2)));assertEquals(before,rows);assertEquals(first.history(),service.loadRequired(request));}
    @Test void capacityFailureIsDurableAndNeverReplacedWithEmptyComplete(){
        var proposed=new PriorAssessmentHistory(new RiskInput.Evidence<>(RiskInput.Availability.INCOMPLETE,null,a.current().supplierId(),"INR",a.cutoff(),a.cutoff().minusSeconds(7776000),List.of()),new EvidenceFreezeRequest.Source("risk-assessment-service","supplier-completed-history/456",null));
        var saved=service.persist(parent.lease(),request,proposed);assertEquals(RiskInput.Availability.INCOMPLETE,saved.history().evidence().availability());assertEquals(saved.history(),service.loadRequired(request));verifyNoInteractions(reader);
    }
    @Test void tamperedPayloadMetadataOrProofFailsClosedAndDoesNotRecollect(){capture();var row=rows.getFirst();rows.set(0,new RiskEvidenceRepository.Stored(row.id(),row.recordClass(),row.key(),row.kind(),row.source(),row.snapshot(),row.format(),row.hashFormat(),row.cutoff(),row.coveredFrom(),row.windowEnd(),99,row.availability(),row.json(),row.hash(),row.captured()));
        assertThrows(SubmissionFailure.class,()->service.loadOrCollect(request,deadline()));verify(reader,times(1)).read(any(),any());}
    @Test void changedExclusionsRejectReplayRatherThanOverwritingTheOriginal(){capture();var before=List.copyOf(rows);var changed=new PriorAssessmentHistory.Request(a,Set.of(new SubmissionIdentity("different","retry")),null);assertThrows(SubmissionFailure.class,()->service.loadRequired(changed));assertEquals(before,rows);}
    @Test void fullFreezeReferencesCapturedPopulationAndRollbackRetainsOnlyCommittedCapture()throws Exception{
        var saved=capture();var captured=rows.getFirst();var base=FreezeFixtures.input();var input=new RiskInput(a.current(),a.cutoff(),a.currentAndRetrySubmissionIds(),base.payments(),base.supplier(),base.supplierTransitions(),saved.history().evidence());
        var sources=new EnumMap<>(FreezeFixtures.request(saved.lease(),a,input).sources());sources.put(EvidenceFreezeRequest.Kind.PRIOR_ASSESSMENTS,saved.history().source());
        var request=new EvidenceFreezeRequest(saved.lease(),a,input,sources);var freezer=new RiskEvidenceService(work,acceptance,evidence,tx,Clock.fixed(FreezeFixtures.NOW,ZoneOffset.UTC));
        doAnswer(i->{header=i.getArgument(1);parent=revision(i.getArgument(2));return null;}).when(evidence).associate(any(),any(),anyLong());
        var frozen=freezer.freeze(request);assertEquals(7,rows.size());assertEquals(captured,rows.stream().filter(PriorHistoryCaptureCodec::isCapture).findFirst().orElseThrow());
        assertEquals(frozen.input(),new FrozenAssessmentLoader(acceptance,work,evidence).loadStored(stored()).input());
    }
    @Test void partialFinalFreezeFailureRollsBackNewChildrenButKeepsCapture()throws Exception{
        var saved=capture();var committed=List.copyOf(rows);var base=FreezeFixtures.input();var input=new RiskInput(a.current(),a.cutoff(),a.currentAndRetrySubmissionIds(),base.payments(),base.supplier(),base.supplierTransitions(),saved.history().evidence());
        var sources=new EnumMap<>(FreezeFixtures.request(saved.lease(),a,input).sources());sources.put(EvidenceFreezeRequest.Kind.PRIOR_ASSESSMENTS,saved.history().source());
        when(evidence.insert(eq(17L),any())).thenAnswer(i->{RiskEvidenceRepository.Stored r=i.getArgument(1);if(r.key().equals("SUPPLIER"))throw new IllegalStateException("injected after first inserts");var value=r.withId(100+rows.size());rows.add(value);return value;});
        assertThrows(FreezeFailure.class,()->new RiskEvidenceService(work,acceptance,evidence,tx,Clock.fixed(FreezeFixtures.NOW,ZoneOffset.UTC)).freeze(new EvidenceFreezeRequest(saved.lease(),a,input,sources)));
        assertEquals(committed,rows);assertNull(header.hash());verify(connection).rollback();
    }
    @Test void captureFenceBindsEveryCapabilityAndOracleExpiry()throws Exception{
        var ps=mock(PreparedStatement.class);when(connection.prepareStatement(RiskEvidenceRepository.CAPTURE_FENCE)).thenReturn(ps);when(ps.executeUpdate()).thenReturn(1);
        new org.springframework.transaction.support.TransactionTemplate(tx).execute(status->{new RiskEvidenceRepository(new JdbcTemplate(ds)).captureFence(parent,2);return null;});
        verify(ps).setLong(1,2);verify(ps).setLong(2,17);verify(ps).setString(3,parent.token());verify(ps).setLong(4,parent.epoch());verify(ps).setInt(5,parent.attempt());verify(ps).setLong(6,parent.revision());verify(ps).setObject(7,parent.expiry().atOffset(ZoneOffset.UTC),Types.TIMESTAMP_WITH_TIMEZONE);
        assertTrue(RiskEvidenceRepository.CAPTURE_FENCE.contains("LEASE_EXPIRES_AT>SYSTIMESTAMP"));
        when(ps.executeUpdate()).thenReturn(0);assertThrows(WorkFailure.class,()->new org.springframework.transaction.support.TransactionTemplate(tx).execute(status->{new RiskEvidenceRepository(new JdbcTemplate(ds)).captureFence(parent,2);return null;}));
    }
}
