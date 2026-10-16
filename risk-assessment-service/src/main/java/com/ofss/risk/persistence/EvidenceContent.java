package com.ofss.risk.persistence;

import com.ofss.risk.engine.RiskInput;
import java.util.Map;

/** Immutable verification data, independent of the worker's write capability. */
interface EvidenceContent {
    AcceptanceRequest acceptance();
    RiskInput input();
    Map<EvidenceFreezeRequest.Kind,EvidenceFreezeRequest.Source> sources();
    record Stored(AcceptanceRequest acceptance,RiskInput input,
                  Map<EvidenceFreezeRequest.Kind,EvidenceFreezeRequest.Source> sources) implements EvidenceContent {
        public Stored { sources=Map.copyOf(sources); }
    }
}
