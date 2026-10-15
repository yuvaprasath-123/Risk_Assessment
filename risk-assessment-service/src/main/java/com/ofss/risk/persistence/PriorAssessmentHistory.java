package com.ofss.risk.persistence;

import com.ofss.risk.engine.RiskInput;
import java.util.*;

/** Internal evidence only; namespaced identities are distinct from payment IDs and engine-local aliases. */
public record PriorAssessmentHistory(RiskInput.Evidence<RiskInput.PriorAssessment> evidence,EvidenceFreezeRequest.Source source,String populationJson) {
    public PriorAssessmentHistory(RiskInput.Evidence<RiskInput.PriorAssessment> evidence,EvidenceFreezeRequest.Source source){this(evidence,source,null);}
    public record Request(AcceptanceRequest accepted,Set<SubmissionIdentity> retryIdentities,String expectedPopulationHash) {
        public Request { Objects.requireNonNull(accepted);Objects.requireNonNull(accepted.configuration());retryIdentities=Set.copyOf(retryIdentities);
            if(expectedPopulationHash!=null&&!expectedPopulationHash.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid history receipt"); }
    }
}
