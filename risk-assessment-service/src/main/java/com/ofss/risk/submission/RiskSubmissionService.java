package com.ofss.risk.submission;

import com.ofss.risk.persistence.*;
import java.time.Clock;
import java.util.Objects;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import static com.ofss.risk.submission.SubmissionAdapters.*;

/** One synchronous attempt. No scheduler/background writes; every persistence phase owns its existing transaction. */
public class RiskSubmissionService {
    public record Status(String assessmentId,String rowVersion,String status,String cutoff,String configurationVersionId,
            String configurationRowVersion,String completedAt,AssessmentReadView.Score result,boolean retryAllowed) {}
    private final SubmissionSources sources;private final Facts facts;private final Evidence evidence;private final PublishedConfiguration configs;
    private final RiskAcceptanceService acceptance;private final RiskWorkService work;private final RiskEvidenceService freeze;
    private final RiskFinalizationService finalization;private final SubmissionSnapshotReader snapshots;private final AssessmentReadService reads;
    private final DependencyCalls calls;private final Clock clock;private final TransactionTemplate outside;
    public RiskSubmissionService(SubmissionSources sources,Facts facts,Evidence evidence,PublishedConfiguration configs,
            RiskAcceptanceService acceptance,RiskWorkService work,RiskEvidenceService freeze,RiskFinalizationService finalization,
            SubmissionSnapshotReader snapshots,AssessmentReadService reads,DependencyCalls calls,Clock clock,PlatformTransactionManager manager) {
        this.sources=sources;this.facts=facts;this.evidence=evidence;this.configs=configs;this.acceptance=acceptance;this.work=work;
        this.freeze=freeze;this.finalization=finalization;this.snapshots=snapshots;this.reads=reads;this.calls=calls;this.clock=clock;
        outside=new TransactionTemplate(manager);outside.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    }
    public Status submit(String service,SubmissionCommand command) {
        sources.require(service,command.submissionSource());
        try{return outside.execute(tx->perform(command));}
        catch(SubmissionFailure e){throw e;}
        catch(AcceptanceFailure e){throw failure(e.code()==AcceptanceFailure.Code.SUBMISSION_CONFLICT?SubmissionFailure.Code.SUBMISSION_CONFLICT:SubmissionFailure.Code.SUBMISSION_UNAVAILABLE);}
        catch(RuntimeException e){throw failure(SubmissionFailure.Code.SUBMISSION_UNAVAILABLE);}
    }
    public Status status(String service,String source,String key) {
        sources.require(service,source);SubmissionCommand.text(key,128);
        try{return current(new SubmissionIdentity(source,key));}
        catch(SubmissionFailure e){throw e;}catch(RuntimeException e){throw failure(SubmissionFailure.Code.SUBMISSION_UNAVAILABLE);}
    }
    private Status perform(SubmissionCommand command) {
        var deadline=calls.deadline();var existing=acceptance.find(command.identity());AcceptedAssessment stored;
        if(existing.isPresent()) {stored=existing.get();compare(command,snapshots.restore(stored));}
        else {
            var cutoff=clock.instant();
            var verified=calls.call(deadline,()->facts.verify(command,cutoff,deadline));
            if(verified==null)throw failure(SubmissionFailure.Code.DEPENDENCY_UNAVAILABLE);
            var selected=calls.call(deadline,()->configs.resolve(command.configurationVersionId(),deadline));
            var config=new PublishedConfigurationMapping().map(selected);
            if(command.configurationVersionId()!=null && !command.configurationVersionId().equals(config.versionId()))throw failure(SubmissionFailure.Code.DEPENDENCY_UNAVAILABLE);
            AcceptanceRequest request;
            try {request=verified.acceptance(cutoff,config);compare(command,request);}
            catch(RuntimeException e){throw failure(SubmissionFailure.Code.DEPENDENCY_UNAVAILABLE);}
            try {stored=acceptance.accept(request);}
            catch(AcceptanceFailure collision) {
                if(collision.code()!=AcceptanceFailure.Code.SUBMISSION_CONFLICT)throw collision;
                var winner=acceptance.find(command.identity()).orElseThrow(()->failure(SubmissionFailure.Code.SUBMISSION_UNAVAILABLE));
                compare(command,snapshots.restore(winner));
                // A racing omitted choice follows the first winner. Recheck ALL adapter facts with winner cutoff/config locally.
                var candidate=verified.acceptance(winner.cutoff(),command.configurationVersionId()==null?null:config);
                stored=acceptance.accept(candidate);
            }
        }
        if(stored.workState().equals("COMPLETED") || stored.workState().equals("BLOCKED"))return current(command.identity());
        WorkLease lease;
        try {lease=work.claim(stored.assessmentId(),stored.rowVersion());}
        catch(WorkFailure e){if(e.code()==WorkFailure.Code.WORK_CONFLICT || e.code()==WorkFailure.Code.LEASE_REJECTED)return current(command.identity());throw e;}
        try {
            var pinned=snapshots.restore(stored);String inputHash=snapshots.frozenHash(command.identity());
            if(inputHash==null) {
                var bundle=calls.call(deadline,()->evidence.retrieve(pinned,deadline));
                if(bundle==null || calls.exhausted(deadline))throw failure(SubmissionFailure.Code.DEPENDENCY_UNAVAILABLE);
                int records=bundle.input().payments().records().size()+bundle.input().supplier().records().size()
                    +bundle.input().supplierTransitions().records().size()+bundle.input().previousAssessments().records().size();
                if(records>10000)throw failure(SubmissionFailure.Code.DEPENDENCY_UNAVAILABLE);
                var frozen=freeze.freeze(new EvidenceFreezeRequest(lease,pinned,bundle.input(),bundle.sources()));
                lease=frozen.lease();inputHash=frozen.inputHash();
            }
            if(calls.exhausted(deadline))throw failure(SubmissionFailure.Code.DEPENDENCY_UNAVAILABLE);
            finalization.finalizeAssessment(new FinalizationRequest(command.identity(),lease,stored.acceptanceHash(),stored.configurationHash(),inputHash));
        }catch(WorkFailure e) {
            if(e.code()!=WorkFailure.Code.LEASE_REJECTED && e.code()!=WorkFailure.Code.WORK_CONFLICT)failSafely(lease,WorkDiagnostic.TRANSIENT_FAILURE);
        }catch(SubmissionFailure e) {
            failSafely(lease,e.code()==SubmissionFailure.Code.CUTOFF_NOT_REPRODUCIBLE?WorkDiagnostic.INTERVENTION_REQUIRED:WorkDiagnostic.TRANSIENT_FAILURE);
        }catch(FinalizationFailure e) {
            failSafely(lease,e.code()==FinalizationFailure.Code.POLICY_UNRESOLVED || e.code()==FinalizationFailure.Code.EVALUATOR_UNAVAILABLE?
                WorkDiagnostic.INTERVENTION_REQUIRED:WorkDiagnostic.TRANSIENT_FAILURE);
        }catch(RuntimeException e){failSafely(lease,WorkDiagnostic.TRANSIENT_FAILURE);}
        return current(command.identity());
    }
    private void failSafely(WorkLease lease,WorkDiagnostic diagnostic) {
        try{work.fail(lease,diagnostic);}catch(WorkFailure e){
            if(e.code()!=WorkFailure.Code.LEASE_REJECTED && e.code()!=WorkFailure.Code.WORK_CONFLICT)throw failure(SubmissionFailure.Code.SUBMISSION_UNAVAILABLE);
        }
    }
    private Status current(SubmissionIdentity identity) {
        var stored=acceptance.find(identity).orElseThrow(()->failure(SubmissionFailure.Code.SUBMISSION_NOT_FOUND));
        var v=reads.get(stored.assessmentId());
        return new Status(v.assessmentId(),v.rowVersion(),v.status(),v.cutoff(),v.configurationVersionId(),v.configurationRowVersion(),v.completedAt(),v.result(),
            !v.status().equals("COMPLETED") && !v.status().equals("BLOCKED"));
    }
    private static void compare(SubmissionCommand command,AcceptanceRequest accepted) {
        if(!command.identity().equals(accepted.identity()) || SubmissionCommand.id(command.paymentReference())!=accepted.paymentId()
            || !command.validationReference().equals(accepted.validationReference()) || !Objects.equals(command.validationRevision(),accepted.validationRevision())
            || command.configurationVersionId()!=null && !command.configurationVersionId().equals(accepted.configuration().versionId()))throw failure(SubmissionFailure.Code.SUBMISSION_CONFLICT);
    }
    private static SubmissionFailure failure(SubmissionFailure.Code code){return new SubmissionFailure(code);}
}
