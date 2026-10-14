package com.ofss.risk.engine;

import java.time.Instant;
import java.util.Set;

/** Integration port only. No Developer 1 history endpoint is assumed or implemented. */
@FunctionalInterface
public interface EvidenceAdapter {
    RiskInput fetchFrozenEvidence(RiskInput.Payment validatedCurrent, Instant cutoff,
            Set<String> currentAndRetrySubmissionIds, RiskConfiguration frozenConfiguration);
}
