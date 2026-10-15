package com.ofss.risk.submission;

import com.ofss.risk.persistence.*;
import com.ofss.risk.engine.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static com.ofss.risk.engine.RiskInput.*;
import static com.ofss.risk.submission.SubmissionAdapters.*;

/** Synthetic data only. Registered exclusively by risk-submission-demo. Never supplies a published configuration. */
public final class DemoSubmissionAdapters {
    private DemoSubmissionAdapters() {}
    public static VerifiedFacts facts(SubmissionCommand command,Instant cutoff,Deadline deadline) {
        if(!command.submissionSource().startsWith("demo.") || !command.validationReference().startsWith("DEMO-PASS-"))throw SubmissionAdapters.unavailable();
        var current=new Payment("demo-current",command.submissionKey(),"9000000001","DEMO-INVOICE","9000000002","INR",new BigDecimal("1.00"),
            cutoff.minusSeconds(1),null,PaymentStatus.PENDING_APPROVAL,true);
        return new VerifiedFacts(command.identity(),SubmissionCommand.id(command.paymentReference()),9000000003L,command.validationReference(),command.validationRevision(),current,Set.of(current.submissionId()));
    }
    public static Bundle evidence(AcceptanceRequest accepted,Deadline deadline) {
        if(!accepted.identity().source().startsWith("demo."))throw SubmissionAdapters.unavailable();
        var p=accepted.current();var cutoff=accepted.cutoff();
        long window=accepted.configuration().rules().stream().filter(RiskConfiguration.Rule::enabled).mapToLong(r->r.parameters().windowSeconds()).max().orElse(1);
        var from=cutoff.minusSeconds(window);
        var input=new RiskInput(p,cutoff,accepted.currentAndRetrySubmissionIds(),envelope("demo-payments",p,cutoff,from,List.of()),
            envelope("demo-supplier",p,cutoff,cutoff,List.of(new Supplier("demo-supplier",p.supplierId(),Instant.parse("1900-01-01T00:00:00Z"),SupplierStatus.ACTIVE))),
            envelope("demo-transitions",p,cutoff,from,List.of()),envelope("demo-prior",p,cutoff,from,List.of()));
        var sources=new EnumMap<EvidenceFreezeRequest.Kind,EvidenceFreezeRequest.Source>(EvidenceFreezeRequest.Kind.class);
        for(var kind:EvidenceFreezeRequest.Kind.values())if(kind!=EvidenceFreezeRequest.Kind.CURRENT_INPUT)sources.put(kind,new EvidenceFreezeRequest.Source("risk-demo-fixture",kind.name(),"synthetic-v1"));
        return new Bundle(input,sources);
    }
    private static <T> RiskInput.Evidence<T> envelope(String id,Payment p,Instant cutoff,Instant from,List<T> records){
        return new RiskInput.Evidence<>(Availability.COMPLETE,id,p.supplierId(),"INR",cutoff,from,records);
    }
}
