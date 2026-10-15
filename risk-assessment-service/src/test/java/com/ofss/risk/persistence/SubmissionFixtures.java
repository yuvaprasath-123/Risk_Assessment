package com.ofss.risk.persistence;

import com.ofss.risk.submission.*;
import com.ofss.risk.engine.*;
import java.util.*;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.DeserializationFeature;

/** Controlled test publication only; never evidence of readiness-approved publication. */
public final class SubmissionFixtures {
    public static AcceptanceRequest request(){return AcceptanceFixtures.request(FinalizationFixtures.config());}
    public static SubmissionCommand command(){var r=request();return new SubmissionCommand(SubmissionCommand.VERSION,r.identity().source(),r.identity().key(),Long.toString(r.paymentId()),r.validationReference(),r.validationRevision(),null);}
    public static AcceptedAssessment stored(String state){var r=request();var canonical=new CanonicalAcceptance();var c=canonical.configuration(r.configuration());var a=canonical.acceptance(r,r.configuration().versionId(),r.configuration().rowVersion(),c.hash());
        return new AcceptedAssessment("1",r.identity(),"2",r.configuration().versionId(),r.configuration().rowVersion(),"0",state,r.cutoff(),AcceptanceFixtures.CREATED,a.hash(),c.hash(),a.json(),c.json());}
    public static AssessmentReadView view(String state){var a=stored(state);return new AssessmentReadView("1","4",state,a.createdAt().toString(),a.cutoff().toString(),
        state.equals("COMPLETED")?a.createdAt().toString():null,state.equals("COMPLETED")?a.createdAt().toString():null,a.configurationVersionId(),a.configurationRowVersion(),PinnedRiskEvaluators.CURRENT,
        state.equals("COMPLETED")?new AssessmentReadView.Score(135,100,100,"HIGH"):null,null,"2",a.configurationHash(),Map.of());}
    @SuppressWarnings("unchecked") public static SubmissionAdapters.PublishedSnapshot published(){
        try{
            var mapper=JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,DeserializationFeature.USE_BIG_INTEGER_FOR_INTS).build();
            var config=request().configuration();var rules=config.rules().stream().map(r->{
                var m=new TreeMap<String,Object>();m.put("ruleCode",r.code().name());m.put("enabled",r.enabled());m.put("weight",r.weight());m.put("evaluationOrder",r.evaluationOrder());
                m.put("parameterSchemaVersion",r.parameterSchemaVersion());
                var parameters=new TreeMap<String,Object>((Map<String,Object>)mapper.readValue(mapper.writeValueAsString(r.parameters()),Map.class));
                if(r.parameters() instanceof com.ofss.risk.contract.RuleParameters.Amount amount)parameters.put("meanMultiplier",amount.meanMultiplier().toPlainString());
                m.put("parameters",parameters);return m;
            }).toList();
            var bands=config.bands().stream().map(b->Map.of("bandCode",b.code(),"minScoreInclusive",b.minimum(),"maxScoreInclusive",b.maximum(),"approvalLevel",com.ofss.risk.contract.ApprovalPolicyContracts.BAND_LEVELS.get(b.code()))).toList();
            var root=new TreeMap<String,Object>();root.put("hashFormat","configuration-content-v2");root.put("versionId",config.versionId());root.put("riskRules",rules);root.put("riskBands",bands);
            root.put("versionNumber",1);root.put("versionLabel","CONTROLLED TEST FIXTURE");root.put("description",null);root.put("isDemonstration",true);
            root.put("createdAt","2026-10-13T00:00:00Z");root.put("createdByUserId","42");
            root.put("scorePolicyCode","CAP_AT_MAX");root.put("maxFinalScore",100);
            var last=new TreeMap<String,Object>();last.put("approvalLevel",3);last.put("minAmountInclusive","456.00");last.put("maxAmountExclusive",null);
            root.put("approvalThresholds",List.of(Map.of("approvalLevel",1,"minAmountInclusive","0.00","maxAmountExclusive","123.00"),Map.of("approvalLevel",2,"minAmountInclusive","123.00","maxAmountExclusive","456.00"),last));
            root.put("approvalSlas",java.util.stream.IntStream.rangeClosed(1,3).mapToObj(level->{
                var sla=new TreeMap<String,Object>();sla.put("approvalLevel",level);sla.put("durationSeconds",86400);
                sla.put("startEventCode","APPROVAL_PENDING_V1");sla.put("escalationPolicyCode",level<3?"NEXT_LEVEL_V1":"SAME_LEVEL_BACKUP_ONCE_V1");
                sla.put("terminalLevelPolicyCode",level<3?null:"MANUAL_REVIEW_V1");return sla;
            }).toList());
            root.put("approvalPolicy",com.ofss.risk.contract.ApprovalPolicyContracts.snapshot(com.ofss.risk.contract.ApprovalPolicyContracts.BAND_LEVELS,
                java.util.stream.IntStream.rangeClosed(1,3).mapToObj(level->new com.ofss.risk.contract.ApprovalPolicyContracts.Sla(level,86400,"APPROVAL_PENDING_V1",level<3?"NEXT_LEVEL_V1":"SAME_LEVEL_BACKUP_ONCE_V1",level<3?null:"MANUAL_REVIEW_V1")).toList()));
            String json=canonical(root,mapper);String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8)));
            return new SubmissionAdapters.PublishedSnapshot(config.versionId(),config.rowVersion(),"PUBLISHED","configuration-content-v2",hash,json);
        }catch(Exception e){throw new AssertionError(e);}
    }
    private static String canonical(Object v,JsonMapper mapper){
        if(v instanceof Map<?,?> m){var sorted=new TreeMap<String,Object>();m.forEach((k,x)->sorted.put(k.toString(),x));return "{"+String.join(",",sorted.entrySet().stream().map(e->mapper.writeValueAsString(e.getKey())+":"+canonical(e.getValue(),mapper)).toList())+"}";}
        if(v instanceof List<?> l)return "["+String.join(",",l.stream().map(x->canonical(x,mapper)).toList())+"]";
        if(v instanceof BigDecimal b)return b.stripTrailingZeros().toPlainString();return mapper.writeValueAsString(v);
    }
}
