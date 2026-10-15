package com.ofss.risk.persistence;

import com.ofss.risk.engine.*;
import com.ofss.risk.submission.*;
import com.ofss.risk.submission.SubmissionAdapters.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProductionEvidenceAdapterTest {
    UpstreamEvidence upstream=mock(UpstreamEvidence.class);PriorHistoryCaptureService local=mock(PriorHistoryCaptureService.class);
    AcceptanceRequest accepted=PriorHistoryFixtures.request();Deadline deadline=new Deadline(System.nanoTime()+Duration.ofSeconds(10).toNanos());
    UpstreamBundle supplied;PriorAssessmentHistory history;
    @BeforeEach void setup(){var input=FinalizationFixtures.input(false);var sources=new EnumMap<EvidenceFreezeRequest.Kind,EvidenceFreezeRequest.Source>(EvidenceFreezeRequest.Kind.class);
        for(var k:List.of(EvidenceFreezeRequest.Kind.PAYMENT_HISTORY,EvidenceFreezeRequest.Kind.SUPPLIER,EvidenceFreezeRequest.Kind.SUPPLIER_TRANSITIONS))sources.put(k,new EvidenceFreezeRequest.Source("developer1-integration",k.name(),"immutable-test-revision"));
        supplied=new UpstreamBundle(input.payments(),input.supplier(),input.supplierTransitions(),sources,Set.of(new SubmissionIdentity("source","retry")),"a".repeat(64));
        history=new PriorAssessmentHistory(new RiskInput.Evidence<>(RiskInput.Availability.COMPLETE,"local-snapshot","456","INR",accepted.cutoff(),accepted.cutoff().minusSeconds(7776000),List.of()),new EvidenceFreezeRequest.Source("risk-assessment-service","supplier-completed-history/456","a".repeat(64)));
        when(upstream.retrieve(eq(accepted),any())).thenReturn(supplied);when(local.loadRequired(any())).thenReturn(history);
    }
    @Test void upstreamFetchFinishesBeforeLocalReadAndAssemblesAllFourDependencySources(){
        var bundle=new ProductionEvidenceAdapter(upstream,local).retrieve(accepted,deadline);assertSame(history.evidence(),bundle.input().previousAssessments());assertEquals(4,bundle.sources().size());assertEquals(history.source(),bundle.sources().get(EvidenceFreezeRequest.Kind.PRIOR_ASSESSMENTS));
        assertEquals(accepted.current(),bundle.input().current());assertEquals(accepted.cutoff(),bundle.input().cutoff());assertEquals(accepted.currentAndRetrySubmissionIds(),bundle.input().currentAndRetrySubmissionIds());
        var ordered=inOrder(upstream,local);ordered.verify(upstream).retrieve(accepted,deadline);ordered.verify(local).loadRequired(new PriorAssessmentHistory.Request(accepted,supplied.retryIdentities(),null));
    }
    @Test void absentUpstreamDoesNotFetchLocalHistoryOrInventEmptyInputs(){when(upstream.retrieve(any(),any())).thenThrow(SubmissionAdapters.unavailable());assertEquals(SubmissionFailure.Code.ADAPTER_UNAVAILABLE,assertThrows(SubmissionFailure.class,()->new ProductionEvidenceAdapter(upstream,local).retrieve(accepted,deadline)).code());verifyNoInteractions(local);}
    @Test void partialUpstreamAndLocalEvidenceRetainTheirAvailability(){var incomplete=new RiskInput.Evidence<RiskInput.Payment>(RiskInput.Availability.INCOMPLETE,null,"456","INR",accepted.cutoff(),accepted.cutoff().minusSeconds(100),List.of());
        when(upstream.retrieve(any(),any())).thenReturn(new UpstreamBundle(incomplete,supplied.supplier(),supplied.transitions(),supplied.sources(),Set.of(),supplied.expectedPriorPopulationHash()));
        var result=new ProductionEvidenceAdapter(upstream,local).retrieve(accepted,deadline);assertEquals(RiskInput.Availability.INCOMPLETE,result.input().payments().availability());
        when(local.loadRequired(any())).thenReturn(new PriorAssessmentHistory(RiskInput.Evidence.unavailable("456","INR",accepted.cutoff()),history.source()));assertEquals(RiskInput.Availability.UNAVAILABLE,new ProductionEvidenceAdapter(upstream,local).retrieve(accepted,deadline).input().previousAssessments().availability());
    }
    @Test void mismatchedUpstreamScopeRejectedBeforeLocalQuery(){var wrong=RiskInput.Evidence.<RiskInput.Payment>unavailable("999","INR",accepted.cutoff());when(upstream.retrieve(any(),any())).thenReturn(new UpstreamBundle(wrong,supplied.supplier(),supplied.transitions(),supplied.sources(),Set.of(),null));assertThrows(SubmissionFailure.class,()->new ProductionEvidenceAdapter(upstream,local).retrieve(accepted,deadline));verifyNoInteractions(local);}
    @Test void noDependencyCallsWhileCallerHoldsATransaction(){TransactionSynchronizationManager.setActualTransactionActive(true);try{assertThrows(SubmissionFailure.class,()->new ProductionEvidenceAdapter(upstream,local).retrieve(accepted,deadline));verifyNoInteractions(upstream,local);}finally{TransactionSynchronizationManager.setActualTransactionActive(false);}}
    @Test void irreproducibleLocalCaptureIsPropagatedForBlockedRecovery(){when(local.loadRequired(any())).thenThrow(new SubmissionFailure(SubmissionFailure.Code.CUTOFF_NOT_REPRODUCIBLE));assertEquals(SubmissionFailure.Code.CUTOFF_NOT_REPRODUCIBLE,assertThrows(SubmissionFailure.class,()->new ProductionEvidenceAdapter(upstream,local).retrieve(accepted,deadline)).code());}
    @Test void upstreamCannotOverrideLocalHistorySource(){var sources=new EnumMap<EvidenceFreezeRequest.Kind,EvidenceFreezeRequest.Source>(supplied.sources());sources.put(EvidenceFreezeRequest.Kind.PRIOR_ASSESSMENTS,history.source());assertThrows(IllegalArgumentException.class,()->new UpstreamBundle(supplied.payments(),supplied.supplier(),supplied.transitions(),sources,Set.of(),null));}
    @Test void unexpectedAdapterErrorsAreSanitized(){when(upstream.retrieve(any(),any())).thenThrow(new IllegalStateException("sensitive payload"));var e=assertThrows(SubmissionFailure.class,()->new ProductionEvidenceAdapter(upstream,local).retrieve(accepted,deadline));assertEquals(SubmissionFailure.Code.DEPENDENCY_UNAVAILABLE,e.code());assertNull(e.getCause());}
    @Test void productionDoesNotRequireAnUpstreamReceipt(){when(upstream.retrieve(any(),any())).thenReturn(new UpstreamBundle(supplied.payments(),supplied.supplier(),supplied.transitions(),supplied.sources(),Set.of(),null));
        assertEquals(history.evidence(),new ProductionEvidenceAdapter(upstream,local).retrieve(accepted,deadline).input().previousAssessments());verify(local).loadRequired(new PriorAssessmentHistory.Request(accepted,Set.of(),null));}
    @Test void collectionReadsBeforePersistAndReturnsUpdatedLeaseOnlyAfterCommit(){
        var lease=new WorkLease("17","fixture-lease",1,1,"1",accepted.cutoff().plusSeconds(30));var updated=new WorkLease("17",lease.token(),1,1,"2",lease.expiresAt());
        var owner=Thread.currentThread();when(local.loadOrCollect(any(),any())).thenReturn(history);when(local.persist(eq(lease),any(),same(history))).thenAnswer(i->{assertSame(owner,Thread.currentThread());return new PriorHistoryCaptureService.Captured(updated,history);});
        try(var calls=new DependencyCalls(Duration.ofSeconds(5))){var result=new ProductionEvidenceAdapter(upstream,local).retrieveWithCapture(accepted,lease,calls,calls.deadline());assertEquals(updated,result.lease());assertSame(history.evidence(),result.bundle().input().previousAssessments());
            var order=inOrder(upstream,local);order.verify(upstream).retrieve(any(),any());order.verify(local).loadOrCollect(any(),any());order.verify(local).persist(eq(lease),any(),same(history));}
    }
}
