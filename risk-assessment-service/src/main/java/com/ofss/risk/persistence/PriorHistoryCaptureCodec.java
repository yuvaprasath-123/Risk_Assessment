package com.ofss.risk.persistence;

import com.ofss.risk.engine.*;
import com.ofss.risk.contract.RuleCode;
import java.time.*;
import java.util.*;
import static com.ofss.risk.persistence.CanonicalEvidence.*;

/** Separate immutable collection record. It is NOT an ENGINE_INPUT manifest or permission to score partial inputs. */
final class PriorHistoryCaptureCodec {
    static final String KEY="LOCAL_PRIOR_POPULATION",FORMAT="risk-prior-capture-v1";
    static boolean isCapture(RiskEvidenceRepository.Stored row){return KEY.equals(row.key());}
    static RiskEvidenceRepository.Stored encode(PriorAssessmentHistory.Request request,PriorAssessmentHistory history,Instant captured){
        var a=request.accepted();var canonical=new CanonicalAcceptance();var config=canonical.configuration(a.configuration());
        var acceptance=canonical.acceptance(a,a.configuration().versionId(),a.configuration().rowVersion(),config.hash());
        var body=document(map("format",FORMAT,"acceptanceHash",acceptance.hash(),"configurationHash",config.hash(),
            "retryIdentities",request.retryIdentities().stream().sorted(Comparator.comparing(SubmissionIdentity::source).thenComparing(SubmissionIdentity::key)).toList(),
            "evidence",history.evidence(),"source",history.source(),"population",history.populationJson()==null?null:CanonicalAcceptance.read(history.populationJson())));
        var e=history.evidence();return new RiskEvidenceRepository.Stored(0,"INPUT",KEY,"PRIOR_ASSESSMENTS",history.source(),e.sourceSnapshotId(),FORMAT,
            HASH_FORMAT,e.cutoff(),e.coveredFrom(),e.coveredFrom()==null?null:e.cutoff(),e.records().size(),e.availability().name(),body.json(),body.hash(),captured);
    }
    static PriorAssessmentHistory decode(RiskEvidenceRepository.Stored row,AcceptedAssessment accepted){
        try{
            var body=CanonicalAcceptance.read(row.json());keys(body,"format","acceptanceHash","configurationHash","retryIdentities","evidence","source","population");
            if(!FORMAT.equals(body.get("format"))||!accepted.acceptanceHash().equals(body.get("acceptanceHash"))||!accepted.configurationHash().equals(body.get("configurationHash"))
                ||!document(body).json().equals(row.json())||!document(body).hash().equals(row.hash()))throw invalid();
            var evidence=FrozenInputDecoder.priorEvidence(object(body.get("evidence")));var sourceMap=object(body.get("source"));keys(sourceMap,"service","recordReference","revision");
            var source=new EvidenceFreezeRequest.Source(text(sourceMap,"service"),(String)sourceMap.get("recordReference"),(String)sourceMap.get("revision"));
            var retry=new HashSet<SubmissionIdentity>();for(Object value:list(body.get("retryIdentities"))){var pair=object(value);keys(pair,"source","key");if(!retry.add(new SubmissionIdentity(text(pair,"source"),text(pair,"key"))))throw invalid();}
            var facts=CanonicalAcceptance.read(accepted.acceptanceJson());var current=object(facts.get("current"));var config=StoredConfigurationDecoder.read(accepted.configurationJson());
            var rule=config.rules().stream().filter(r->r.code()==RuleCode.PREVIOUS_RISK_FLAGS&&r.enabled()).findFirst().orElseThrow();var from=accepted.cutoff().minusSeconds(rule.parameters().windowSeconds());
            if(!evidence.supplierId().equals(current.get("supplierId"))||!evidence.currencyCode().equals(current.get("currencyCode"))||!evidence.cutoff().equals(accepted.cutoff())||!from.equals(evidence.coveredFrom())
                ||!source.service().equals("risk-assessment-service")||!source.recordReference().equals("supplier-completed-history/"+evidence.supplierId()))throw invalid();
            var excluded=new HashSet<>(retry);excluded.add(accepted.identity());var ids=new HashSet<String>();var submissions=new HashSet<String>();String populationJson=null;
            if(evidence.records().size()>1000)throw invalid();
            if(evidence.availability()==RiskInput.Availability.COMPLETE){
                var population=object(body.get("population"));keys(population,"format","supplierId","currency","from","cutoff","currentIdentity","excludedIdentities","excludedAliases","records");
                var proof=document(population);populationJson=proof.json();
                if(!"risk-prior-population-v1".equals(population.get("format"))||!proof.hash().equals(source.revision())||!("risk-prior-v1:"+proof.hash()).equals(evidence.sourceSnapshotId())
                    ||!evidence.supplierId().equals(population.get("supplierId"))||!evidence.currencyCode().equals(population.get("currency"))||!from.toString().equals(population.get("from"))||!accepted.cutoff().toString().equals(population.get("cutoff"))
                    ||!document(accepted.identity()).equals(document(population.get("currentIdentity")))
                    ||!document(excluded.stream().sorted(Comparator.comparing(SubmissionIdentity::source).thenComparing(SubmissionIdentity::key)).toList()).equals(document(population.get("excludedIdentities")))
                    ||!document(new HashSet<>(list(facts.get("currentAndRetrySubmissionIds")))).equals(document(population.get("excludedAliases"))))throw invalid();
                var proofs=list(population.get("records"));if(proofs.size()!=evidence.records().size())throw invalid();
                for(int i=0;i<proofs.size();i++){var p=object(proofs.get(i));keys(p,"identity","originalSubmissionId","record","resultHash");var pair=object(p.get("identity"));keys(pair,"source","key");var identity=new SubmissionIdentity(text(pair,"source"),text(pair,"key"));
                    var record=evidence.records().get(i);
                    String alias=text(p,"originalSubmissionId");PersistenceValues.text(alias,Integer.MAX_VALUE);
                    if(excluded.contains(identity)||identity.source().equals(accepted.identity().source())&&list(facts.get("currentAndRetrySubmissionIds")).contains(alias)
                        ||!record.evidenceId().matches("d2-risk-assessment:[1-9][0-9]{0,18}")||!record.submissionId().equals(PriorAssessmentHistoryRepository.submissionReference(identity))||!document(record).equals(document(p.get("record")))||!text(p,"resultHash").matches("[0-9a-f]{64}"))throw invalid();
                    PersistenceValues.id(record.evidenceId().substring("d2-risk-assessment:".length()));
                }
            }else if(evidence.availability()!=RiskInput.Availability.INCOMPLETE||!evidence.records().isEmpty()||body.get("population")!=null||source.revision()!=null||evidence.sourceSnapshotId()!=null)throw invalid();
            for(var record:evidence.records())if(!record.supplierId().equals(evidence.supplierId())||record.assessedAt().isBefore(from)||!record.assessedAt().isBefore(accepted.cutoff())
                ||!ids.add(record.evidenceId())||!submissions.add(record.submissionId())||record.triggeredFactors().contains(RuleCode.PREVIOUS_RISK_FLAGS))throw invalid();
            PersistenceValues.timestamp(row.captured());if(row.captured().isBefore(accepted.createdAt()))throw invalid();
            var history=new PriorAssessmentHistory(evidence,source,populationJson);
            // Rebuild all duplicated SQL metadata, not just the JSON hash.
            var expected=new RiskEvidenceRepository.Stored(row.id(),"INPUT",KEY,"PRIOR_ASSESSMENTS",source,evidence.sourceSnapshotId(),FORMAT,HASH_FORMAT,
                evidence.cutoff(),from,evidence.cutoff(),evidence.records().size(),evidence.availability().name(),row.json(),row.hash(),row.captured());
            if(!expected.equals(row))throw invalid();return history;
        }catch(RuntimeException e){throw invalid();}
    }
    static Optional<RiskEvidenceRepository.Stored> partial(List<RiskEvidenceRepository.Stored> rows,AcceptedAssessment accepted){
        if(rows.isEmpty())return Optional.empty();if(rows.size()!=1||!isCapture(rows.getFirst()))throw invalid();decode(rows.getFirst(),accepted);return Optional.of(rows.getFirst());
    }
    static RiskEvidenceRepository.Stored find(List<RiskEvidenceRepository.Stored> rows,AcceptedAssessment accepted){
        var matches=rows.stream().filter(PriorHistoryCaptureCodec::isCapture).toList();if(matches.size()>1)throw invalid();if(matches.isEmpty())return null;decode(matches.getFirst(),accepted);return matches.getFirst();
    }
    static void matches(RiskEvidenceRepository.Stored row,AcceptedAssessment accepted,RiskInput.Evidence<RiskInput.PriorAssessment> supplied,EvidenceFreezeRequest.Source source){
        var saved=decode(row,accepted);if(!saved.evidence().equals(supplied)||!saved.source().equals(source))throw invalid();
    }
    private static Map<?,?> object(Object value){if(value instanceof Map<?,?> map)return map;throw invalid();}
    private static List<?> list(Object value){if(value instanceof List<?> l)return l;throw invalid();}
    private static String text(Map<?,?> map,String key){if(map.get(key) instanceof String s)return s;throw invalid();}
    private static void keys(Map<?,?> map,String... keys){if(!map.keySet().equals(Set.of(keys)))throw invalid();}
    static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid immutable local prior population");}
}
