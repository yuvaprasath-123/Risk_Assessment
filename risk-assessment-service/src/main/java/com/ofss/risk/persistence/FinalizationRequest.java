package com.ofss.risk.persistence;

import java.util.Objects;

/** Expected hashes are comparison preconditions, never trusted substitutes for locally verified stored data. */
public record FinalizationRequest(SubmissionIdentity identity,WorkLease lease,String acceptanceHash,
                                  String configurationHash,String inputHash) {
    public FinalizationRequest {
        Objects.requireNonNull(identity); Objects.requireNonNull(lease);
        for(String hash:new String[]{acceptanceHash,configurationHash,inputHash})
            if(hash==null || !hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Expected SHA-256 required");
    }
}
