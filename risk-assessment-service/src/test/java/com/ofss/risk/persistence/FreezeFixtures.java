package com.ofss.risk.persistence;

import com.ofss.risk.engine.*;
import java.time.*;
import java.util.*;
import static com.ofss.risk.engine.RiskInput.*;
import static com.ofss.risk.persistence.EvidenceFreezeRequest.*;

final class FreezeFixtures {
    static final Instant NOW=RiskWorkServiceTest.NOW;
    static AcceptanceRequest acceptance() { return AcceptanceFixtures.request(AcceptanceFixtures.config()); }
    static RiskInput input() {
        var accepted=acceptance(); var cutoff=accepted.cutoff(); var current=accepted.current();
        return new RiskInput(current,cutoff,accepted.currentAndRetrySubmissionIds(),
            envelope("payments",List.of(),cutoff.minusSeconds(7776000)),
            envelope("supplier",List.of(new Supplier("supplier-ಕನ್ನಡ😀",current.supplierId(),cutoff.minusSeconds(4000000),SupplierStatus.ACTIVE)),cutoff),
            envelope("transitions",List.of(),cutoff.minusSeconds(2592000)),envelope("prior",List.of(),cutoff.minusSeconds(7776000)));
    }
    static <T> Evidence<T> envelope(String snapshot,List<T> records,Instant start) {
        return new Evidence<>(Availability.COMPLETE,snapshot,"456","INR",acceptance().cutoff(),start,records);
    }
    static RiskInput input(Evidence<Payment> payments,Evidence<Supplier> supplier,Evidence<StatusTransition> transitions,Evidence<PriorAssessment> prior) {
        var base=input(); return new RiskInput(base.current(),base.cutoff(),base.currentAndRetrySubmissionIds(),payments,supplier,transitions,prior);
    }
    static RiskWorkRepository.Row row() {
        return new RiskWorkRepository.Row(17,23,"IN_PROGRESS","synthetic-lease",1,1,1,NOW.plusSeconds(60),AcceptanceFixtures.CREATED,AcceptanceFixtures.CUTOFF,null,null);
    }
    static AcceptedAssessment stored(long revision) {
        return stored(acceptance(),revision);
    }
    static AcceptedAssessment stored(AcceptanceRequest request,long revision) {
        var canonical=new CanonicalAcceptance(); var config=canonical.configuration(request.configuration());
        var accepted=canonical.acceptance(request,request.configuration().versionId(),request.configuration().rowVersion(),config.hash());
        return new AcceptedAssessment("17",request.identity(),"23",request.configuration().versionId(),request.configuration().rowVersion(),
            Long.toString(revision),"IN_PROGRESS",request.cutoff(),AcceptanceFixtures.CREATED,accepted.hash(),config.hash(),accepted.json(),config.json());
    }
    static EvidenceFreezeRequest request(WorkLease lease,RiskInput input) { return request(lease,acceptance(),input); }
    static EvidenceFreezeRequest request(WorkLease lease,AcceptanceRequest acceptance,RiskInput input) {
        var sources=new EnumMap<Kind,Source>(Kind.class);
        for(Kind kind:Kind.values()) if(kind!=Kind.CURRENT_INPUT) sources.put(kind,new Source("synthetic-adapter","source-ಕನ್ನಡ😀",null));
        return new EvidenceFreezeRequest(lease,acceptance,input,sources);
    }
}
