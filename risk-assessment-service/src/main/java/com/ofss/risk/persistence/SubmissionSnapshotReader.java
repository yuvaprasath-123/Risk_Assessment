package com.ofss.risk.persistence;

import com.ofss.risk.engine.RiskInput;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Orchestration bridge to existing verified snapshots; does not mutate persistence contracts. */
public class SubmissionSnapshotReader {
    private final RiskAcceptanceRepository acceptance;private final RiskEvidenceRepository evidence;
    private final FrozenAssessmentLoader loader;private final TransactionTemplate reads;
    public SubmissionSnapshotReader(RiskAcceptanceRepository acceptance,RiskWorkRepository work,RiskEvidenceRepository evidence,
            PlatformTransactionManager manager){
        this.acceptance=acceptance;this.evidence=evidence;loader=new FrozenAssessmentLoader(acceptance,work,evidence);
        reads=new TransactionTemplate(manager);reads.setReadOnly(true);reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    }
    @SuppressWarnings("unchecked") public AcceptanceRequest restore(AcceptedAssessment stored) {
        try {
            var m=CanonicalAcceptance.read(stored.acceptanceJson());var p=(Map<String,Object>)m.get("current");
            var payment=new RiskInput.Payment((String)p.get("evidenceId"),(String)p.get("submissionId"),(String)p.get("invoiceId"),(String)p.get("invoiceNumber"),
                (String)p.get("supplierId"),(String)p.get("currencyCode"),new BigDecimal((String)p.get("amount")),Instant.parse((String)p.get("submittedAt")),
                p.get("completedAt")==null?null:Instant.parse((String)p.get("completedAt")),RiskInput.PaymentStatus.valueOf((String)p.get("statusAsOfCutoff")),(Boolean)p.get("validated"));
            var request=new AcceptanceRequest(stored.identity(),PersistenceValues.id((String)m.get("paymentId")),PersistenceValues.id((String)m.get("requesterId")),
                (String)m.get("validationReference"),(String)m.get("validationRevision"),payment,Instant.parse((String)m.get("cutoff")),
                Set.copyOf((List<String>)m.get("currentAndRetrySubmissionIds")),(String)m.get("evaluatorVersion"),StoredConfigurationDecoder.read(stored.configurationJson()),null);
            var canonical=new CanonicalAcceptance();
            if(!canonical.configuration(request.configuration()).hash().equals(stored.configurationHash())
                || !canonical.acceptance(request,stored.configurationVersionId(),stored.configurationRowVersion(),stored.configurationHash()).equals(new CanonicalAcceptance.Document(stored.acceptanceJson(),stored.acceptanceHash())))throw AcceptanceFailure.unavailable();
            return request;
        }catch(RuntimeException e){throw AcceptanceFailure.unavailable();}
    }
    /** Fresh coherent read; validates every frozen child if present, never returns evidence publicly. */
    public String frozenHash(SubmissionIdentity identity) {
        try{return reads.execute(status->{
            var stored=acceptance.find(identity).orElseThrow(AcceptanceFailure::unavailable);
            var header=evidence.header(PersistenceValues.id(stored.assessmentId()));
            if(header.hash()==null){if(!evidence.children(PersistenceValues.id(stored.assessmentId())).isEmpty())throw AcceptanceFailure.unavailable();return null;}
            return loader.loadStored(stored).header().hash();
        });}catch(RuntimeException e){throw AcceptanceFailure.unavailable();}
    }
}
