package com.ofss.risk.persistence;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** TEST ONLY. Independent cleanup contract: never relaxes work/acceptance fixture cleaners. */
final class OracleEvidenceFixtureCleaner {
    static final String LOCK="SELECT * FROM D2_RISK.RISK_ASSESSMENTS WHERE SUBMISSION_SOURCE=? FOR UPDATE";
    static final String MARKER="""
        ASSESSMENT_ID=? AND SUBMISSION_SOURCE=? AND SUBMISSION_KEY=? AND EVALUATOR_VERSION=? AND VALIDATION_REFERENCE=?
        AND WORK_STATE IN ('ACCEPTED','IN_PROGRESS','RETRYABLE_FAILURE','BLOCKED')
        AND TOTAL_RAW_SCORE IS NULL AND FINAL_SCORE IS NULL AND RISK_BAND IS NULL AND FINAL_FACTOR_COUNT IS NULL
        AND EVALUATED_AT IS NULL AND COMPLETED_AT IS NULL
        """;
    static final String DETACH="""
        UPDATE D2_RISK.RISK_ASSESSMENTS SET CONFIGURATION_SNAPSHOT_ID=NULL,
          FROZEN_INPUT_EVIDENCE_ID=NULL, INPUT_HASH=NULL, INPUT_FROZEN_AT=NULL WHERE
        """+MARKER;
    static final String DELETE_EVIDENCE="""
        DELETE FROM D2_RISK.RISK_INPUT_EVIDENCE WHERE ASSESSMENT_ID=? AND
          ((ATTEMPT_NO=0 AND RECORD_CLASS IN ('INPUT','MANIFEST')
            AND EVIDENCE_KEY IN ('CURRENT_INPUT','PAYMENT_HISTORY','SUPPLIER','SUPPLIER_TRANSITIONS','PRIOR_ASSESSMENTS','ENGINE_INPUT')
            AND HASH_FORMAT_VERSION='risk-evidence-canonical-v1' AND EVIDENCE_FORMAT_VERSION IN ('risk-evidence-v1','risk-input-manifest-v1'))
           OR (ATTEMPT_NO>0 AND RECORD_CLASS='ATTEMPT_DIAGNOSTIC' AND EVIDENCE_KEY='RESULT'
            AND HASH_FORMAT_VERSION='risk-canonical-v1' AND EVIDENCE_FORMAT_VERSION='risk-work-diagnostic-v1'))
          AND EXISTS (SELECT 1 FROM D2_RISK.RISK_ASSESSMENTS WHERE
        """+MARKER+")";
    static final String DELETE_SNAPSHOT="DELETE FROM D2_RISK.RISK_CONFIGURATION_SNAPSHOTS WHERE ASSESSMENT_ID=? AND SNAPSHOT_ID=? AND EXISTS (SELECT 1 FROM D2_RISK.RISK_ASSESSMENTS WHERE "+MARKER+")";
    static final String DELETE_PARENT="DELETE FROM D2_RISK.RISK_ASSESSMENTS WHERE CONFIGURATION_SNAPSHOT_ID IS NULL AND FROZEN_INPUT_EVIDENCE_ID IS NULL AND INPUT_HASH IS NULL AND INPUT_FROZEN_AT IS NULL AND "+MARKER;
    record Row(long id,String key,long snapshot,long evidenceCount) {}
    private final JdbcTemplate jdbc; private final RiskEvidenceRepository repository; private final TransactionTemplate tx;
    OracleEvidenceFixtureCleaner(JdbcTemplate jdbc,PlatformTransactionManager manager) {
        this.jdbc=jdbc; repository=new RiskEvidenceRepository(jdbc); tx=new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW); tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    void clean(String namespace) {
        OracleEvidenceFixtures.requireNamespace(namespace);
        tx.executeWithoutResult(status -> {
            var parents=jdbc.query(LOCK,(rs,n)->OracleWorkPersistenceTest.values(rs),namespace);
            var verified=new ArrayList<Row>(); var seen=new HashSet<String>();
            for(var parent:parents) {
                String key=(String)parent.get("SUBMISSION_KEY");
                require(OracleEvidenceFixtures.KEYS.contains(key) && seen.add(key)
                    && OracleEvidenceFixtures.EVALUATOR.equals(parent.get("EVALUATOR_VERSION"))
                    && ("synthetic-freeze-pass/"+key).equals(parent.get("VALIDATION_REFERENCE"))
                    && Set.of("ACCEPTED","IN_PROGRESS","RETRYABLE_FAILURE","BLOCKED").contains(parent.get("WORK_STATE")));
                for(String field:List.of("TOTAL_RAW_SCORE","FINAL_SCORE","RISK_BAND","FINAL_FACTOR_COUNT","EVALUATED_AT","COMPLETED_AT")) require(parent.get(field)==null);
                long id=PersistenceValues.number(parent.get("ASSESSMENT_ID"),true),snapshot=PersistenceValues.number(parent.get("CONFIGURATION_SNAPSHOT_ID"),true);
                require(Objects.equals(0L,jdbc.queryForObject(OracleWorkFixtureCleaner.FACTORS,Long.class,id)));
                var snapshots=jdbc.query(OracleWorkFixtureCleaner.SNAPSHOT,(rs,n)-> {
                    String json=rs.getString("CONFIGURATION_JSON"); var body=CanonicalAcceptance.read(json);
                    require(body.get("configuration") instanceof Map<?,?> config && ("ORACLE_FREEZE_TEST:"+namespace).equals(config.get("contentHash"))
                        && CanonicalAcceptance.hash(json).equals(rs.getString("CONTENT_HASH"))
                        && rs.getString("CONTENT_HASH").equals(parent.get("CONFIGURATION_HASH"))); return true;
                },id,snapshot); require(snapshots.size()==1);
                var header=repository.header(id); var inputs=repository.children(id);
                if(header.hash()==null) require(inputs.isEmpty()); else validateFrozen(parent,header,inputs);
                var diagnostics=jdbc.query("SELECT * FROM D2_RISK.RISK_INPUT_EVIDENCE WHERE ASSESSMENT_ID=? AND ATTEMPT_NO>0",(rs,n)-> {
                    require("ATTEMPT_DIAGNOSTIC".equals(rs.getString("RECORD_CLASS")) && "RESULT".equals(rs.getString("EVIDENCE_KEY"))
                        && "ATTEMPT_RESULT".equals(rs.getString("EVIDENCE_KIND")) && "risk-assessment-service".equals(rs.getString("SOURCE_SERVICE"))
                        && "risk-work-diagnostic-v1".equals(rs.getString("EVIDENCE_FORMAT_VERSION")) && "risk-canonical-v1".equals(rs.getString("HASH_FORMAT_VERSION")));
                    for(String field:List.of("SOURCE_RECORD_REFERENCE","SOURCE_SNAPSHOT_REFERENCE","SOURCE_REVISION",
                        "WINDOW_START_AT","WINDOW_END_AT","RECORD_COUNT","AVAILABILITY")) require(rs.getObject(field)==null);
                    String json=rs.getString("EVIDENCE_JSON"); var body=CanonicalAcceptance.read(json);
                    WorkDiagnostic code=WorkDiagnostic.valueOf((String)body.get("code"));
                    require(body.keySet().equals(Set.of("attempt","code","epoch","state")) && code.state().equals(body.get("state"))
                        && PersistenceValues.number(body.get("attempt"),true)==PersistenceValues.number(rs.getObject("ATTEMPT_NO"),true)
                        && Long.parseLong((String)body.get("epoch"))>0 && CanonicalAcceptance.hash(json).equals(rs.getString("CONTENT_HASH"))); return true;
                },id);
                verified.add(new Row(id,key,snapshot,inputs.size()+diagnostics.size()));
            }
            // No mutation until ALL namespace parents, snapshots, inputs and diagnostics have been verified.
            for(var row:verified) {
                Object[] marker={row.id,namespace,row.key,OracleEvidenceFixtures.EVALUATOR,"synthetic-freeze-pass/"+row.key};
                require(jdbc.update(DETACH,marker)==1);
                require(jdbc.update(DELETE_EVIDENCE,prepend(marker,row.id))==row.evidenceCount);
                require(jdbc.update(DELETE_SNAPSHOT,prepend(marker,row.id,row.snapshot))==1);
                require(jdbc.update(DELETE_PARENT,marker)==1);
            }
        });
    }
    static void validateFrozen(Map<String,Object> parent,RiskEvidenceRepository.Header header,List<RiskEvidenceRepository.Stored> inputs) {
        require(inputs.size()==6); var byKey=new HashMap<String,RiskEvidenceRepository.Stored>();
        for(var row:inputs) {
            require(byKey.put(row.key(),row)==null && CanonicalEvidence.HASH_FORMAT.equals(row.hashFormat()) && row.captured().equals(header.frozenAt())
                && row.cutoff().equals(parent.get("EVIDENCE_AS_OF")) && row.hash().equals(CanonicalAcceptance.hash(row.json())));
        }
        require(byKey.keySet().equals(Set.of("CURRENT_INPUT","PAYMENT_HISTORY","SUPPLIER","SUPPLIER_TRANSITIONS","PRIOR_ASSESSMENTS","ENGINE_INPUT")));
        var manifest=byKey.get("ENGINE_INPUT");
        require(manifest.id()==header.manifest() && "MANIFEST".equals(manifest.recordClass()) && "INPUT_MANIFEST".equals(manifest.kind())
            && CanonicalEvidence.MANIFEST_FORMAT.equals(manifest.format()) && "risk-assessment-service".equals(manifest.source().service()));
        var body=CanonicalAcceptance.read(manifest.json()); var document=CanonicalEvidence.document(body.get("inputDocument"));
        require(header.hash().equals(document.hash()) && header.hash().equals(body.get("inputHash")) && header.hash().equals(manifest.snapshot()));
        FrozenInputDecoder.read(document.json()); var envelope=CanonicalAcceptance.read(document.json());
        require(parent.get("ACCEPTANCE_HASH").equals(envelope.get("acceptanceHash")) && parent.get("CONFIGURATION_HASH").equals(envelope.get("configurationHash"))
            && OracleEvidenceFixtures.EVALUATOR.equals(envelope.get("evaluatorVersion")));
        var ordered=new ArrayList<RiskEvidenceRepository.Stored>();
        for(var kind:EvidenceFreezeRequest.Kind.values()) {
            var row=byKey.get(kind.name()); require("INPUT".equals(row.recordClass()) && kind.name().equals(row.kind()) && CanonicalEvidence.EVIDENCE_FORMAT.equals(row.format())
                && (kind==EvidenceFreezeRequest.Kind.CURRENT_INPUT?"risk-assessment-service":"synthetic-freeze-adapter").equals(row.source().service())); ordered.add(row);
        }
        require(new CanonicalEvidence().manifest(document,ordered).json().equals(manifest.json()));
    }
    private static Object[] prepend(Object[] marker,Object... before) {
        var result=Arrays.copyOf(before,before.length+marker.length); System.arraycopy(marker,0,result,before.length,marker.length); return result;
    }
    private static void require(boolean valid) { if(!valid) throw new IllegalStateException("Freeze cleanup refused: unexpected identity or workflow data"); }
}
