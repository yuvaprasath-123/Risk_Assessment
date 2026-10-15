package com.ofss.risk.persistence;

import com.ofss.risk.engine.RiskInput.*;
import com.ofss.risk.contract.RuleCode;
import java.util.*;
import static com.ofss.risk.persistence.CanonicalEvidence.*;

final class PriorCaptureFixtures {
    static PriorAssessmentHistory history(PriorAssessmentHistory.Request request,int count){
        var a=request.accepted();var from=a.cutoff().minusSeconds(a.configuration().rules().stream().filter(r->r.code()==RuleCode.PREVIOUS_RISK_FLAGS).findFirst().orElseThrow().parameters().windowSeconds());
        var records=new ArrayList<PriorAssessment>();var proofs=new ArrayList<Object>();
        for(int i=0;i<count;i++){var identity=new SubmissionIdentity("old-source","old-ಕನ್ನಡ😀-"+i);var p=new PriorAssessment("d2-risk-assessment:"+(900+i),PriorAssessmentHistoryRepository.submissionReference(identity),a.current().supplierId(),a.cutoff().minusNanos(count-i),Set.of(RuleCode.NEW_SUPPLIER));records.add(p);proofs.add(map("identity",identity,"originalSubmissionId","old-engine-id-"+i,"record",p,"resultHash","a".repeat(64)));}
        var exclusions=new HashSet<>(request.retryIdentities());exclusions.add(a.identity());
        var doc=document(map("format","risk-prior-population-v1","supplierId",a.current().supplierId(),"currency","INR","from",from,"cutoff",a.cutoff(),"currentIdentity",a.identity(),
            "excludedIdentities",exclusions.stream().sorted(Comparator.comparing(SubmissionIdentity::source).thenComparing(SubmissionIdentity::key)).toList(),"excludedAliases",a.currentAndRetrySubmissionIds(),"records",proofs));
        return new PriorAssessmentHistory(new Evidence<>(Availability.COMPLETE,"risk-prior-v1:"+doc.hash(),a.current().supplierId(),"INR",a.cutoff(),from,records),new EvidenceFreezeRequest.Source("risk-assessment-service","supplier-completed-history/"+a.current().supplierId(),doc.hash()),doc.json());
    }
}
