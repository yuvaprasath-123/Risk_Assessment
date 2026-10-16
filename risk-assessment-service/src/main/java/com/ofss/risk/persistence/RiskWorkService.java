package com.ofss.risk.persistence;

import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit transaction boundary, including lock, authoritative clock, diagnostic, fence and readback. */
public final class RiskWorkService {
    private final RiskWorkRepository repository;
    private final Clock clock;
    private final Duration duration;
    private final Supplier<String> tokens;
    private final TransactionTemplate writes;
    public RiskWorkService(RiskWorkRepository repository, PlatformTransactionManager manager, Clock clock, Duration duration) {
        this(repository,manager,clock,duration,() -> UUID.randomUUID().toString());
    }
    RiskWorkService(RiskWorkRepository repository, PlatformTransactionManager manager, Clock clock,
                    Duration duration, Supplier<String> tokens) {
        this.repository=Objects.requireNonNull(repository); this.clock=Objects.requireNonNull(clock);
        this.duration=Objects.requireNonNull(duration); this.tokens=Objects.requireNonNull(tokens);
        if(duration.isZero() || duration.isNegative()) throw new IllegalArgumentException("Lease duration must be positive");
        writes=new TransactionTemplate(manager);
        writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public WorkLease claim(String assessmentId,String expectedRowVersion) {
        long id=PersistenceValues.id(assessmentId), revision=WorkLease.revision(expectedRowVersion);
        return transaction(() -> {
            var row=repository.lock(id); Instant now=now(row);
            if(row.revision()!=revision) throw failure(WorkFailure.Code.WORK_CONFLICT);
            if(!(row.state().equals("ACCEPTED") || row.state().equals("RETRYABLE_FAILURE")
                || row.state().equals("IN_PROGRESS") && !now.isBefore(row.expiry())))
                throw failure(WorkFailure.Code.WORK_CONFLICT);
            long epoch=next(row.epoch()), nextRevision=next(row.revision());
            if(row.attempt()==Integer.MAX_VALUE) throw failure(WorkFailure.Code.WORK_COUNTER_EXHAUSTED);
            String token=tokens.get(); PersistenceValues.text(token,64);
            if(token.equals(row.token())) throw failure(WorkFailure.Code.WORK_PERSISTENCE_UNAVAILABLE);
            var saved=new RiskWorkRepository.Row(row.id(),row.snapshot(),"IN_PROGRESS",token,epoch,row.attempt()+1,
                nextRevision,expiry(now),row.created(),row.cutoff(),null,null);
            repository.update(row,saved,false); verify(saved); return saved.lease();
        });
    }
    public WorkLease renew(WorkLease lease) {
        Objects.requireNonNull(lease);
        return transaction(() -> {
            var row=repository.lock(PersistenceValues.id(lease.assessmentId())); Instant now=now(row);
            fence(row,lease,now);
            Instant expiry=expiry(now);
            // Do not shorten a lease if the authoritative clock stepped backwards.
            if(expiry.isBefore(row.expiry())) expiry=row.expiry();
            var saved=new RiskWorkRepository.Row(row.id(),row.snapshot(),row.state(),row.token(),row.epoch(),row.attempt(),
                next(row.revision()),expiry,row.created(),row.cutoff(),null,null);
            repository.update(row,saved,true); verify(saved); return saved.lease();
        });
    }
    /** Returns the new parent rowVersion; no scores or band are created. */
    public String fail(WorkLease lease,WorkDiagnostic diagnostic) {
        Objects.requireNonNull(lease); Objects.requireNonNull(diagnostic);
        return transaction(() -> {
            var row=repository.lock(PersistenceValues.id(lease.assessmentId())); Instant now=now(row);
            fence(row,lease,now);
            var saved=new RiskWorkRepository.Row(row.id(),row.snapshot(),diagnostic.state(),null,row.epoch(),row.attempt(),
                next(row.revision()),null,row.created(),row.cutoff(),diagnostic.name(),diagnostic.message());
            repository.diagnostic(row,diagnostic,now);
            // Rechecks Oracle expiry after the diagnostic insert; an expired worker rolls the child back too.
            repository.update(row,saved,true); verify(saved); return Long.toString(saved.revision());
        });
    }
    private void verify(RiskWorkRepository.Row expected) {
        try {
            if(!expected.equals(repository.read(expected.id()))) throw failure(WorkFailure.Code.WORK_PERSISTENCE_UNAVAILABLE);
        } catch(WorkFailure e) { throw failure(WorkFailure.Code.WORK_PERSISTENCE_UNAVAILABLE); }
    }
    private Instant now(RiskWorkRepository.Row row) {
        Instant now=clock.instant(); PersistenceValues.timestamp(now);
        if(now.isBefore(row.created())) throw failure(WorkFailure.Code.WORK_PERSISTENCE_UNAVAILABLE);
        return now;
    }
    private Instant expiry(Instant now) {
        try { var expiry=now.plus(duration); PersistenceValues.timestamp(expiry); return expiry; }
        catch(RuntimeException e) { throw failure(WorkFailure.Code.WORK_COUNTER_EXHAUSTED); }
    }
    static void fence(RiskWorkRepository.Row row,WorkLease lease,Instant now) {
        if(!row.state().equals("IN_PROGRESS") || !Objects.equals(row.token(),lease.token())
            || row.epoch()!=lease.epoch() || row.attempt()!=lease.attempt()
            || row.revision()!=WorkLease.revision(lease.rowVersion()) || !Objects.equals(row.expiry(),lease.expiresAt())
            || !now.isBefore(row.expiry())) throw failure(WorkFailure.Code.LEASE_REJECTED);
    }
    private static long next(long value) {
        if(value==Long.MAX_VALUE) throw failure(WorkFailure.Code.WORK_COUNTER_EXHAUSTED);
        return value+1;
    }
    private <T> T transaction(Supplier<T> operation) {
        try { return writes.execute(status -> operation.get()); }
        catch(WorkFailure e) { throw e; }
        catch(RuntimeException e) { throw failure(WorkFailure.Code.WORK_PERSISTENCE_UNAVAILABLE); }
    }
    private static WorkFailure failure(WorkFailure.Code code) { return new WorkFailure(code); }
}
