package com.ofss.risk.persistence;

import com.ofss.risk.contract.RuleCode;
import com.ofss.risk.engine.*;
import java.math.BigInteger;
import java.util.*;
import static com.ofss.risk.persistence.CanonicalEvidence.map;

/** Validates engine output and encodes the immutable, versioned factor detail envelope. */
final class FinalizedFactors {
    static final String FORMAT="risk-factor-v1";
    static final BigInteger MAX_RAW=new BigInteger("15032385529");
    static void validate(RiskResult result,FrozenAssessmentLoader.Snapshot s) {
        if(result.status()==RiskResult.Status.TECHNICAL_FAILURE) throw new FinalizationFailure(FinalizationFailure.Code.EVALUATION_TECHNICAL_FAILURE);
        if(result.status()==RiskResult.Status.POLICY_UNRESOLVED) throw new FinalizationFailure(FinalizationFailure.Code.POLICY_UNRESOLVED);
        if(!result.configurationSnapshot().equals(s.config()) || !result.inputSnapshot().equals(s.input())
            || !result.submissionId().equals(s.input().current().submissionId())
            || !result.configurationVersionId().equals(s.config().versionId())
            || !result.configurationRowVersion().equals(s.config().rowVersion())
            || !result.configurationContentHash().equals(s.config().contentHash()) || !result.cutoff().equals(s.input().cutoff())
            || result.factors().size()!=7) throw invalid();
        var codes=EnumSet.noneOf(RuleCode.class); BigInteger sum=BigInteger.ZERO;
        for(var f:result.factors()) {
            var rule=s.config().rules().stream().filter(r->r.code()==f.ruleCode()).findFirst().orElseThrow(FinalizedFactors::invalid);
            if(!codes.add(f.ruleCode()) || f.configuredWeight()!=rule.weight()
                || (f.outcome()==RiskResult.Outcome.DISABLED)!=!rule.enabled()
                || f.outcome()==RiskResult.Outcome.TECHNICAL_FAILURE || f.outcome()==RiskResult.Outcome.POLICY_UNRESOLVED
                || f.contribution()!=(f.outcome()==RiskResult.Outcome.TRIGGERED?rule.weight():0)) throw invalid();
            PersistenceValues.text(f.explanation(),2000);
            var allowed=new HashSet<String>();
            switch(f.ruleCode()) {
                case STRUCTURING,UNUSUAL_FREQUENCY -> { allowed.add(s.input().current().evidenceId()); s.input().payments().records().forEach(p->allowed.add(p.evidenceId())); }
                case UNUSUAL_AMOUNT,NEAR_DUPLICATE_INVOICE -> s.input().payments().records().forEach(p->allowed.add(p.evidenceId()));
                case RECENTLY_REACTIVATED_SUPPLIER -> s.input().supplierTransitions().records().forEach(p->allowed.add(p.evidenceId()));
                case NEW_SUPPLIER -> s.input().supplier().records().forEach(p->allowed.add(p.evidenceId()));
                case PREVIOUS_RISK_FLAGS -> s.input().previousAssessments().records().forEach(p->allowed.add(p.evidenceId()));
            }
            for(String id:f.matchedEvidenceIds()) if(!allowed.contains(id)) throw invalid();
            for(var entry:f.observations().entrySet()) { PersistenceValues.unicode(entry.getKey()); PersistenceValues.unicode(entry.getValue()); }
            if(!rule.enabled() && (!f.matchedEvidenceIds().isEmpty() || !f.observations().isEmpty())) throw invalid();
            sum=sum.add(BigInteger.valueOf(f.contribution()));
        }
        int score=sum.min(BigInteger.valueOf(s.config().maxFinalScore())).intValueExact();
        var band=s.config().bands().stream().filter(b->score>=b.minimum() && (b.maximum()==null || score<=b.maximum())).findFirst().orElseThrow(FinalizedFactors::invalid);
        if(!codes.equals(EnumSet.allOf(RuleCode.class)) || sum.compareTo(MAX_RAW)>0 || !sum.equals(result.rawTotal())
            || result.finalScore()!=score || !band.code().equals(result.riskBand())) throw invalid();
    }
    static String receipt(FinalizationRequest r) {
        return CanonicalEvidence.document(map("format","risk-completion-fence-v1","identity",r.identity(),"lease",r.lease(),
            "acceptanceHash",r.acceptanceHash(),"configurationHash",r.configurationHash(),"inputHash",r.inputHash())).hash();
    }
    static String details(RiskResult.Factor f,FrozenAssessmentLoader.Snapshot s,String receipt) {
        var refs=f.outcome()==RiskResult.Outcome.DISABLED?List.of():s.evidence().stream()
            .sorted(Comparator.comparing(RiskEvidenceRepository.Stored::key))
            .map(e->map("key",e.key(),"evidenceId",Long.toString(e.id()),"hash",e.hash())).toList();
        return CanonicalEvidence.document(map("format",FORMAT,"evaluatorVersion",s.evaluator(),"acceptanceHash",s.accepted().acceptanceHash(),
            "configurationHash",s.accepted().configurationHash(),"inputHash",s.header().hash(),"completionFenceHash",receipt,
            "manifestId",Long.toString(s.header().manifest()),"matchedEvidenceIds",f.matchedEvidenceIds(),"observations",f.observations(),"evidenceReferences",refs)).json();
    }
    @SuppressWarnings("unchecked") static RiskResult.Factor decode(RuleCode code,RiskResult.Outcome outcome,int weight,int contribution,String explanation,String json) {
        var body=CanonicalAcceptance.read(json);
        return new RiskResult.Factor(code,outcome,weight,contribution,explanation,(List<String>)(List<?>)body.get("matchedEvidenceIds"),(Map<String,String>)(Map<?,?>)body.get("observations"));
    }
    static FinalizationFailure invalid() { return new FinalizationFailure(FinalizationFailure.Code.INVALID_EVALUATION); }
}
