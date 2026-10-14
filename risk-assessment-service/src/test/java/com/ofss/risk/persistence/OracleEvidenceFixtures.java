package com.ofss.risk.persistence;

import com.ofss.risk.engine.*;
import com.ofss.risk.contract.RuleCode;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static com.ofss.risk.engine.RiskInput.*;
import static com.ofss.risk.persistence.EvidenceFreezeRequest.*;

final class OracleEvidenceFixtures {
    static final String PREFIX="D2_RISK_FREEZE_", EVALUATOR="oracle-freeze-test-v1";
    static final Set<String> KEYS=Set.of("roundtrip","retries","partial-unavailable","partial-incomplete",
        "rollback-child","rollback-associated","fencing","recovery","concurrent","final-expiry");
    final String namespace=PREFIX+UUID.randomUUID();
    static void requireNamespace(String namespace) {
        if(namespace==null || !namespace.startsWith(PREFIX)) throw new IllegalArgumentException("Exact freeze namespace required");
        String uuid=namespace.substring(PREFIX.length());
        try { if(!UUID.fromString(uuid).toString().equals(uuid)) throw new IllegalArgumentException(); }
        catch(IllegalArgumentException e) { throw new IllegalArgumentException("Exact freeze namespace required"); }
    }
    static String payload() { return "ಕನ್ನಡ 日本語 हिन्दी 😀 \"\\\n".repeat(3072); }
    AcceptanceRequest acceptance(String key,Instant created) {
        if(!KEYS.contains(key)) throw new IllegalArgumentException("Unknown freeze fixture key");
        var base=AcceptanceFixtures.config(); var configuration=new RiskConfiguration(base.versionId(),base.rowVersion(),"ORACLE_FREEZE_TEST:"+namespace,
            base.currencyCode(),base.maxFinalScore(),base.rules(),base.bands(),base.approvalBoundaries());
        Instant cutoff=created.minusSeconds(1).minusNanos(101); String engineId=namespace+"/"+key;
        var payment=new Payment("synthetic-current/"+engineId,engineId,"123","SYNTHETIC-INVOICE "+payload(),"456","INR",new BigDecimal("9999999999999.99"),
            cutoff.minusNanos(1),null,PaymentStatus.PENDING_APPROVAL,true);
        return new AcceptanceRequest(new SubmissionIdentity(namespace,key),17,42,"synthetic-freeze-pass/"+key,null,payment,cutoff,
            Set.of(engineId,engineId+"/retry"),EVALUATOR,configuration,"freeze-fixture/"+key);
    }
    EvidenceFreezeRequest request(WorkLease lease,AcceptanceRequest acceptance) {
        Instant cutoff=acceptance.cutoff(); String key=acceptance.identity().key();
        var payment=new Payment("history-ಕನ್ನಡ😀",namespace+"/history/"+key,"456","PAST-INVOICE "+payload(),"456","INR",new BigDecimal("1234567890123.45"),
            cutoff.minusSeconds(3).minusNanos(102),cutoff.minusSeconds(2).minusNanos(103),PaymentStatus.COMPLETE,true);
        var supplier=new Supplier("supplier "+payload(),"456",cutoff.minusSeconds(10000000).minusNanos(104),SupplierStatus.ACTIVE);
        var transition=new StatusTransition("transition "+payload(),"456",SupplierStatus.INACTIVE,SupplierStatus.ACTIVE,cutoff.minusSeconds(1).minusNanos(105));
        var prior=new PriorAssessment("prior "+payload(),namespace+"/prior/"+key,"456",cutoff.minusSeconds(1).minusNanos(106),Set.of(RuleCode.STRUCTURING,RuleCode.NEW_SUPPLIER));
        var input=new RiskInput(acceptance.current(),cutoff,acceptance.currentAndRetrySubmissionIds(),
            envelope(key,Kind.PAYMENT_HISTORY,List.of(payment),cutoff.minusSeconds(7776000),cutoff),
            envelope(key,Kind.SUPPLIER,List.of(supplier),cutoff,cutoff),
            envelope(key,Kind.SUPPLIER_TRANSITIONS,List.of(transition),cutoff.minusSeconds(2592000),cutoff),
            envelope(key,Kind.PRIOR_ASSESSMENTS,List.of(prior),cutoff.minusSeconds(7776000),cutoff));
        var sources=new EnumMap<Kind,Source>(Kind.class);
        for(Kind kind:Kind.values()) if(kind!=Kind.CURRENT_INPUT) sources.put(kind,new Source("synthetic-freeze-adapter","freeze-record/"+key,"r-ಕನ್ನಡ😀"));
        return new EvidenceFreezeRequest(lease,acceptance,input,sources);
    }
    static <T> Evidence<T> envelope(String key,Kind kind,List<T> records,Instant start,Instant cutoff) {
        return new Evidence<>(Availability.COMPLETE,"freeze-source/"+key+"/"+kind,"456","INR",cutoff,start,records);
    }
}
