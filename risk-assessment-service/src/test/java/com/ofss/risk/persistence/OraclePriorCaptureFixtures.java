package com.ofss.risk.persistence;

import com.ofss.risk.contract.*;
import com.ofss.risk.engine.*;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import static com.ofss.risk.engine.RiskInput.*;

final class OraclePriorCaptureFixtures {
    static final String PREFIX="D2_RISK_PRIOR_";
    static final Set<String> CASES=Set.of("capture","changed","takeover","bounds","capacity","rollback","excluded","visibility");
    static final Set<String> KEYS;
    static{var keys=new HashSet<>(CASES);for(var key:CASES)for(int n=0;n<5;n++)keys.add("seed-"+key+"-"+n);KEYS=Set.copyOf(keys);}
    final String namespace;OraclePriorCaptureFixtures(){this(PREFIX+UUID.randomUUID());}
    OraclePriorCaptureFixtures(String namespace){validate(namespace);this.namespace=namespace;}
    static void validate(String namespace){if(namespace==null||!namespace.startsWith(PREFIX))throw new IllegalArgumentException("Exact prior capture namespace required");var part=namespace.substring(PREFIX.length());if(!UUID.fromString(part).toString().equals(part))throw new IllegalArgumentException("Canonical prior UUID required");}
    long supplier(String caseKey){return 8000000000000L+Integer.toUnsignedLong(namespace.hashCode())*10+new TreeSet<>(CASES).headSet(caseKey).size();}
    AcceptanceRequest request(String key,String caseKey,Instant cutoff,boolean prior,boolean zero){
        if(!KEYS.contains(key)||!CASES.contains(caseKey))throw new IllegalArgumentException("Unknown prior fixture key");var base=FinalizationFixtures.config();
        var config=new RiskConfiguration(base.versionId(),base.rowVersion(),"ORACLE_PRIOR_TEST:"+namespace,"INR",100,base.rules().stream()
            .map(r->new RiskConfiguration.Rule(r.code(),r.code()==(prior?RuleCode.PREVIOUS_RISK_FLAGS:RuleCode.NEW_SUPPLIER),zero?0:r.weight(),r.evaluationOrder(),r.parameterSchemaVersion(),r.parameters())).toList(),base.bands(),base.approvalBoundaries());
        String submission=namespace+"/"+key+"/ಕನ್ನಡ😀";var current=new Payment("prior-current-ಕನ್ನಡ😀",submission,"123","PRIOR-FIXTURE",Long.toString(supplier(caseKey)),"INR",new BigDecimal("40.01"),cutoff.minusNanos(1),null,PaymentStatus.PENDING_APPROVAL,true);
        return new AcceptanceRequest(new SubmissionIdentity(namespace,key),17,prior?42:99,"synthetic-prior-pass/"+key,null,current,cutoff,Set.of(submission,submission+"/retry"),PinnedRiskEvaluators.CURRENT,config,null);
    }
    EvidenceFreezeRequest sourceInput(WorkLease lease,AcceptanceRequest a){
        String supplier=a.current().supplierId();var cutoff=a.cutoff();var unavailable=Evidence.<Payment>unavailable(supplier,"INR",cutoff);
        var input=new RiskInput(a.current(),cutoff,a.currentAndRetrySubmissionIds(),unavailable,new Evidence<>(Availability.COMPLETE,"prior-source-supplier",supplier,"INR",cutoff,cutoff,List.of(new Supplier("source-ಕನ್ನಡ😀",supplier,cutoff.minusNanos(1),SupplierStatus.ACTIVE))),Evidence.unavailable(supplier,"INR",cutoff),Evidence.unavailable(supplier,"INR",cutoff));
        var sources=new EnumMap<EvidenceFreezeRequest.Kind,EvidenceFreezeRequest.Source>(EvidenceFreezeRequest.Kind.class);for(var k:EvidenceFreezeRequest.Kind.values())if(k!=EvidenceFreezeRequest.Kind.CURRENT_INPUT)sources.put(k,new EvidenceFreezeRequest.Source("synthetic-prior-source",key(a),null));
        return new EvidenceFreezeRequest(lease,a,input,sources);
    }
    static String key(AcceptanceRequest a){return a.identity().key();}
}
