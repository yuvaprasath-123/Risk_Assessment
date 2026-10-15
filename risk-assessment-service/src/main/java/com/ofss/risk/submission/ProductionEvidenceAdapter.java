package com.ofss.risk.submission;

import com.ofss.risk.engine.*;
import com.ofss.risk.contract.RuleCode;
import com.ofss.risk.persistence.*;
import com.ofss.risk.submission.SubmissionAdapters.*;
import java.util.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** No remote endpoint assumptions or demo fallback. All upstream calls finish before the local read-only transaction starts. */
public final class ProductionEvidenceAdapter implements Evidence {
    public record Collected(Bundle bundle,WorkLease lease) {}
    private final UpstreamEvidence upstream;private final PriorHistoryCaptureService history;
    public ProductionEvidenceAdapter(UpstreamEvidence upstream,PriorHistoryCaptureService history){this.upstream=Objects.requireNonNull(upstream);this.history=Objects.requireNonNull(history);}
    @Override public Bundle retrieve(AcceptanceRequest accepted,Deadline deadline){
        try{
            if(TransactionSynchronizationManager.isActualTransactionActive()||deadline==null||deadline.remainingNanos()==0)throw failure();
            var supplied=fetch(accepted,deadline);
            var prior=history.loadRequired(request(accepted,supplied));
            return compose(accepted,supplied,prior);
        }catch(SubmissionFailure e){throw e;}catch(RuntimeException e){throw failure();}
    }
    /** Read dependencies on bounded workers, commit the D2 population on this orchestration thread, then expose it to freezing. */
    public Collected retrieveWithCapture(AcceptanceRequest accepted,WorkLease lease,DependencyCalls calls,Deadline deadline){
        var supplied=calls.call(deadline,()->fetch(accepted,deadline));var request=request(accepted,supplied);
        var candidate=calls.call(deadline,()->history.loadOrCollect(request,deadline));
        if(calls.exhausted(deadline))throw failure();
        var committed=history.persist(lease,request,candidate);
        return new Collected(compose(accepted,supplied,committed.history()),committed.lease());
    }
    private UpstreamBundle fetch(AcceptanceRequest accepted,Deadline deadline){
        try{
            if(TransactionSynchronizationManager.isActualTransactionActive()||deadline==null||deadline.remainingNanos()==0)throw failure();
            var supplied=upstream.retrieve(accepted,deadline);if(supplied==null)throw failure();
            // Unavailable/partial upstream envelopes stay unavailable/partial; no authoritative empty substitutes.
            for(var envelope:List.of(supplied.payments(),supplied.supplier(),supplied.transitions()))
                if(!envelope.supplierId().equals(accepted.current().supplierId())||!envelope.currencyCode().equals(accepted.current().currencyCode())||!envelope.cutoff().equals(accepted.cutoff()))throw failure();
            return supplied;
        }catch(SubmissionFailure e){throw e;}catch(RuntimeException e){throw failure();}
    }
    private static PriorAssessmentHistory.Request request(AcceptanceRequest accepted,UpstreamBundle supplied){return new PriorAssessmentHistory.Request(accepted,supplied.retryIdentities(),null);}
    private Bundle compose(AcceptanceRequest accepted,UpstreamBundle supplied,PriorAssessmentHistory prior){
        try{
            var input=new RiskInput(accepted.current(),accepted.cutoff(),accepted.currentAndRetrySubmissionIds(),supplied.payments(),supplied.supplier(),supplied.transitions(),prior.evidence());
            var sources=new EnumMap<EvidenceFreezeRequest.Kind,EvidenceFreezeRequest.Source>(EvidenceFreezeRequest.Kind.class);sources.putAll(supplied.sources());sources.put(EvidenceFreezeRequest.Kind.PRIOR_ASSESSMENTS,prior.source());
            return new Bundle(input,sources);
        }catch(SubmissionFailure e){throw e;}catch(RuntimeException e){throw failure();}
    }
    private static SubmissionFailure failure(){return new SubmissionFailure(SubmissionFailure.Code.DEPENDENCY_UNAVAILABLE);}
}
