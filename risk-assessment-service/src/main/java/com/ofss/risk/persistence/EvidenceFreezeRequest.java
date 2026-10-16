package com.ofss.risk.persistence;

import com.ofss.risk.engine.RiskInput;
import java.util.*;

/** Supplied adapter data only. Configuration must explicitly reproduce the accepted snapshot. */
public record EvidenceFreezeRequest(WorkLease lease, AcceptanceRequest acceptance, RiskInput input,
                                   Map<Kind, Source> sources) implements EvidenceContent {
    public enum Kind { CURRENT_INPUT, PAYMENT_HISTORY, SUPPLIER, SUPPLIER_TRANSITIONS, PRIOR_ASSESSMENTS }
    public record Source(String service, String recordReference, String revision) {
        public Source {
            PersistenceValues.text(service,64); PersistenceValues.optionalText(recordReference,128);
            PersistenceValues.optionalText(revision,128);
        }
    }
    public EvidenceFreezeRequest {
        Objects.requireNonNull(lease); Objects.requireNonNull(acceptance); Objects.requireNonNull(input);
        Objects.requireNonNull(acceptance.configuration(),"Explicit frozen configuration required");
        sources=Map.copyOf(sources);
        if(!sources.keySet().equals(EnumSet.complementOf(EnumSet.of(Kind.CURRENT_INPUT))))
            throw new IllegalArgumentException("Exactly four dependency source contracts required");
    }
}
