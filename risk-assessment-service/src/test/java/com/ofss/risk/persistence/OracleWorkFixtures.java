package com.ofss.risk.persistence;

import com.ofss.risk.engine.*;
import java.time.*;
import java.util.*;

/** TEST ONLY: separate namespace/markers from acceptance fixtures and all real submissions. */
final class OracleWorkFixtures {
    static final String PREFIX="D2_RISK_WORK_", EVALUATOR="oracle-work-test-v1";
    static final Set<String> KEYS=Set.of("competing","renewal","takeover","outcomes",
        "rollback-claim","rollback-diagnostic","rollback-parent","rollback-expiry");
    final String namespace=PREFIX+UUID.randomUUID();
    static void requireNamespace(String namespace) {
        if(namespace==null || !namespace.startsWith(PREFIX)) throw new IllegalArgumentException("Exact work fixture namespace required");
        String uuid=namespace.substring(PREFIX.length());
        try { if(!UUID.fromString(uuid).toString().equals(uuid)) throw new IllegalArgumentException(); }
        catch(IllegalArgumentException e) { throw new IllegalArgumentException("Exact work fixture namespace required"); }
    }
    AcceptanceRequest request(String key,Instant created) {
        if(!KEYS.contains(key)) throw new IllegalArgumentException("Unknown work fixture key");
        var base=AcceptanceFixtures.config(); String engineId=namespace+"/"+key; Instant cutoff=created.minusSeconds(1);
        var config=new RiskConfiguration(base.versionId(),base.rowVersion(),"ORACLE_WORK_TEST:"+namespace,
            base.currencyCode(),base.maxFinalScore(),base.rules(),base.bands(),base.approvalBoundaries());
        var p=AcceptanceFixtures.request(base).current();
        var current=new RiskInput.Payment("work-test/"+engineId,engineId,p.invoiceId(),"SYNTHETIC-WORK-INVOICE",p.supplierId(),
            p.currencyCode(),p.amount(),cutoff.minusNanos(1),null,p.statusAsOfCutoff(),true);
        return new AcceptanceRequest(new SubmissionIdentity(namespace,key),17,42,"synthetic-work-pass/"+key,null,
            current,cutoff,Set.of(engineId,engineId+"/retry"),EVALUATOR,config,"work-fixture/"+key);
    }
}
