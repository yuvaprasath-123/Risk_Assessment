package com.ofss.risk.persistence;

import java.time.Instant;

/** Immutable lookup result; ACCEPTED is not a scored/completed or low-risk result. */
public record AcceptedAssessment(String assessmentId, SubmissionIdentity identity,
        String configurationSnapshotId, String configurationVersionId, String configurationRowVersion,
        String rowVersion, String workState, Instant cutoff, Instant createdAt,
        String acceptanceHash, String configurationHash, String acceptanceJson, String configurationJson) {}
