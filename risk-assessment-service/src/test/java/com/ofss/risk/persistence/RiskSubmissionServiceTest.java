package com.ofss.risk.persistence;

import com.ofss.risk.submission.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.ofss.risk.submission.SubmissionAdapters.*;

class RiskSubmissionServiceTest {
    RiskAcceptanceService acceptance=mock(RiskAcceptanceService.class);RiskWorkService work=mock(RiskWorkService.class);
    RiskEvidenceService freeze=mock(RiskEvidenceService.class);RiskFinalizationService finalization=mock(RiskFinalizationService.class);
    SubmissionSnapshotReader snapshots=mock(SubmissionSnapshotReader.class);AssessmentReadService reads=mock(AssessmentReadService.class);
    Facts facts=mock(Facts.class);Evidence evidence=mock(Evidence.class);PublishedConfiguration config=mock(PublishedConfiguration.class);
    DependencyCalls calls=new DependencyCalls(Duration.ofSeconds(2));PlatformTransactionManager tx=mock(PlatformTransactionManager.class);
    RiskSubmissionService service;SubmissionCommand command=SubmissionFixtures.command();AcceptanceRequest original=SubmissionFixtures.request();
    AcceptedAssessment stored=SubmissionFixtures.stored("ACCEPTED");WorkLease lease=new WorkLease("1","test-opaque",1,1,"1",AcceptanceFixtures.CREATED.plusSeconds(100));
    @BeforeEach void setup(){
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service=new RiskSubmissionService(new SubmissionSources("{\"payment-service\":[\"developer1\"]}"),facts,evidence,config,acceptance,work,freeze,finalization,snapshots,reads,calls,
            Clock.fixed(original.cutoff(),ZoneOffset.UTC),tx);
        when(acceptance.find(command.identity())).thenReturn(Optional.of(stored));when(snapshots.restore(any())).thenReturn(original);
        when(reads.get("1")).thenReturn(SubmissionFixtures.view("COMPLETED"));when(work.claim("1","0")).thenReturn(lease);
        when(snapshots.frozenHash(command.identity())).thenReturn("a".repeat(64));
    }
    @AfterEach void close(){calls.close();}
    @Test void completedRetryReadsStoredResultWithoutAnyDependenciesOrWork(){
        when(acceptance.find(command.identity())).thenReturn(Optional.of(SubmissionFixtures.stored("COMPLETED")));
        var response=service.submit("payment-service",command);assertEquals("COMPLETED",response.status());assertFalse(response.retryAllowed());
        verifyNoInteractions(facts,config,evidence,work,freeze,finalization);
    }
    @Test void frozenRetryUsesPinnedConfigurationCutoffAndInputWithoutRefetch(){
        service.submit("payment-service",command);
        verify(finalization).finalizeAssessment(new FinalizationRequest(command.identity(),lease,stored.acceptanceHash(),stored.configurationHash(),"a".repeat(64)));
        verifyNoInteractions(facts,config,evidence,freeze);
    }
    @Test void blockedAssessmentNeverClaimsOrRefetches(){
        when(acceptance.find(command.identity())).thenReturn(Optional.of(SubmissionFixtures.stored("BLOCKED")));
        when(reads.get("1")).thenReturn(SubmissionFixtures.view("BLOCKED"));assertFalse(service.submit("payment-service",command).retryAllowed());
        verifyNoInteractions(work,facts,evidence,config,finalization);
    }
    @ParameterizedTest @ValueSource(strings={"payment","validation","revision","configuration"})
    void changedReferencesConflictBeforeDependencies(String field){
        var changed=new SubmissionCommand(command.contractVersion(),command.submissionSource(),command.submissionKey(),field.equals("payment")?"2":command.paymentReference(),
            field.equals("validation")?"different":command.validationReference(),field.equals("revision")?"new":command.validationRevision(),field.equals("configuration")?"3":null);
        assertEquals(SubmissionFailure.Code.SUBMISSION_CONFLICT,assertThrows(SubmissionFailure.class,()->service.submit("payment-service",changed)).code());verifyNoInteractions(work,facts,evidence,config);
    }
    @Test void explicitMatchingPinnedVersionDoesNotResolveAgain(){
        var pinned=new SubmissionCommand(command.contractVersion(),command.submissionSource(),command.submissionKey(),command.paymentReference(),command.validationReference(),null,stored.configurationVersionId());
        service.submit("payment-service",pinned);verifyNoInteractions(config,facts,evidence);
    }
    @Test void activeWorkerReturnsPendingWithoutSecondCollection(){
        when(work.claim("1","0")).thenThrow(new WorkFailure(WorkFailure.Code.WORK_CONFLICT));when(reads.get("1")).thenReturn(SubmissionFixtures.view("IN_PROGRESS"));
        var response=service.submit("payment-service",command);assertNull(response.result());assertTrue(response.retryAllowed());verifyNoInteractions(evidence,finalization,freeze);
    }
    @Test void missingHistoryIsRecoverableNotAZeroScore(){
        when(snapshots.frozenHash(command.identity())).thenReturn(null);when(evidence.retrieve(any(),any())).thenThrow(new SubmissionFailure(SubmissionFailure.Code.DEPENDENCY_UNAVAILABLE));
        when(reads.get("1")).thenReturn(SubmissionFixtures.view("RETRYABLE_FAILURE"));var response=service.submit("payment-service",command);
        assertNull(response.result());assertEquals("RETRYABLE_FAILURE",response.status());verify(work).fail(lease,WorkDiagnostic.TRANSIENT_FAILURE);verifyNoInteractions(freeze,finalization);
    }
    @Test void irreproducibleCutoffRequiresInterventionNotFreshHistory(){
        when(snapshots.frozenHash(command.identity())).thenReturn(null);when(evidence.retrieve(any(),any())).thenThrow(new SubmissionFailure(SubmissionFailure.Code.CUTOFF_NOT_REPRODUCIBLE));
        when(reads.get("1")).thenReturn(SubmissionFixtures.view("BLOCKED"));assertFalse(service.submit("payment-service",command).retryAllowed());verify(work).fail(lease,WorkDiagnostic.INTERVENTION_REQUIRED);verifyNoInteractions(finalization);
    }
    @Test void supersededLeaseCannotBeMarkedFailedByOldWorker(){
        when(finalization.finalizeAssessment(any())).thenThrow(new WorkFailure(WorkFailure.Code.LEASE_REJECTED));
        when(reads.get("1")).thenReturn(SubmissionFixtures.view("IN_PROGRESS"));service.submit("payment-service",command);verify(work,never()).fail(any(),any());
    }
    @Test void policyFailureBlocksRatherThanCompletingZero(){
        when(finalization.finalizeAssessment(any())).thenThrow(new FinalizationFailure(FinalizationFailure.Code.POLICY_UNRESOLVED));
        when(reads.get("1")).thenReturn(SubmissionFixtures.view("BLOCKED"));assertNull(service.submit("payment-service",command).result());verify(work).fail(lease,WorkDiagnostic.INTERVENTION_REQUIRED);
    }
    @Test void sourceOwnerIsCheckedBeforeAnyPersistenceOrAdapters(){
        assertEquals(SubmissionFailure.Code.SOURCE_FORBIDDEN,assertThrows(SubmissionFailure.class,()->service.submit("approval-service",command)).code());
        verifyNoInteractions(acceptance,reads,work,facts,config,evidence);
    }
    @Test void statusLookupHasNoWorkOrDependenciesAndNoLeaseData(){
        assertEquals("COMPLETED",service.status("payment-service",command.submissionSource(),command.submissionKey()).status());verifyNoInteractions(facts,config,evidence,work,freeze,finalization);
    }
    @Test void unknownStatusIs404Contract(){when(acceptance.find(any())).thenReturn(Optional.empty());assertEquals(SubmissionFailure.Code.SUBMISSION_NOT_FOUND,
        assertThrows(SubmissionFailure.class,()->service.status("payment-service",command.submissionSource(),command.submissionKey())).code());}
    @Test void acceptanceCreatesOnlyAfterAuthoritativeFactsAndVerifiedConfig(){
        when(acceptance.find(command.identity())).thenReturn(Optional.empty(),Optional.of(stored));
        when(facts.verify(any(),any(),any())).thenReturn(new VerifiedFacts(original.identity(),original.paymentId(),original.requesterId(),original.validationReference(),original.validationRevision(),original.current(),original.currentAndRetrySubmissionIds()));
        when(config.resolve(isNull(),any())).thenReturn(SubmissionFixtures.published());when(acceptance.accept(any())).thenReturn(stored);
        service.submit("payment-service",command);var capture=org.mockito.ArgumentCaptor.forClass(AcceptanceRequest.class);verify(acceptance).accept(capture.capture());
        assertEquals(original.current(),capture.getValue().current());assertEquals(original.cutoff(),capture.getValue().cutoff());assertEquals(original.configuration().versionId(),capture.getValue().configuration().versionId());
    }
    @Test void unavailableAdapterDoesNotCreateParent(){
        when(acceptance.find(any())).thenReturn(Optional.empty());when(facts.verify(any(),any(),any())).thenThrow(SubmissionAdapters.unavailable());
        assertEquals(SubmissionFailure.Code.ADAPTER_UNAVAILABLE,assertThrows(SubmissionFailure.class,()->service.submit("payment-service",command)).code());verify(acceptance,never()).accept(any());verifyNoInteractions(config,work);
    }
    @Test void racingFirstAcceptanceReusesWinnerCutoffWithoutSelectingAnotherActiveVersion(){
        when(acceptance.find(command.identity())).thenReturn(Optional.empty(),Optional.of(stored));
        when(facts.verify(any(),any(),any())).thenReturn(new VerifiedFacts(original.identity(),original.paymentId(),original.requesterId(),original.validationReference(),original.validationRevision(),original.current(),original.currentAndRetrySubmissionIds()));
        when(config.resolve(isNull(),any())).thenReturn(SubmissionFixtures.published());when(acceptance.accept(any())).thenThrow(AcceptanceFailure.conflict()).thenReturn(stored);
        service.submit("payment-service",command);var capture=org.mockito.ArgumentCaptor.forClass(AcceptanceRequest.class);verify(acceptance,times(2)).accept(capture.capture());
        var retry=capture.getAllValues().get(1);assertNull(retry.configuration());assertEquals(stored.cutoff(),retry.cutoff());assertEquals(original.current(),retry.current());verify(config,times(1)).resolve(isNull(),any());
    }
    @Test void incompleteEvidenceRollsBackFreezeAndRecordsRecoverableFailure(){
        var bundle=DemoSubmissionAdapters.evidence(new AcceptanceRequest(new SubmissionIdentity("demo.test","x"),original.paymentId(),original.requesterId(),original.validationReference(),null,
            original.current(),original.cutoff(),original.currentAndRetrySubmissionIds(),PinnedRiskEvaluators.CURRENT,original.configuration(),null),calls.deadline());
        when(snapshots.frozenHash(any())).thenReturn(null);when(evidence.retrieve(any(),any())).thenReturn(bundle);when(freeze.freeze(any())).thenThrow(new FreezeFailure(FreezeFailure.Code.EVIDENCE_NOT_READY));
        when(reads.get("1")).thenReturn(SubmissionFixtures.view("RETRYABLE_FAILURE"));assertNull(service.submit("payment-service",command).result());verify(work).fail(lease,WorkDiagnostic.TRANSIENT_FAILURE);verifyNoInteractions(finalization);
    }
    @Test void durableLocalCapacityFailureRequiresInterventionUsingUpdatedCaptureFence(){
        var upstream=mock(UpstreamEvidence.class);var captures=mock(PriorHistoryCaptureService.class);var base=FinalizationFixtures.input(false);
        var sources=new EnumMap<EvidenceFreezeRequest.Kind,EvidenceFreezeRequest.Source>(EvidenceFreezeRequest.Kind.class);
        for(var kind:List.of(EvidenceFreezeRequest.Kind.PAYMENT_HISTORY,EvidenceFreezeRequest.Kind.SUPPLIER,EvidenceFreezeRequest.Kind.SUPPLIER_TRANSITIONS))sources.put(kind,new EvidenceFreezeRequest.Source("fixture-upstream",kind.name(),null));
        when(upstream.retrieve(any(),any())).thenReturn(new UpstreamBundle(base.payments(),base.supplier(),base.supplierTransitions(),sources,Set.of()));
        var incomplete=new PriorAssessmentHistory(new com.ofss.risk.engine.RiskInput.Evidence<>(com.ofss.risk.engine.RiskInput.Availability.INCOMPLETE,null,"456","INR",original.cutoff(),original.cutoff().minusSeconds(7776000),List.of()),new EvidenceFreezeRequest.Source("risk-assessment-service","supplier-completed-history/456",null));
        when(captures.loadOrCollect(any(),any())).thenReturn(incomplete);var refreshed=new WorkLease("1",lease.token(),1,1,"2",lease.expiresAt());when(captures.persist(eq(lease),any(),same(incomplete))).thenReturn(new PriorHistoryCaptureService.Captured(refreshed,incomplete));
        when(snapshots.frozenHash(any())).thenReturn(null);when(reads.get("1")).thenReturn(SubmissionFixtures.view("BLOCKED"));
        service=new RiskSubmissionService(new SubmissionSources("{\"payment-service\":[\"developer1\"]}"),facts,new ProductionEvidenceAdapter(upstream,captures),config,acceptance,work,freeze,finalization,snapshots,reads,calls,Clock.fixed(original.cutoff(),ZoneOffset.UTC),tx);
        var response=service.submit("payment-service",command);assertEquals("BLOCKED",response.status());assertFalse(response.retryAllowed());assertNull(response.result());verify(work).fail(refreshed,WorkDiagnostic.INTERVENTION_REQUIRED);verifyNoInteractions(freeze,finalization);
    }
    @Test void freezeReturnsRefreshedFenceForFinalization(){
        var bundle=DemoSubmissionAdapters.evidence(new AcceptanceRequest(new SubmissionIdentity("demo.test","x"),original.paymentId(),original.requesterId(),original.validationReference(),null,
            original.current(),original.cutoff(),original.currentAndRetrySubmissionIds(),PinnedRiskEvaluators.CURRENT,original.configuration(),null),calls.deadline());
        when(snapshots.frozenHash(any())).thenReturn(null);when(evidence.retrieve(any(),any())).thenReturn(bundle);
        var refreshed=new WorkLease("1",lease.token(),1,1,"2",lease.expiresAt());when(freeze.freeze(any())).thenReturn(new FrozenInput("3","b".repeat(64),"c".repeat(64),original.cutoff(),refreshed,"not-public",bundle.input()));
        service.submit("payment-service",command);verify(finalization).finalizeAssessment(new FinalizationRequest(command.identity(),refreshed,stored.acceptanceHash(),stored.configurationHash(),"b".repeat(64)));
    }
    @Test void competingRequestsOnlyWinnerCollectsAndFinalizes()throws Exception{
        var claimed=new java.util.concurrent.atomic.AtomicBoolean();when(work.claim("1","0")).thenAnswer(i->{if(!claimed.compareAndSet(false,true))throw new WorkFailure(WorkFailure.Code.WORK_CONFLICT);return lease;});
        var pool=Executors.newFixedThreadPool(2);try{var a=pool.submit(()->service.submit("payment-service",command));var b=pool.submit(()->service.submit("payment-service",command));a.get(5,TimeUnit.SECONDS);b.get(5,TimeUnit.SECONDS);}
        finally{pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
        verify(finalization,times(1)).finalizeAssessment(any());
    }
}
