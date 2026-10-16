package com.ofss.risk.persistence;

import com.ofss.risk.engine.RiskConfiguration;
import com.ofss.risk.engine.RiskInput.Payment;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/** Trusted adapter supplies facts, not hashes. A null configuration means reuse only. */
public record AcceptanceRequest(SubmissionIdentity identity, long paymentId, long requesterId,
        String validationReference, String validationRevision, Payment current, Instant cutoff,
        Set<String> currentAndRetrySubmissionIds, String evaluatorVersion,
        RiskConfiguration configuration, String correlationId) {
    public AcceptanceRequest {
        Objects.requireNonNull(identity); Objects.requireNonNull(current);
        PersistenceValues.positive(paymentId); PersistenceValues.positive(requesterId);
        PersistenceValues.id(current.supplierId()); PersistenceValues.id(current.invoiceId());
        PersistenceValues.text(validationReference, 128);
        PersistenceValues.optionalText(validationRevision, 128);
        PersistenceValues.text(evaluatorVersion, 64);
        PersistenceValues.optionalText(correlationId, 128);
        PersistenceValues.timestamp(cutoff); PersistenceValues.timestamp(current.submittedAt());
        if (current.completedAt() != null) PersistenceValues.timestamp(current.completedAt());
        currentAndRetrySubmissionIds = Set.copyOf(currentAndRetrySubmissionIds);
        currentAndRetrySubmissionIds.forEach(s -> PersistenceValues.text(s, Integer.MAX_VALUE));
        if (!currentAndRetrySubmissionIds.contains(current.submissionId()) || !current.validated()
                || !"INR".equals(current.currencyCode()) || current.submittedAt().isAfter(cutoff)
                || (current.completedAt() != null && current.completedAt().isAfter(cutoff)))
            throw new IllegalArgumentException("Invalid validated current facts or cutoff");
    }
}
