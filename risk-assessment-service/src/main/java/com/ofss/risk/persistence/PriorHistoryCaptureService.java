package com.ofss.risk.persistence;

import com.ofss.risk.contract.RuleCode;
import com.ofss.risk.submission.*;
import com.ofss.risk.submission.SubmissionAdapters.Deadline;
import java.time.*;
import java.util.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** D2 owns capture and replay. Collection is read-only; the short append transaction is fully lease fenced. */
public class PriorHistoryCaptureService {
    public record Captured(WorkLease lease,PriorAssessmentHistory history) {}
    private final PriorAssessmentHistoryReader reader;private final RiskAcceptanceRepository acceptance;private final RiskWorkRepository work;
    private final RiskEvidenceRepository evidence;private final Clock clock;private final TransactionTemplate reads,writes;
    public PriorHistoryCaptureService(PriorAssessmentHistoryReader reader,RiskAcceptanceRepository acceptance,RiskWorkRepository work,
            RiskEvidenceRepository evidence,PlatformTransactionManager manager,Clock clock){
        this.reader=reader;this.acceptance=acceptance;this.work=work;this.evidence=evidence;this.clock=clock;
        reads=new TransactionTemplate(manager);reads.setReadOnly(true);reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);reads.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);reads.setTimeout(5);
        writes=new TransactionTemplate(manager);writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);writes.setTimeout(5);
    }
    /** Called only on dependency-read threads; no mutation. A retained population prevents any history query on retry. */
    public PriorAssessmentHistory loadOrCollect(PriorAssessmentHistory.Request request,Deadline deadline){
        outside();check(deadline);if(disabled(request))return unavailable(request);var saved=load(request);check(deadline);return saved!=null?saved:reader.read(request,deadline);
    }
    public PriorAssessmentHistory loadRequired(PriorAssessmentHistory.Request request){
        outside();if(disabled(request))return unavailable(request);var history=load(request);if(history==null)throw failure();return history;
    }
    private PriorAssessmentHistory load(PriorAssessmentHistory.Request request){
        try{return reads.execute(status->{
            // Reuse the same explicit Oracle snapshot contract as the established stored-result reader.
            work.beginSnapshot();var stored=acceptance.find(request.accepted().identity()).orElseThrow(PriorHistoryCaptureService::failure);matches(request.accepted(),stored);
            long id=PersistenceValues.id(stored.assessmentId());var rows=evidence.children(id);var header=evidence.header(id);
            var row=header.hash()==null?PriorHistoryCaptureCodec.partial(rows,stored).orElse(null):PriorHistoryCaptureCodec.find(rows,stored);
            if(row==null)return null;return validateRequest(row,stored,request);
        });}catch(RuntimeException e){throw failure();}
    }
    /** Never called through DependencyCalls: insert/fence/readback commit on the orchestration thread. */
    public Captured persist(WorkLease lease,PriorAssessmentHistory.Request request,PriorAssessmentHistory proposed){
        outside();
        if(request.accepted().configuration().rules().stream().noneMatch(r->r.code()==RuleCode.PREVIOUS_RISK_FLAGS&&r.enabled()))return new Captured(lease,proposed);
        try{return writes.execute(status->{
            var parent=work.lock(PersistenceValues.id(lease.assessmentId()));var now=clock.instant();PersistenceValues.timestamp(now);RiskWorkService.fence(parent,lease,now);
            var stored=acceptance.find(request.accepted().identity()).orElseThrow(PriorHistoryCaptureService::failure);matches(request.accepted(),stored);
            if(!stored.assessmentId().equals(Long.toString(parent.id()))||!stored.rowVersion().equals(Long.toString(parent.revision()))||!stored.configurationSnapshotId().equals(Long.toString(parent.snapshot()))||now.isBefore(stored.createdAt()))throw failure();
            var rows=evidence.children(parent.id());var header=evidence.header(parent.id());
            var existing=header.hash()==null?PriorHistoryCaptureCodec.partial(rows,stored).orElse(null):PriorHistoryCaptureCodec.find(rows,stored);
            boolean inserting=existing==null;
            if(inserting&&header.hash()!=null)throw failure();
            if(inserting&&parent.revision()==Long.MAX_VALUE)throw new WorkFailure(WorkFailure.Code.WORK_COUNTER_EXHAUSTED);
            var row=existing;
            if(inserting){row=PriorHistoryCaptureCodec.encode(request,proposed,now);PriorHistoryCaptureCodec.decode(row,stored);row=evidence.insert(parent.id(),row);}
            var saved=validateRequest(row,stored,request);long revision=inserting?parent.revision()+1:parent.revision();
            evidence.captureFence(parent,revision); // Oracle expiry rechecked AFTER CLOB insertion.
            var after=work.read(parent.id());
            var expected=new RiskWorkRepository.Row(parent.id(),parent.snapshot(),parent.state(),parent.token(),parent.epoch(),parent.attempt(),revision,parent.expiry(),parent.created(),parent.cutoff(),parent.errorCode(),parent.errorMessage());
            if(!after.equals(expected)||!evidence.header(parent.id()).equals(header))throw failure();
            var reread=PriorHistoryCaptureCodec.find(evidence.children(parent.id()),stored);if(reread==null||!reread.equals(row))throw failure();
            return new Captured(after.lease(),saved);
        });}catch(WorkFailure e){throw e;}catch(RuntimeException e){throw failure();}
    }
    private static PriorAssessmentHistory validateRequest(RiskEvidenceRepository.Stored row,AcceptedAssessment stored,PriorAssessmentHistory.Request request){
        var history=PriorHistoryCaptureCodec.decode(row,stored);
        if(!PriorHistoryCaptureCodec.encode(request,history,row.captured()).withId(row.id()).equals(row))throw failure();return history;
    }
    private static void matches(AcceptanceRequest request,AcceptedAssessment stored){
        var canonical=new CanonicalAcceptance();if(!canonical.configuration(request.configuration()).hash().equals(stored.configurationHash())
            ||!canonical.acceptance(request,stored.configurationVersionId(),stored.configurationRowVersion(),stored.configurationHash()).hash().equals(stored.acceptanceHash()))throw failure();
    }
    private static void outside(){if(TransactionSynchronizationManager.isActualTransactionActive())throw failure();}
    private static boolean disabled(PriorAssessmentHistory.Request r){return r.accepted().configuration().rules().stream().noneMatch(v->v.code()==RuleCode.PREVIOUS_RISK_FLAGS&&v.enabled());}
    private static PriorAssessmentHistory unavailable(PriorAssessmentHistory.Request r){var p=r.accepted().current();return new PriorAssessmentHistory(com.ofss.risk.engine.RiskInput.Evidence.unavailable(p.supplierId(),p.currencyCode(),r.accepted().cutoff()),new EvidenceFreezeRequest.Source("risk-assessment-service","supplier-completed-history/"+p.supplierId(),null));}
    private static void check(Deadline d){if(d==null||d.remainingNanos()==0||Thread.currentThread().isInterrupted())throw failure();}
    private static SubmissionFailure failure(){return new SubmissionFailure(SubmissionFailure.Code.DEPENDENCY_UNAVAILABLE);}
}
