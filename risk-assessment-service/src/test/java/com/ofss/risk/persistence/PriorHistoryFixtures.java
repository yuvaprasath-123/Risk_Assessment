package com.ofss.risk.persistence;

import com.ofss.risk.contract.*;
import com.ofss.risk.engine.*;
import java.util.*;
import java.time.Instant;
import static com.ofss.risk.persistence.PriorAssessmentHistoryRepository.*;

final class PriorHistoryFixtures {
    static AcceptanceRequest request(){return AcceptanceFixtures.request(FinalizationFixtures.config());}
    static Candidate candidate(long id,String source,String key,Instant completed){return new Candidate(id,new SubmissionIdentity(source,key),"456","INR",completed);}
    static Verified verified(Candidate row,String alias,Set<RuleCode> triggered){return new Verified(row,alias,
        new RiskInput.PriorAssessment("d2-risk-assessment:"+row.id(),submissionReference(row.identity()),row.supplier(),row.completed(),triggered),"a".repeat(64));}
    static AssessmentReadView view(Candidate candidate){
        var config=FinalizationFixtures.config();var result=new RiskScoringEngine().evaluate(config,FinalizationFixtures.input(true));
        var factors=result.factors().stream().map(f->new AssessmentReadView.Factor(f.ruleCode().name(),f.ruleCode().ordinal()+1,f.outcome().name(),f.configuredWeight(),f.contribution(),f.explanation())).toList();
        return new AssessmentReadView(Long.toString(candidate.id()),"3","COMPLETED",candidate.completed().minusSeconds(10).toString(),candidate.completed().minusSeconds(5).toString(),
            candidate.completed().minusSeconds(1).toString(),candidate.completed().toString(),"9","2",PinnedRiskEvaluators.CURRENT,
            new AssessmentReadView.Score(135,100,100,"HIGH"),factors,"2","b".repeat(64),Map.of());
    }
    static RiskConfiguration onlyPrevious(RiskConfiguration base){return new RiskConfiguration(base.versionId(),base.rowVersion(),base.contentHash(),base.currencyCode(),base.maxFinalScore(),base.rules().stream()
        .map(r->new RiskConfiguration.Rule(r.code(),r.code()==RuleCode.PREVIOUS_RISK_FLAGS,r.weight(),r.evaluationOrder(),r.parameterSchemaVersion(),r.parameters())).toList(),base.bands(),base.approvalBoundaries());}
}
