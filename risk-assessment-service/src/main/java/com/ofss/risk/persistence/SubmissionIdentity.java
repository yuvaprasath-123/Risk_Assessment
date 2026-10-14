package com.ofss.risk.persistence;

/** Opaque, case-sensitive pair. Payment ID and transport IDs are not this key. */
public record SubmissionIdentity(String source, String key) {
    public SubmissionIdentity {
        PersistenceValues.text(source, 64);
        PersistenceValues.text(key, 128);
    }
}
