package com.ofss.risk.persistence;

import com.ofss.risk.engine.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import java.sql.Connection;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.ofss.risk.engine.RiskInput.*;

/** Real Spring transaction manager; mocked repository operations, not live database verification. */
class RiskEvidenceServiceTest {
    final RiskWorkRepository work=mock(RiskWorkRepository.class);
    final RiskAcceptanceRepository acceptance=mock(RiskAcceptanceRepository.class);
    final RiskEvidenceRepository evidence=mock(RiskEvidenceRepository.class);
    final DataSource ds=mock(DataSource.class); final Connection connection=mock(Connection.class);
    final AtomicReference<RiskWorkRepository.Row> parent=new AtomicReference<>(FreezeFixtures.row());
    final AtomicReference<RiskEvidenceRepository.Header> header=new AtomicReference<>(new RiskEvidenceRepository.Header(null,null,null));
    final List<RiskEvidenceRepository.Stored> children=new ArrayList<>(); final AtomicLong identities=new AtomicLong(100);
    RiskEvidenceService service; JdbcTransactionManager manager;
    @BeforeEach void setup() throws Exception {
        when(ds.getConnection()).thenReturn(connection); when(connection.getAutoCommit()).thenReturn(true);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED); manager=new JdbcTransactionManager(ds);
        service=service(FreezeFixtures.NOW);
        when(work.lock(17)).thenAnswer(c->parent.get()); when(work.read(17)).thenAnswer(c->parent.get());
        when(acceptance.find(FreezeFixtures.acceptance().identity())).thenAnswer(c->Optional.of(FreezeFixtures.stored(parent.get().revision())));
        when(evidence.header(17)).thenAnswer(c->header.get()); when(evidence.children(17)).thenAnswer(c->List.copyOf(children));
        when(evidence.insert(eq(17L),any())).thenAnswer(c->{ var saved=((RiskEvidenceRepository.Stored)c.getArgument(1)).withId(identities.getAndIncrement()); children.add(saved); return saved; });
        doAnswer(c->{ header.set(c.getArgument(1)); var before=parent.get(); parent.set(new RiskWorkRepository.Row(before.id(),before.snapshot(),before.state(),before.token(),before.epoch(),before.attempt(),
            c.getArgument(2),before.expiry(),before.created(),before.cutoff(),null,null)); return null; }).when(evidence).associate(any(),any(),anyLong());
        doAnswer(c->{ parent.set(c.getArgument(1)); return null; }).when(work).update(any(),any(),anyBoolean());
    }
    RiskEvidenceService service(Instant now) { return new RiskEvidenceService(work,acceptance,evidence,manager,Clock.fixed(now,ZoneOffset.UTC)); }
    EvidenceFreezeRequest request() { return FreezeFixtures.request(parent.get().lease(),FreezeFixtures.input()); }
    FreezeFailure failure(FreezeFailure.Code code,Runnable action) {
        var e=assertThrows(FreezeFailure.class,action::run); assertEquals(code,e.code()); assertNull(e.getCause()); return e;
    }
    @Test void fullBundleFreezesFiveInputsAndManifestThenAssociatesAndReturnsRefreshedFence() throws Exception {
        var result=service.freeze(request()); assertEquals(6,children.size()); assertEquals("105",result.manifestId());
        assertEquals("2",result.lease().rowVersion()); assertEquals(FreezeFixtures.NOW,result.frozenAt());
        assertNotEquals(result.inputHash(),result.manifestHash()); assertEquals(result.inputHash(),header.get().hash());
        for(var child:children) {
            assertEquals(CanonicalEvidence.HASH_FORMAT,child.hashFormat()); assertEquals(CanonicalAcceptance.hash(child.json()),child.hash());
            assertEquals(FreezeFixtures.NOW,child.captured()); assertEquals(FreezeFixtures.acceptance().cutoff(),child.cutoff());
        }
        var order=inOrder(evidence,connection);
        order.verify(evidence,times(6)).insert(eq(17L),any()); order.verify(evidence).associate(any(),any(),eq(2L)); order.verify(connection).commit();
        verify(work,never()).diagnostic(any(),any(),any()); verify(connection,never()).rollback();
        var manifest=CanonicalAcceptance.read(children.getLast().json());
        assertEquals(5,((List<?>)manifest.get("references")).size()); assertEquals(result.inputHash(),manifest.get("inputHash"));
    }
    @Test void identicalRetryReusesAllIdentitiesHashesAndFrozenTimestampWithoutIncrement() throws Exception {
        var first=service.freeze(request()); var saved=List.copyOf(children); clearInvocations(evidence,connection);
        var second=service.freeze(request()); assertEquals(first,second); assertEquals(saved,children);
        verify(evidence,never()).insert(anyLong(),any()); verify(evidence,never()).associate(any(),any(),anyLong());
        verify(evidence).reuse(parent.get(),header.get()); verify(connection).commit();
    }
    @Test void differingProvenanceConflictsWithoutChildWrites() throws Exception {
        service.freeze(request()); var saved=List.copyOf(children); var before=header.get(); clearInvocations(evidence,connection);
        var original=request(); var sources=new EnumMap<>(original.sources());
        sources.put(EvidenceFreezeRequest.Kind.PAYMENT_HISTORY,new EvidenceFreezeRequest.Source("synthetic-adapter","changed",null));
        failure(FreezeFailure.Code.FROZEN_EVIDENCE_CONFLICT,()->service.freeze(new EvidenceFreezeRequest(original.lease(),original.acceptance(),original.input(),sources)));
        assertEquals(saved,children); assertEquals(before,header.get()); verify(evidence,never()).insert(anyLong(),any()); verify(connection).rollback(); verify(connection,never()).commit();
    }
    @ParameterizedTest @ValueSource(strings={"UNAVAILABLE","INCOMPLETE"}) void partialRequiredCaptureDoesNotWriteAnyRows(String status) throws Exception {
        var base=FreezeFixtures.input(); var incomplete=new Evidence<Payment>(Availability.valueOf(status),null,"456","INR",base.cutoff(),null,List.of());
        var input=FreezeFixtures.input(incomplete,base.supplier(),base.supplierTransitions(),base.previousAssessments());
        failure(FreezeFailure.Code.EVIDENCE_NOT_READY,()->service.freeze(FreezeFixtures.request(parent.get().lease(),input)));
        assertTrue(children.isEmpty()); verify(evidence,never()).insert(anyLong(),any()); verify(connection).rollback();
    }
    @Test void disabledDependenciesRetainUnavailableEnvelopesAndNeverBecomeEmptyCompleteHistory() {
        var original=FreezeFixtures.acceptance(); var c=original.configuration();
        var disabled=new RiskConfiguration(c.versionId(),c.rowVersion(),c.contentHash(),c.currencyCode(),c.maxFinalScore(),c.rules().stream()
            .map(r->new RiskConfiguration.Rule(r.code(),false,r.weight(),r.evaluationOrder(),r.parameterSchemaVersion(),r.parameters())).toList(),c.bands(),c.approvalBoundaries());
        var supplied=AcceptanceFixtures.copy(original,disabled,original.paymentId(),original.validationRevision(),original.cutoff(),original.currentAndRetrySubmissionIds(),null);
        var canonical=new CanonicalAcceptance(); var config=canonical.configuration(disabled); var accepted=canonical.acceptance(supplied,disabled.versionId(),disabled.rowVersion(),config.hash());
        var stored=FreezeFixtures.stored(1);
        doReturn(Optional.of(new AcceptedAssessment(stored.assessmentId(),stored.identity(),stored.configurationSnapshotId(),stored.configurationVersionId(),stored.configurationRowVersion(),"1","IN_PROGRESS",
            stored.cutoff(),stored.createdAt(),accepted.hash(),config.hash(),accepted.json(),config.json()))).when(acceptance).find(original.identity());
        var current=original.current(); var cutoff=original.cutoff(); var input=new RiskInput(current,cutoff,original.currentAndRetrySubmissionIds(),
            Evidence.unavailable("456","INR",cutoff),Evidence.unavailable("456","INR",cutoff),Evidence.unavailable("456","INR",cutoff),Evidence.unavailable("456","INR",cutoff));
        service.freeze(FreezeFixtures.request(parent.get().lease(),supplied,input));
        assertEquals(4,children.stream().filter(r->"UNAVAILABLE".equals(r.availability())).count());
        assertEquals("COMPLETE",children.getLast().availability());
        assertTrue(children.getLast().json().contains("UNAVAILABLE"));
    }
    @ParameterizedTest @ValueSource(strings={"facts","configuration","cutoff","exclusions"}) void changedAcceptedFactsCannotFreeze(String change) throws Exception {
        var original=FreezeFixtures.acceptance(); var config=original.configuration();
        if(change.equals("configuration")) config=new RiskConfiguration("2",config.rowVersion(),config.contentHash(),config.currencyCode(),config.maxFinalScore(),config.rules(),config.bands(),config.approvalBoundaries());
        var supplied=AcceptanceFixtures.copy(original,config,change.equals("facts")?999:original.paymentId(),original.validationRevision(),
            change.equals("cutoff")?original.cutoff().plusNanos(1):original.cutoff(),change.equals("exclusions")?Set.of(original.current().submissionId()):original.currentAndRetrySubmissionIds(),null);
        failure(FreezeFailure.Code.EVIDENCE_MISMATCH,()->service.freeze(FreezeFixtures.request(parent.get().lease(),supplied,FreezeFixtures.input())));
        verify(evidence,never()).insert(anyLong(),any()); verify(connection).rollback();
    }
    @Test void failureAndTakeoverKeepFrozenChildrenAndRetryUsesOriginalInputAtOriginalCutoff() {
        var frozen=service.freeze(request()); var saved=List.copyOf(children);
        var worker=new RiskWorkService(work,manager,Clock.fixed(FreezeFixtures.NOW,ZoneOffset.UTC),Duration.ofSeconds(60));
        String revision=worker.fail(frozen.lease(),WorkDiagnostic.TRANSIENT_FAILURE);
        assertEquals(saved,children); var claimed=worker.claim("17",revision);
        var reused=service.freeze(FreezeFixtures.request(claimed,FreezeFixtures.input()));
        assertEquals(frozen.manifestId(),reused.manifestId()); assertEquals(frozen.inputHash(),reused.inputHash()); assertEquals(frozen.frozenAt(),reused.frozenAt());
        assertEquals(saved,children);
        var later=FreezeFixtures.NOW.plusSeconds(61); var recovery=new RiskWorkService(work,manager,Clock.fixed(later,ZoneOffset.UTC),Duration.ofSeconds(60));
        var after=recovery.claim("17",parent.get().lease().rowVersion());
        var third=service(later).freeze(FreezeFixtures.request(after,FreezeFixtures.input()));
        assertEquals(frozen.manifestId(),third.manifestId()); assertEquals(saved,children);
    }
    @Test void staleOrExpiredWorkersCannotEvenInspectOrMutateInputRows() throws Exception {
        var row=parent.get(); var stale=new WorkLease("17","different",row.epoch(),row.attempt(),"1",row.expiry());
        assertEquals(WorkFailure.Code.LEASE_REJECTED,assertThrows(WorkFailure.class,()->service.freeze(FreezeFixtures.request(stale,FreezeFixtures.input()))).code());
        assertEquals(WorkFailure.Code.LEASE_REJECTED,assertThrows(WorkFailure.class,()->service(row.expiry()).freeze(request())).code());
        verifyNoInteractions(evidence); verify(connection,times(2)).rollback();
    }
    @Test void orphanPartialRowsAreRefusedWithoutOverwrite() throws Exception {
        var sample=mock(RiskEvidenceRepository.Stored.class); children.add(sample);
        failure(FreezeFailure.Code.EVIDENCE_PERSISTENCE_UNAVAILABLE,()->service.freeze(request()));
        verify(evidence,never()).insert(anyLong(),any()); verify(connection).rollback();
    }
    @Test void rowVersionExhaustionPrecedesAnyChildWrite() throws Exception {
        var row=parent.get(); parent.set(new RiskWorkRepository.Row(row.id(),row.snapshot(),row.state(),row.token(),row.epoch(),row.attempt(),Long.MAX_VALUE,row.expiry(),row.created(),row.cutoff(),null,null));
        assertEquals(WorkFailure.Code.WORK_COUNTER_EXHAUSTED,assertThrows(WorkFailure.class,()->service.freeze(request())).code());
        verify(evidence,never()).insert(anyLong(),any()); verify(connection).rollback();
    }
    @Test void finalFenceFailureRollsBackAfterChildrenWereWritten() throws Exception {
        doThrow(new WorkFailure(WorkFailure.Code.LEASE_REJECTED)).when(evidence).associate(any(),any(),anyLong());
        assertEquals(WorkFailure.Code.LEASE_REJECTED,assertThrows(WorkFailure.class,()->service.freeze(request())).code());
        verify(evidence,times(6)).insert(eq(17L),any()); verify(connection).rollback(); verify(connection,never()).commit();
    }
    @Test void existingFrozenHashIsVerifiedAndFinalExpiryCheckedEvenOnIdenticalRetry() throws Exception {
        service.freeze(request()); clearInvocations(evidence,connection);
        doThrow(new WorkFailure(WorkFailure.Code.LEASE_REJECTED)).when(evidence).reuse(any(),any());
        assertEquals(WorkFailure.Code.LEASE_REJECTED,assertThrows(WorkFailure.class,()->service.freeze(request())).code());
        verify(evidence,never()).insert(anyLong(),any()); verify(connection).rollback(); verify(connection,never()).commit();
    }
    @ParameterizedTest @ValueSource(strings={"epoch","attempt","rowVersion","expiry"}) void everyFenceFieldAppliesToFreeze(String field) throws Exception {
        var row=parent.get(); var lease=new WorkLease("17",row.token(),field.equals("epoch")?2:row.epoch(),field.equals("attempt")?2:row.attempt(),
            field.equals("rowVersion")?"2":"1",field.equals("expiry")?row.expiry().plusNanos(1):row.expiry());
        assertEquals(WorkFailure.Code.LEASE_REJECTED,assertThrows(WorkFailure.class,()->service.freeze(FreezeFixtures.request(lease,FreezeFixtures.input()))).code());
        verifyNoInteractions(evidence); verify(connection).rollback();
    }
}
