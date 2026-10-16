package com.ofss.risk.persistence;

import com.ofss.risk.contract.*;
import com.ofss.risk.engine.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static com.ofss.risk.engine.RiskInput.*;
import static com.ofss.risk.persistence.EvidenceFreezeRequest.*;

/** Synthetic, supplied evidence only. No upstream records, seed data or active configuration. */
final class OracleFinalizationFixtures {
    static final String PREFIX="D2_RISK_FINAL_";
    static final Set<String> KEYS=Set.of("success","nanoseconds","maximum-score","insufficient","retries","concurrent","fencing","final-expiry",
        "rollback-factor","rollback-completed","rollback-readback","completed-work","policy-unresolved");
    final String namespace;
    OracleFinalizationFixtures() { this(PREFIX+UUID.randomUUID()); }
    OracleFinalizationFixtures(String namespace) { requireNamespace(namespace); this.namespace=namespace; }
    static void requireNamespace(String namespace) {
        if(namespace==null || !namespace.startsWith(PREFIX)) throw new IllegalArgumentException("Exact finalization namespace required");
        String uuid=namespace.substring(PREFIX.length());
        try { if(!UUID.fromString(uuid).toString().equals(uuid)) throw new IllegalArgumentException(); }
        catch(IllegalArgumentException e) { throw new IllegalArgumentException("Exact finalization namespace required"); }
    }
    AcceptanceRequest acceptance(String key,Instant created) {
        if(!KEYS.contains(key)) throw new IllegalArgumentException("Unknown finalization fixture key");
        var base=FinalizationFixtures.config(); var rules=base.rules().stream().map(r->{
            RuleParameters parameters=r.parameters();
            if(r.code()==RuleCode.UNUSUAL_AMOUNT) parameters=new RuleParameters.Amount(7776000,5,new BigDecimal("2"),RuleParameters.ZeroBaselinePolicy.INSUFFICIENT_HISTORY);
            if(key.equals("policy-unresolved") && r.code()==RuleCode.NEAR_DUPLICATE_INVOICE)
                parameters=new RuleParameters.NearDuplicate(2592000,RuleParameters.HistoryEligibility.VALIDATED_SUBMISSIONS,RuleParameters.InvoiceNumberNormalization.UNRESOLVED);
            return new RiskConfiguration.Rule(r.code(),r.enabled(),key.equals("maximum-score")?Integer.MAX_VALUE:r.weight(),r.evaluationOrder(),r.parameterSchemaVersion(),parameters);
        }).toList();
        var config=new RiskConfiguration(base.versionId(),base.rowVersion(),"ORACLE_FINAL_TEST:"+namespace,"INR",100,rules,base.bands(),base.approvalBoundaries());
        Instant cutoff=created.minusSeconds(1).minusNanos(101); String submission=namespace+"/"+key;
        var current=new Payment("current-ಕನ್ನಡ😀",submission,"123","CURRENT-ಕನ್ನಡ😀","456","INR",new BigDecimal("40.01"),cutoff.minusNanos(1),null,PaymentStatus.PENDING_APPROVAL,true);
        return new AcceptanceRequest(new SubmissionIdentity(namespace,key),17,42,"synthetic-final-pass/"+key,null,current,cutoff,
            Set.of(submission,submission+"/retry"),PinnedRiskEvaluators.CURRENT,config,"final-fixture/"+key);
    }
    EvidenceFreezeRequest evidence(WorkLease lease,AcceptanceRequest acceptance) {
        var cutoff=acceptance.cutoff(); String key=acceptance.identity().key(); boolean populated=!key.equals("insufficient");
        String payload=OracleEvidenceFixtures.payload(); var history=new ArrayList<Payment>();
        if(populated) for(int i=0;i<9;i++) history.add(new Payment("payment-"+i+payload,namespace+"/history/"+key+"/"+i,"past-"+i,"DIFFERENT-"+i,
            "456","INR",new BigDecimal(i<4?"40.01":"10.01"),cutoff.minusSeconds(7200+i).minusNanos(102),
            i<4?null:cutoff.minusSeconds(3600+i).minusNanos(103),i<4?PaymentStatus.APPROVED:PaymentStatus.COMPLETE,true));
        var supplier=new Supplier("supplier-"+payload,"456",cutoff.minusSeconds(populated?100000:4000000).minusNanos(104),SupplierStatus.ACTIVE);
        var input=new RiskInput(acceptance.current(),cutoff,acceptance.currentAndRetrySubmissionIds(),
            envelope(key,Kind.PAYMENT_HISTORY,history,cutoff.minusSeconds(7776000),cutoff),
            envelope(key,Kind.SUPPLIER,List.of(supplier),cutoff,cutoff),
            envelope(key,Kind.SUPPLIER_TRANSITIONS,populated?List.of(new StatusTransition("transition-"+payload,"456",SupplierStatus.INACTIVE,SupplierStatus.ACTIVE,cutoff.minusSeconds(100).minusNanos(105))):List.of(),cutoff.minusSeconds(2592000),cutoff),
            envelope(key,Kind.PRIOR_ASSESSMENTS,populated?List.of(new PriorAssessment("original-flags-"+payload,namespace+"/prior/"+key,"456",cutoff.minusSeconds(200).minusNanos(106),Set.of(RuleCode.NEW_SUPPLIER))):List.of(),cutoff.minusSeconds(7776000),cutoff));
        var sources=new EnumMap<Kind,Source>(Kind.class);
        for(var kind:Kind.values()) if(kind!=Kind.CURRENT_INPUT) sources.put(kind,new Source("synthetic-final-adapter","final-record/"+key,"r-ಕನ್ನಡ😀"));
        return new EvidenceFreezeRequest(lease,acceptance,input,sources);
    }
    static <T> Evidence<T> envelope(String key,Kind kind,List<T> records,Instant start,Instant cutoff) {
        return new Evidence<>(Availability.COMPLETE,"final-source/"+key+"/"+kind,"456","INR",cutoff,start,records);
    }
}
