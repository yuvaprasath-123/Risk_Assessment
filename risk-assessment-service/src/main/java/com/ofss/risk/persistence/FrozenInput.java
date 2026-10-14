package com.ofss.risk.persistence;

import java.time.Instant;

/** Manifest hash and assembled input hash are distinct. Lease includes the refreshed parent rowVersion. */
public record FrozenInput(String manifestId,String inputHash,String manifestHash,Instant frozenAt,
                          WorkLease lease,String canonicalInputJson,com.ofss.risk.engine.RiskInput input) {}
