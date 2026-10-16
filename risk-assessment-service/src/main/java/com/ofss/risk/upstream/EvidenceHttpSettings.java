package com.ofss.risk.upstream;

import com.ofss.risk.facts.FactsHttpSettings;

/** Per-response and whole-population limits. No silent truncation is allowed. */
public record EvidenceHttpSettings(FactsHttpSettings transport,int pageSize,int maxPages,int maxRecords) {
    public EvidenceHttpSettings {
        if(transport==null||pageSize<1||pageSize>1000||maxPages<1||maxPages>100||maxRecords<1||maxRecords>9000)throw invalid();
    }
    public static IllegalStateException invalid(){return new IllegalStateException("Complete valid external upstream evidence HTTP settings are required");}
    @Override public String toString(){return "Upstream evidence HTTP settings [values withheld]";}
}
