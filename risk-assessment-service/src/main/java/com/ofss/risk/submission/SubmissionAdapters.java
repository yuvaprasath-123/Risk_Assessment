package com.ofss.risk.submission;

import com.ofss.risk.engine.*;
import com.ofss.risk.persistence.*;
import java.time.Instant;
import java.util.*;

/** No endpoint assumptions. Implementations must enforce remote timeouts and immutable reference semantics. */
public final class SubmissionAdapters {
    private SubmissionAdapters() {}
    public record Deadline(long expiresAtNanos) {
        public long remainingNanos(){return Math.max(0,expiresAtNanos-System.nanoTime());}
    }
    /** Must verify PASS, identity/payment ownership and immutable validation revision against authoritative data. */
    public interface Facts { VerifiedFacts verify(SubmissionCommand command,Instant cutoff,Deadline deadline); }
    /** Must reproduce original cutoff supplier-wide across requesters, including completeness/exclusions/source revisions. */
    public interface Evidence { Bundle retrieve(AcceptanceRequest accepted,Deadline deadline); }
    /** Trusted integration seam. Supplies ONLY three upstream dependencies; D2 owns local history capture/replay.
     * The legacy optional expectedPriorPopulationHash is ignored; it cannot authorize or replace D2 evidence. */
    public interface UpstreamEvidence { UpstreamBundle retrieve(AcceptanceRequest accepted,Deadline deadline); }
    public record UpstreamBundle(RiskInput.Evidence<RiskInput.Payment> payments,RiskInput.Evidence<RiskInput.Supplier> supplier,
            RiskInput.Evidence<RiskInput.StatusTransition> transitions,Map<EvidenceFreezeRequest.Kind,EvidenceFreezeRequest.Source> sources,
            Set<SubmissionIdentity> retryIdentities,String expectedPriorPopulationHash) {
        public UpstreamBundle(RiskInput.Evidence<RiskInput.Payment> payments,RiskInput.Evidence<RiskInput.Supplier> supplier,
                RiskInput.Evidence<RiskInput.StatusTransition> transitions,Map<EvidenceFreezeRequest.Kind,EvidenceFreezeRequest.Source> sources,Set<SubmissionIdentity> retryIdentities){
            this(payments,supplier,transitions,sources,retryIdentities,null);
        }
        public UpstreamBundle {
            Objects.requireNonNull(payments);Objects.requireNonNull(supplier);Objects.requireNonNull(transitions);
            sources=Map.copyOf(sources);retryIdentities=Set.copyOf(retryIdentities);
            if(!sources.keySet().equals(Set.of(EvidenceFreezeRequest.Kind.PAYMENT_HISTORY,EvidenceFreezeRequest.Kind.SUPPLIER,EvidenceFreezeRequest.Kind.SUPPLIER_TRANSITIONS)))
                throw new IllegalArgumentException("Exactly three upstream source contracts required");
            if(expectedPriorPopulationHash!=null&&!expectedPriorPopulationHash.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Invalid prior population receipt");
        }
    }
    /** Must fetch a verified real published active/pinned full snapshot; no DRAFT or demo fallback. */
    public interface PublishedConfiguration { PublishedSnapshot resolve(String explicitVersionId,Deadline deadline); }
    public record VerifiedFacts(SubmissionIdentity identity,long paymentId,long requesterId,String validationReference,
            String validationRevision,RiskInput.Payment current,Set<String> currentAndRetrySubmissionIds) {
        public VerifiedFacts { Objects.requireNonNull(identity);Objects.requireNonNull(current);currentAndRetrySubmissionIds=Set.copyOf(currentAndRetrySubmissionIds); }
        public AcceptanceRequest acceptance(Instant cutoff,RiskConfiguration config) {
            return new AcceptanceRequest(identity,paymentId,requesterId,validationReference,validationRevision,current,cutoff,
                currentAndRetrySubmissionIds,PinnedRiskEvaluators.CURRENT,config,null);
        }
    }
    public record Bundle(RiskInput input,Map<EvidenceFreezeRequest.Kind,EvidenceFreezeRequest.Source> sources) {
        public Bundle { Objects.requireNonNull(input);sources=Map.copyOf(sources); }
    }
    /** Canonical configuration-content-v2 JSON from the existing manager/internal read projection. */
    public record PublishedSnapshot(String versionId,String rowVersion,String publicationState,String hashFormat,
            String contentHash,String canonicalConfiguration) {}
    public static SubmissionFailure unavailable(){return new SubmissionFailure(SubmissionFailure.Code.ADAPTER_UNAVAILABLE);}
}
