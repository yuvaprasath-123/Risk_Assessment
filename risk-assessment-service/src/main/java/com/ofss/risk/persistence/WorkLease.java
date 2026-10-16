package com.ofss.risk.persistence;

import java.time.Instant;

/** An opaque capability: callers must use the refreshed rowVersion after each renewal. */
public record WorkLease(String assessmentId, String token, long epoch, int attempt,
                        String rowVersion, Instant expiresAt) {
    public WorkLease {
        PersistenceValues.id(assessmentId);
        PersistenceValues.text(token, 64);
        if (epoch < 1 || attempt < 1) throw new IllegalArgumentException("Invalid lease counters");
        revision(rowVersion); PersistenceValues.timestamp(expiresAt);
    }
    static long revision(String value) {
        if (value == null || !value.matches("[0-9]+")) throw new IllegalArgumentException("Invalid rowVersion");
        try { return Long.parseLong(value); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid rowVersion"); }
    }
    // Never accidentally log the capability through record.toString().
    @Override public String toString() { return "WorkLease[assessmentId=" + assessmentId + ", epoch=" + epoch + ", attempt=" + attempt + "]"; }
}
