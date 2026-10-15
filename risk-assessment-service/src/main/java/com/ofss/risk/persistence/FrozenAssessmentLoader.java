package com.ofss.risk.persistence;

import com.ofss.risk.engine.*;
import java.util.*;
import static com.ofss.risk.persistence.EvidenceFreezeRequest.*;

/** Same strict graph verifier as freezing; all source data comes from immutable persisted snapshots. */
class FrozenAssessmentLoader {
    record Snapshot(AcceptedAssessment accepted,RiskWorkRepository.Row work,RiskEvidenceRepository.Header header,
                    List<RiskEvidenceRepository.Stored> evidence,RiskConfiguration config,RiskInput input,String evaluator) {
        boolean sameFrozen(Snapshot other) {
            return accepted.assessmentId().equals(other.accepted.assessmentId()) && accepted.identity().equals(other.accepted.identity())
                && accepted.configurationSnapshotId().equals(other.accepted.configurationSnapshotId())
                && accepted.acceptanceJson().equals(other.accepted.acceptanceJson()) && accepted.configurationJson().equals(other.accepted.configurationJson())
                && header.equals(other.header) && evidence.equals(other.evidence);
        }
    }
    private final RiskAcceptanceRepository acceptance; private final RiskWorkRepository work; private final RiskEvidenceRepository evidence;
    FrozenAssessmentLoader(RiskAcceptanceRepository acceptance,RiskWorkRepository work,RiskEvidenceRepository evidence) {
        this.acceptance=acceptance; this.work=work; this.evidence=evidence;
    }
    @SuppressWarnings("unchecked") Snapshot load(FinalizationRequest request) {
        var accepted=acceptance.find(request.identity()).orElseThrow(()->new WorkFailure(WorkFailure.Code.WORK_NOT_FOUND));
        if(!accepted.assessmentId().equals(request.lease().assessmentId())) throw conflict();
        var parent=work.read(PersistenceValues.id(accepted.assessmentId())); var header=evidence.header(parent.id());
        if(header.hash()==null) throw new FinalizationFailure(FinalizationFailure.Code.FROZEN_INPUT_REQUIRED);
        if(!accepted.rowVersion().equals(Long.toString(parent.revision())) || !accepted.configurationSnapshotId().equals(Long.toString(parent.snapshot()))
            || !accepted.workState().equals(parent.state())) throw unavailable();
        if(!request.acceptanceHash().equals(accepted.acceptanceHash()) || !request.configurationHash().equals(accepted.configurationHash())
            || !request.inputHash().equals(header.hash())) throw conflict();
        var config=StoredConfigurationDecoder.read(accepted.configurationJson()); var rows=evidence.children(parent.id());
        var manifests=rows.stream().filter(r->"ENGINE_INPUT".equals(r.key())).toList(); if(manifests.size()!=1) throw unavailable();
        var body=CanonicalAcceptance.read(manifests.getFirst().json()); var document=CanonicalEvidence.document(body.get("inputDocument"));
        if(!document.hash().equals(header.hash())) throw unavailable();
        var input=FrozenInputDecoder.read(document.json()); var envelope=CanonicalAcceptance.read(document.json());
        var sourceMap=(Map<String,Object>)envelope.get("sources"); var sources=new EnumMap<Kind,Source>(Kind.class);
        sourceMap.forEach((key,value)-> { var s=(Map<String,Object>)value; sources.put(Kind.valueOf(key),new Source((String)s.get("service"),(String)s.get("recordReference"),(String)s.get("revision"))); });
        var facts=CanonicalAcceptance.read(accepted.acceptanceJson()); String evaluator=(String)facts.get("evaluatorVersion");
        var original=new AcceptanceRequest(accepted.identity(),PersistenceValues.id((String)facts.get("paymentId")),PersistenceValues.id((String)facts.get("requesterId")),
            (String)facts.get("validationReference"),(String)facts.get("validationRevision"),input.current(),input.cutoff(),input.currentAndRetrySubmissionIds(),evaluator,config,null);
        var frozen=new EvidenceFreezeRequest(request.lease(),original,input,sources);
        EvidenceValidation.matches(frozen,accepted);
        if(!new CanonicalEvidence().input(frozen,accepted).equals(document)) throw unavailable();
        RiskEvidenceService.verifyStored(frozen,accepted,document,header,rows);
        return new Snapshot(accepted,parent,header,List.copyOf(rows),config,input,evaluator);
    }
    static FinalizationFailure unavailable() { return new FinalizationFailure(FinalizationFailure.Code.FINALIZATION_UNAVAILABLE); }
    static FinalizationFailure conflict() { return new FinalizationFailure(FinalizationFailure.Code.FINALIZATION_CONFLICT); }
}
