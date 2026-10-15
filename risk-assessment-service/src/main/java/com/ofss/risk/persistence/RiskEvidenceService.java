package com.ofss.risk.persistence;

import com.ofss.risk.engine.RiskInput;
import java.time.*;
import java.util.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import static com.ofss.risk.persistence.EvidenceFreezeRequest.*;

/** Full-bundle freeze only. No partial capture, external calls, scoring or diagnostic writes. */
public final class RiskEvidenceService {
    private final RiskWorkRepository work;
    private final RiskAcceptanceRepository acceptance;
    private final RiskEvidenceRepository evidence;
    private final Clock clock;
    private final TransactionTemplate writes;
    private final CanonicalEvidence canonical=new CanonicalEvidence();
    public RiskEvidenceService(RiskWorkRepository work,RiskAcceptanceRepository acceptance,RiskEvidenceRepository evidence,
                               PlatformTransactionManager manager,Clock clock) {
        this.work=Objects.requireNonNull(work); this.acceptance=Objects.requireNonNull(acceptance);
        this.evidence=Objects.requireNonNull(evidence); this.clock=Objects.requireNonNull(clock);
        writes=new TransactionTemplate(manager); writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public FrozenInput freeze(EvidenceFreezeRequest request) {
        Objects.requireNonNull(request);
        try {
            return writes.execute(status -> {
                var parent=work.lock(PersistenceValues.id(request.lease().assessmentId())); var now=clock.instant(); PersistenceValues.timestamp(now);
                RiskWorkService.fence(parent,request.lease(),now);
                if(now.isBefore(parent.created())) throw unavailable();
                var stored=acceptance.find(request.acceptance().identity()).orElseThrow(EvidenceValidation::mismatch);
                if(!stored.assessmentId().equals(Long.toString(parent.id())) || !stored.rowVersion().equals(Long.toString(parent.revision()))
                    || !stored.configurationSnapshotId().equals(Long.toString(parent.snapshot()))) throw EvidenceValidation.mismatch();
                EvidenceValidation.matches(request,stored);
                CanonicalEvidence.Document input;
                try { input=canonical.input(request,stored); }
                catch(IllegalArgumentException e) { throw EvidenceValidation.mismatch(); }
                var header=evidence.header(parent.id());
                var existing=evidence.children(parent.id());
                if(header.hash()!=null) {
                    if(!header.hash().equals(input.hash())) throw conflict();
                    var expected=verifyStored(request,stored,input,header,existing);
                    evidence.reuse(parent,header); // Final expiry/fence check, even on a read-only logical retry.
                    return result(parent,header,expected,input,request.input());
                }
                if(!existing.isEmpty()) throw unavailable(); // Never overwrite orphan partial capture.
                if(parent.revision()==Long.MAX_VALUE) throw new WorkFailure(WorkFailure.Code.WORK_COUNTER_EXHAUSTED);
                var children=new ArrayList<RiskEvidenceRepository.Stored>();
                for(var child:inputs(request,stored,now)) children.add(evidence.insert(parent.id(),child));
                var manifest=manifest(request,input,children,now); var saved=evidence.insert(parent.id(),manifest);
                var frozen=new RiskEvidenceRepository.Header(input.hash(),saved.id(),now);
                evidence.associate(parent,frozen,parent.revision()+1);
                RiskWorkRepository.Row after;
                try { after=work.read(parent.id()); }
                catch(WorkFailure e) { throw unavailable(); } // A missing locked parent at readback is a persistence failure.
                var expected=new RiskWorkRepository.Row(parent.id(),parent.snapshot(),parent.state(),parent.token(),parent.epoch(),parent.attempt(),
                    parent.revision()+1,parent.expiry(),parent.created(),parent.cutoff(),parent.errorCode(),parent.errorMessage());
                if(!expected.equals(after) || !frozen.equals(evidence.header(parent.id()))) throw unavailable();
                var verified=verifyStored(request,stored,input,frozen,evidence.children(parent.id()));
                return result(after,frozen,verified,input,request.input());
            });
        } catch(FreezeFailure | WorkFailure e) { throw e; }
        catch(RuntimeException e) { throw unavailable(); }
    }
    static RiskEvidenceRepository.Stored verifyStored(EvidenceFreezeRequest request,AcceptedAssessment accepted,
            CanonicalEvidence.Document input,RiskEvidenceRepository.Header header,List<RiskEvidenceRepository.Stored> saved) {
        var byKey=new HashMap<String,RiskEvidenceRepository.Stored>();
        for(var row:saved) if(byKey.put(row.key(),row)!=null) throw unavailable();
        if(saved.size()!=6 || header.frozenAt().isBefore(accepted.createdAt())) throw unavailable();
        var children=new ArrayList<RiskEvidenceRepository.Stored>();
        for(var expected:inputs(request,accepted,header.frozenAt())) {
            var row=byKey.get(expected.key());
            if(row==null || !expected.withId(row.id()).equals(row)) throw unavailable(); children.add(row);
        }
        var expected=manifest(request,input,children,header.frozenAt()); var row=byKey.get("ENGINE_INPUT");
        if(row==null || row.id()!=header.manifest() || !expected.withId(row.id()).equals(row)) throw unavailable(); return row;
    }
    static List<RiskEvidenceRepository.Stored> inputs(EvidenceFreezeRequest request,AcceptedAssessment stored,Instant now) {
        var canonical=new CanonicalEvidence();
        var input=request.input(); var result=new ArrayList<RiskEvidenceRepository.Stored>();
        var currentSource=new Source("risk-assessment-service",stored.assessmentId(),null);
        var current=canonical.evidence(Kind.CURRENT_INPUT,currentSource,CanonicalEvidence.map("current",input.current(),
            "cutoff",input.cutoff(),"exclusions",input.currentAndRetrySubmissionIds(),"acceptanceHash",stored.acceptanceHash()));
        result.add(row("INPUT",Kind.CURRENT_INPUT.name(),Kind.CURRENT_INPUT.name(),currentSource,stored.acceptanceHash(),
            CanonicalEvidence.EVIDENCE_FORMAT,input.cutoff(),null,1,"COMPLETE",current,now));
        for(Kind kind:Kind.values()) {
            if(kind==Kind.CURRENT_INPUT) continue;
            RiskInput.Evidence<?> envelope=switch(kind) {
                case PAYMENT_HISTORY -> input.payments(); case SUPPLIER -> input.supplier();
                case SUPPLIER_TRANSITIONS -> input.supplierTransitions(); case PRIOR_ASSESSMENTS -> input.previousAssessments();
                default -> throw new IllegalArgumentException();
            };
            var document=canonical.evidence(kind,request.sources().get(kind),envelope);
            result.add(row("INPUT",kind.name(),kind.name(),request.sources().get(kind),envelope.sourceSnapshotId(),
                CanonicalEvidence.EVIDENCE_FORMAT,input.cutoff(),envelope.coveredFrom(),envelope.records().size(),envelope.availability().name(),document,now));
        }
        return result;
    }
    static RiskEvidenceRepository.Stored manifest(EvidenceFreezeRequest request,CanonicalEvidence.Document input,
            List<RiskEvidenceRepository.Stored> children,Instant now) {
        return row("MANIFEST","ENGINE_INPUT","INPUT_MANIFEST",new Source("risk-assessment-service",null,null),input.hash(),
            CanonicalEvidence.MANIFEST_FORMAT,request.input().cutoff(),null,children.size(),"COMPLETE",new CanonicalEvidence().manifest(input,children),now);
    }
    private static RiskEvidenceRepository.Stored row(String recordClass,String key,String kind,Source source,String snapshot,
            String format,Instant cutoff,Instant coveredFrom,int count,String availability,CanonicalEvidence.Document document,Instant now) {
        return new RiskEvidenceRepository.Stored(0,recordClass,key,kind,source,snapshot,format,CanonicalEvidence.HASH_FORMAT,
            cutoff,coveredFrom,coveredFrom==null?null:cutoff,count,availability,document.json(),document.hash(),now);
    }
    private static FrozenInput result(RiskWorkRepository.Row parent,RiskEvidenceRepository.Header header,
            RiskEvidenceRepository.Stored manifest,CanonicalEvidence.Document expected,RiskInput supplied) {
        var manifestBody=CanonicalAcceptance.read(manifest.json());
        var input=CanonicalEvidence.document(manifestBody.get("inputDocument"));
        if(!input.equals(expected) || !input.hash().equals(header.hash())) throw unavailable();
        RiskInput decoded=FrozenInputDecoder.read(input.json());
        if(!decoded.equals(supplied)) throw unavailable();
        return new FrozenInput(Long.toString(header.manifest()),header.hash(),manifest.hash(),header.frozenAt(),parent.lease(),input.json(),decoded);
    }
    private static FreezeFailure conflict() { return new FreezeFailure(FreezeFailure.Code.FROZEN_EVIDENCE_CONFLICT); }
    private static FreezeFailure unavailable() { return new FreezeFailure(FreezeFailure.Code.EVIDENCE_PERSISTENCE_UNAVAILABLE); }
}
