package com.ofss.risk.persistence;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit templates exercise the real transaction path even without a Spring proxy. */
public final class RiskAcceptanceService {
    private final RiskAcceptanceRepository repository;
    private final CanonicalAcceptance canonical;
    private final Clock clock;
    private final TransactionTemplate writes, reads;
    public RiskAcceptanceService(RiskAcceptanceRepository repository, CanonicalAcceptance canonical,
            PlatformTransactionManager transactions, Clock clock) {
        this.repository = repository; this.canonical = canonical; this.clock = clock;
        writes = template(transactions, false); reads = template(transactions, true);
    }
    private static TransactionTemplate template(PlatformTransactionManager manager, boolean readOnly) {
        var tx = new TransactionTemplate(manager);
        // Own the commit boundary; a duplicate race is resolved only AFTER the losing transaction rolls back.
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        tx.setReadOnly(readOnly); return tx;
    }
    public Optional<AcceptedAssessment> find(SubmissionIdentity identity) {
        Objects.requireNonNull(identity);
        try { return reads.execute(status -> repository.find(identity)); }
        catch (RuntimeException e) { throw AcceptanceFailure.unavailable(); }
    }
    public AcceptedAssessment accept(AcceptanceRequest request) {
        Objects.requireNonNull(request);
        // Validate/canonicalize caller-supplied configuration before any SQL. Never accept a supplied local hash.
        var configuration = request.configuration() == null ? null : canonical.configuration(request.configuration());
        try {
            try {
                return writes.execute(status -> {
                    var existing = repository.find(request.identity());
                    if (existing.isPresent()) return matching(request, configuration, existing.get());
                    if (configuration == null) throw AcceptanceFailure.required();
                    var now = clock.instant(); PersistenceValues.timestamp(now);
                    if (request.cutoff().isAfter(now)) throw new IllegalArgumentException("Cutoff is in the future");
                    var acceptance = canonical.acceptance(request,
                            Long.toString(PersistenceValues.id(request.configuration().versionId())),
                            Long.toString(Long.parseLong(request.configuration().rowVersion())), configuration.hash());
                    return repository.insert(request, acceptance, configuration, now);
                });
            } catch (SubmissionCollision e) {
                // Oracle uniqueness waits for the winning transaction. A new transaction must see its complete snapshot.
                return reads.execute(status -> matching(request, configuration,
                        repository.find(request.identity()).orElseThrow(AcceptanceFailure::unavailable)));
            }
        } catch (AcceptanceFailure e) { throw e; }
        catch (RuntimeException e) { throw AcceptanceFailure.unavailable(); }
    }
    private AcceptedAssessment matching(AcceptanceRequest request, CanonicalAcceptance.Document config, AcceptedAssessment stored) {
        if (config != null && !config.hash().equals(stored.configurationHash())) throw AcceptanceFailure.conflict();
        var expected = canonical.acceptance(request, stored.configurationVersionId(), stored.configurationRowVersion(), stored.configurationHash());
        if (!expected.hash().equals(stored.acceptanceHash()) || !expected.json().equals(stored.acceptanceJson()))
            throw AcceptanceFailure.conflict();
        return stored;
    }
}
