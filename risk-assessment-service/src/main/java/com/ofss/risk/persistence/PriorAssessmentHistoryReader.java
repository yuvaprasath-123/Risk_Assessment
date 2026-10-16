package com.ofss.risk.persistence;

import com.ofss.risk.contract.*;
import com.ofss.risk.engine.RiskInput.*;
import com.ofss.risk.submission.*;
import com.ofss.risk.submission.SubmissionAdapters.Deadline;
import java.time.*;
import java.util.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** Complete committed population of one capture transaction, NOT a claim to recreate acceptance-time commit visibility. */
public class PriorAssessmentHistoryReader {
    private final PriorAssessmentHistoryRepository repository;private final PlatformTransactionManager manager;private final int capacity;
    public PriorAssessmentHistoryReader(PriorAssessmentHistoryRepository repository,PlatformTransactionManager manager,int capacity){
        if(capacity<1||capacity>1000)throw new IllegalArgumentException("Prior history capacity must be 1..1000");this.repository=repository;this.manager=manager;this.capacity=capacity;
    }
    public PriorAssessmentHistory read(PriorAssessmentHistory.Request request,Deadline deadline){
        try{
            check(deadline);if(TransactionSynchronizationManager.isActualTransactionActive())throw unavailable();
            var a=request.accepted();var rule=a.configuration().rules().stream().filter(r->r.code()==RuleCode.PREVIOUS_RISK_FLAGS).findFirst().orElseThrow();
            String supplier=Long.toString(PersistenceValues.id(a.current().supplierId()));
            if(!rule.enabled())return new PriorAssessmentHistory(Evidence.unavailable(supplier,a.current().currencyCode(),a.cutoff()),source(supplier,null));
            var policy=(RuleParameters.PreviousFlags)rule.parameters();var from=a.cutoff().minusSeconds(policy.windowSeconds());PersistenceValues.timestamp(from);
            var tx=new TransactionTemplate(manager);tx.setReadOnly(true);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);tx.setTimeout((int)Math.max(1,Math.min(5,(deadline.remainingNanos()+999999999)/1000000000)));
            return tx.execute(status->{repository.beginSnapshot();check(deadline);
                repository.captureAttempt(a);
                var rows=repository.candidates(supplier,from,a.cutoff(),capacity);check(deadline);
                if(rows.size()>capacity)return new PriorAssessmentHistory(new Evidence<>(Availability.INCOMPLETE,null,supplier,a.current().currencyCode(),a.cutoff(),from,List.of()),source(supplier,null));
                var ordered=rows.stream().sorted(Comparator.comparing(PriorAssessmentHistoryRepository.Candidate::completed)
                    .thenComparing(r->r.identity().source()).thenComparing(r->r.identity().key()).thenComparingLong(PriorAssessmentHistoryRepository.Candidate::id)).toList();
                var result=new ArrayList<PriorAssessment>();var proofs=new ArrayList<Object>();var ids=new HashSet<Long>();var identities=new HashSet<SubmissionIdentity>();
                var excluded=new HashSet<>(request.retryIdentities());excluded.add(a.identity());
                for(var row:ordered){check(deadline);
                    if(!ids.add(row.id())||!identities.add(row.identity())||!supplier.equals(row.supplier())||!a.current().currencyCode().equals(row.currency())
                        ||row.completed().isBefore(from)||!row.completed().isBefore(a.cutoff()))throw unavailable();
                    // Verify even excluded/nonqualifying rows: corruption must not disappear behind a filter.
                    var verified=repository.verify(row);
                    if(excluded.contains(row.identity())||(row.identity().source().equals(a.identity().source())&&a.currentAndRetrySubmissionIds().contains(verified.originalSubmissionId())))continue;
                    result.add(verified.prior());proofs.add(CanonicalEvidence.map("identity",row.identity(),"originalSubmissionId",verified.originalSubmissionId(),"record",verified.prior(),"resultHash",verified.resultHash()));
                }
                var population=CanonicalEvidence.document(CanonicalEvidence.map("format","risk-prior-population-v1","supplierId",supplier,"currency",a.current().currencyCode(),
                    "from",from,"cutoff",a.cutoff(),"currentIdentity",a.identity(),"excludedIdentities",excluded.stream().sorted(Comparator.comparing(SubmissionIdentity::source).thenComparing(SubmissionIdentity::key)).toList(),
                    "excludedAliases",a.currentAndRetrySubmissionIds(),"records",proofs));String hash=population.hash();
                if(request.expectedPopulationHash()!=null&&!request.expectedPopulationHash().equals(hash))throw new SubmissionFailure(SubmissionFailure.Code.CUTOFF_NOT_REPRODUCIBLE);
                check(deadline);return new PriorAssessmentHistory(new Evidence<>(Availability.COMPLETE,"risk-prior-v1:"+hash,supplier,a.current().currencyCode(),a.cutoff(),from,result),source(supplier,hash),population.json());
            });
        }catch(SubmissionFailure e){throw e;}catch(RuntimeException e){throw unavailable();}
    }
    private static EvidenceFreezeRequest.Source source(String supplier,String revision){return new EvidenceFreezeRequest.Source("risk-assessment-service","supplier-completed-history/"+supplier,revision);}
    private static void check(Deadline d){if(d==null||d.remainingNanos()==0||Thread.currentThread().isInterrupted())throw unavailable();}
    private static SubmissionFailure unavailable(){return PriorAssessmentHistoryRepository.invalid();}
}
