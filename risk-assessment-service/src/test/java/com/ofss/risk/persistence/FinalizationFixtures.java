package com.ofss.risk.persistence;

import com.ofss.risk.engine.*;
import com.ofss.risk.contract.*;
import java.math.BigDecimal;
import java.util.*;
import static com.ofss.risk.engine.RiskInput.*;

final class FinalizationFixtures {
    static RiskConfiguration config() {
        var base=AcceptanceFixtures.config(); int[] weights={35,15,20,15,10,15,25};
        return new RiskConfiguration(base.versionId(),base.rowVersion(),base.contentHash(),"INR",100,base.rules().stream()
            .map(r->new RiskConfiguration.Rule(r.code(),true,weights[r.code().ordinal()],r.evaluationOrder(),r.parameterSchemaVersion(),r.parameters())).toList(),base.bands(),base.approvalBoundaries());
    }
    static RiskInput input(boolean triggered) {
        var base=FreezeFixtures.input(); var old=base.current(); var cutoff=base.cutoff();
        var current=new Payment(old.evidenceId(),old.submissionId(),old.invoiceId(),old.invoiceNumber(),old.supplierId(),"INR",new BigDecimal("40.00"),old.submittedAt(),null,PaymentStatus.PENDING_APPROVAL,true);
        var history=new ArrayList<Payment>();
        if(triggered) for(int i=0;i<9;i++) history.add(new Payment("payment-ಕನ್ನಡ😀-"+i,"prior-"+i,"invoice-"+i,"DIFFERENT-"+i,"456","INR",
            new BigDecimal(i<4?"40.00":"10.00"),cutoff.minusSeconds(7200+i),i<4?null:cutoff.minusSeconds(3600+i),
            i<4?PaymentStatus.APPROVED:PaymentStatus.COMPLETE,true));
        return new RiskInput(current,cutoff,base.currentAndRetrySubmissionIds(),FreezeFixtures.envelope("payments",history,cutoff.minusSeconds(7776000)),
            FreezeFixtures.envelope("supplier",List.of(new Supplier("supplier-ಕನ್ನಡ😀","456",cutoff.minusSeconds(triggered?100000:4000000),SupplierStatus.ACTIVE)),cutoff),
            FreezeFixtures.envelope("transitions",triggered?List.of(new StatusTransition("transition-😀","456",SupplierStatus.INACTIVE,SupplierStatus.ACTIVE,cutoff.minusSeconds(100))):List.of(),cutoff.minusSeconds(2592000)),
            FreezeFixtures.envelope("prior",triggered?List.of(new PriorAssessment("original-flags-😀","old-submission","456",cutoff.minusSeconds(200),Set.of(RuleCode.NEW_SUPPLIER))):List.of(),cutoff.minusSeconds(7776000)));
    }
    static FrozenAssessmentLoader.Snapshot snapshot(RiskConfiguration config,RiskInput input) {
        var old=FreezeFixtures.acceptance(); var request=new AcceptanceRequest(old.identity(),old.paymentId(),old.requesterId(),old.validationReference(),old.validationRevision(),
            input.current(),input.cutoff(),input.currentAndRetrySubmissionIds(),PinnedRiskEvaluators.CURRENT,config,null);
        var accepted=FreezeFixtures.stored(request,1); var freeze=FreezeFixtures.request(FreezeFixtures.row().lease(),request,input);
        var document=new CanonicalEvidence().input(freeze,accepted); var rows=new ArrayList<RiskEvidenceRepository.Stored>();
        for(var r:RiskEvidenceService.inputs(freeze,accepted,FreezeFixtures.NOW)) rows.add(r.withId(100+rows.size()));
        rows.add(RiskEvidenceService.manifest(freeze,document,rows,FreezeFixtures.NOW).withId(105));
        return new FrozenAssessmentLoader.Snapshot(accepted,FreezeFixtures.row(),new RiskEvidenceRepository.Header(document.hash(),105L,FreezeFixtures.NOW),List.copyOf(rows),config,input,PinnedRiskEvaluators.CURRENT);
    }
    static FrozenAssessmentLoader.Snapshot snapshot() { return snapshot(config(),input(true)); }
    static FrozenAssessmentLoader.Snapshot completed(FrozenAssessmentLoader.Snapshot s) {
        var r=s.work(); var a=s.accepted();
        var row=new RiskWorkRepository.Row(r.id(),r.snapshot(),"COMPLETED",null,r.epoch(),r.attempt(),r.revision()+1,null,r.created(),r.cutoff(),null,null);
        var accepted=new AcceptedAssessment(a.assessmentId(),a.identity(),a.configurationSnapshotId(),a.configurationVersionId(),a.configurationRowVersion(),Long.toString(row.revision()),row.state(),a.cutoff(),a.createdAt(),a.acceptanceHash(),a.configurationHash(),a.acceptanceJson(),a.configurationJson());
        return new FrozenAssessmentLoader.Snapshot(accepted,row,s.header(),s.evidence(),s.config(),s.input(),s.evaluator());
    }
    static FinalizationRequest request(FrozenAssessmentLoader.Snapshot s) { return new FinalizationRequest(s.accepted().identity(),s.work().lease(),s.accepted().acceptanceHash(),s.accepted().configurationHash(),s.header().hash()); }
}
