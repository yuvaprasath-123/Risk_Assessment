package com.ofss.risk.persistence;

import com.ofss.risk.engine.*;
import java.time.*;
import java.util.*;
import static com.ofss.risk.engine.RiskInput.*;
import static com.ofss.risk.persistence.EvidenceFreezeRequest.*;

/** Structural dependency validation only; no scoring or new rule/policy semantics. */
final class EvidenceValidation {
    static void matches(EvidenceContent request,AcceptedAssessment stored) {
        try { validate(request,stored); } catch(IllegalArgumentException e) { throw mismatch(); }
    }
    private static void validate(EvidenceContent request,AcceptedAssessment stored) {
        var acceptance=request.acceptance(); var config=acceptance.configuration(); var canonical=new CanonicalAcceptance();
        if(!canonical.configuration(config).hash().equals(stored.configurationHash())) throw mismatch();
        // Replace only current/input cutoff/exclusions to prove the assembled input matches every accepted fact.
        var supplied=new AcceptanceRequest(acceptance.identity(),acceptance.paymentId(),acceptance.requesterId(),
            acceptance.validationReference(),acceptance.validationRevision(),request.input().current(),request.input().cutoff(),
            request.input().currentAndRetrySubmissionIds(),acceptance.evaluatorVersion(),config,acceptance.correlationId());
        for(var candidate:List.of(acceptance,supplied)) {
            if(!canonical.acceptance(candidate,stored.configurationVersionId(),stored.configurationRowVersion(),stored.configurationHash())
                .hash().equals(stored.acceptanceHash())) throw mismatch();
        }
        var input=request.input();
        if(!input.current().supplierId().equals(Long.toString(PersistenceValues.id(input.current().supplierId())))
            || !input.current().invoiceId().equals(Long.toString(PersistenceValues.id(input.current().invoiceId())))) throw mismatch();
        var starts=new EnumMap<Kind,Instant>(Kind.class);
        try {
            for(var rule:config.rules()) {
                if(!rule.enabled()) continue;
                Instant start=input.cutoff().minusSeconds(rule.parameters().windowSeconds()); PersistenceValues.timestamp(start);
                switch(rule.code()) {
                    case STRUCTURING,UNUSUAL_FREQUENCY,UNUSUAL_AMOUNT,NEAR_DUPLICATE_INVOICE -> require(starts,Kind.PAYMENT_HISTORY,start);
                    case RECENTLY_REACTIVATED_SUPPLIER -> { require(starts,Kind.SUPPLIER,input.cutoff()); require(starts,Kind.SUPPLIER_TRANSITIONS,start); }
                    case NEW_SUPPLIER -> require(starts,Kind.SUPPLIER,input.cutoff());
                    case PREVIOUS_RISK_FLAGS -> require(starts,Kind.PRIOR_ASSESSMENTS,start);
                }
            }
        } catch(DateTimeException | IllegalArgumentException e) { throw notReady(); }
        check(input.payments(),input,starts.get(Kind.PAYMENT_HISTORY));
        check(input.supplier(),input,starts.get(Kind.SUPPLIER));
        check(input.supplierTransitions(),input,starts.get(Kind.SUPPLIER_TRANSITIONS));
        check(input.previousAssessments(),input,starts.get(Kind.PRIOR_ASSESSMENTS));
        if(starts.containsKey(Kind.SUPPLIER) && input.supplier().records().size()!=1) throw notReady();
        var paymentIds=new HashMap<String,Payment>(); var evidenceIds=new HashMap<String,Object>();
        for(var p:input.payments().records()) {
            if(!p.supplierId().equals(input.current().supplierId()) || !p.currencyCode().equals(input.current().currencyCode())
                || input.currentAndRetrySubmissionIds().contains(p.submissionId()) || p.submittedAt().isAfter(input.cutoff())
                || p.completedAt()!=null && p.completedAt().isAfter(input.cutoff())) throw mismatch();
            unique(paymentIds,p.submissionId(),p); unique(evidenceIds,p.evidenceId(),p);
        }
        evidenceIds.clear(); // Evidence identifiers are scoped to a dependency, not globally unique across services.
        for(var supplier:input.supplier().records()) {
            if(!supplier.supplierId().equals(input.current().supplierId()) || supplier.createdAt().isAfter(input.cutoff())) throw mismatch();
            unique(evidenceIds,supplier.evidenceId(),supplier);
        }
        evidenceIds.clear();
        for(var transition:input.supplierTransitions().records()) {
            if(!transition.supplierId().equals(input.current().supplierId()) || transition.changedAt().isAfter(input.cutoff())) throw mismatch();
            unique(evidenceIds,transition.evidenceId(),transition);
        }
        evidenceIds.clear(); var priorIds=new HashMap<String,PriorAssessment>();
        for(var prior:input.previousAssessments().records()) {
            if(!prior.supplierId().equals(input.current().supplierId()) || prior.assessedAt().isAfter(input.cutoff())
                || input.currentAndRetrySubmissionIds().contains(prior.submissionId())) throw mismatch();
            unique(priorIds,prior.submissionId(),prior); unique(evidenceIds,prior.evidenceId(),prior);
        }
    }
    private static void require(Map<Kind,Instant> starts,Kind kind,Instant start) { starts.merge(kind,start,(a,b)->a.isBefore(b)?a:b); }
    private static void check(Evidence<?> evidence,RiskInput input,Instant required) {
        if(!evidence.supplierId().equals(input.current().supplierId()) || !evidence.currencyCode().equals(input.current().currencyCode())
            || !evidence.cutoff().equals(input.cutoff())) throw mismatch();
        PersistenceValues.optionalText(evidence.sourceSnapshotId(),128);
        if(required!=null && (evidence.availability()!=Availability.COMPLETE || evidence.coveredFrom().isAfter(required))) throw notReady();
    }
    private static <T> void unique(Map<String,T> records,String key,T value) {
        T old=records.putIfAbsent(key,value); if(old!=null && !old.equals(value)) throw mismatch();
    }
    static FreezeFailure mismatch() { return new FreezeFailure(FreezeFailure.Code.EVIDENCE_MISMATCH); }
    static FreezeFailure notReady() { return new FreezeFailure(FreezeFailure.Code.EVIDENCE_NOT_READY); }
}
