package com.ofss.risk.persistence;

import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Snapshot read, unlocked pure evaluation, then short locked/fenced atomic finalization. */
public final class RiskFinalizationService {
    private final FrozenAssessmentLoader loader;
    private final RiskWorkRepository work;
    private final RiskFinalizationRepository repository;
    private final PinnedRiskEvaluators evaluators;
    private final Clock clock;
    private final TransactionTemplate reads,writes,outside;
    public RiskFinalizationService(RiskAcceptanceRepository acceptance,RiskWorkRepository work,RiskEvidenceRepository evidence,
            RiskFinalizationRepository repository,PinnedRiskEvaluators evaluators,PlatformTransactionManager manager,Clock clock) {
        this(new FrozenAssessmentLoader(acceptance,work,evidence),work,repository,evaluators,manager,clock);
    }
    RiskFinalizationService(FrozenAssessmentLoader loader,RiskWorkRepository work,RiskFinalizationRepository repository,
            PinnedRiskEvaluators evaluators,PlatformTransactionManager manager,Clock clock) {
        this.loader=Objects.requireNonNull(loader); this.work=Objects.requireNonNull(work); this.repository=Objects.requireNonNull(repository);
        this.evaluators=Objects.requireNonNull(evaluators); this.clock=Objects.requireNonNull(clock);
        reads=template(manager,true,TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        writes=template(manager,false,TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        outside=template(manager,false,TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    }
    public CompletedAssessment finalizeAssessment(FinalizationRequest request) {
        Objects.requireNonNull(request);
        try { return outside.execute(status->perform(request)); }
        catch(FinalizationFailure | WorkFailure e) { throw e; }
        catch(RuntimeException e) { throw FrozenAssessmentLoader.unavailable(); }
    }
    private CompletedAssessment perform(FinalizationRequest request) {
        var initial=read(()->{
            var snapshot=loader.load(request);
            if(!snapshot.work().state().equals("COMPLETED")) RiskWorkService.fence(snapshot.work(),request.lease(),clock.instant());
            return snapshot;
        });
        if(initial.work().state().equals("COMPLETED")) return read(()->repository.read(loader.load(request),request));
        // No database transaction or lock is held while running the pinned pure engine.
        var result=evaluators.evaluate(initial.evaluator(),initial.config(),initial.input());
        FinalizedFactors.validate(result,initial);
        Instant evaluated=read(clock::instant); PersistenceValues.timestamp(evaluated);
        return writes.execute(status->{
            var locked=work.lock(initial.work().id());
            var current=loader.load(request);
            if(!locked.equals(current.work()) || !initial.sameFrozen(current)) throw FrozenAssessmentLoader.conflict();
            if(locked.state().equals("COMPLETED")) return repository.read(current,request);
            RiskWorkService.fence(locked,request.lease(),clock.instant());
            if(locked.revision()==Long.MAX_VALUE) throw new WorkFailure(WorkFailure.Code.WORK_COUNTER_EXHAUSTED);
            if(evaluated.isBefore(current.header().frozenAt()) || repository.hasFactors(locked.id())) throw FrozenAssessmentLoader.unavailable();
            String receipt=FinalizedFactors.receipt(request);
            var ordered=result.factors().stream().sorted(Comparator.comparingInt(f->current.config().rules().stream()
                .filter(r->r.code()==f.ruleCode()).findFirst().orElseThrow().evaluationOrder())).toList();
            for(var factor:ordered) repository.insert(current,factor,receipt,evaluated);
            Instant completed=clock.instant(); PersistenceValues.timestamp(completed);
            RiskWorkService.fence(locked,request.lease(),completed);
            if(completed.isBefore(evaluated)) throw FrozenAssessmentLoader.unavailable();
            // SQL independently checks SYSTIMESTAMP after all factor inserts.
            repository.complete(current,result,evaluated,completed);
            var saved=loader.load(request); var stored=repository.read(saved,request);
            if(!initial.sameFrozen(saved) || !stored.result().equals(result) || !stored.evaluatedAt().equals(evaluated)
                || !stored.completedAt().equals(completed)) throw FrozenAssessmentLoader.unavailable();
            RiskWorkService.fence(locked,request.lease(),clock.instant()); // Readback must not extend a worker's authority.
            return stored;
        });
    }
    private <T> T read(Supplier<T> operation) { return reads.execute(status->{repository.beginSnapshot(); return operation.get();}); }
    private static TransactionTemplate template(PlatformTransactionManager manager,boolean readOnly,int propagation) {
        var template=new TransactionTemplate(manager); template.setReadOnly(readOnly); template.setPropagationBehavior(propagation);
        if(propagation!=TransactionDefinition.PROPAGATION_NOT_SUPPORTED) template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        return template;
    }
}
