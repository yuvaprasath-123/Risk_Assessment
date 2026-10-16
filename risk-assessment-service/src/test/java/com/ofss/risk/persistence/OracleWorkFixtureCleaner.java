package com.ofss.risk.persistence;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** TEST ONLY. Lock/verify ALL namespace fixtures before deleting any; refuse workflow data. */
final class OracleWorkFixtureCleaner {
    static final String LOCK="""
        SELECT ASSESSMENT_ID, SUBMISSION_KEY, WORK_STATE, EVALUATOR_VERSION, VALIDATION_REFERENCE,
               CONFIGURATION_SNAPSHOT_ID, FROZEN_INPUT_EVIDENCE_ID, INPUT_HASH, INPUT_FROZEN_AT,
               TOTAL_RAW_SCORE, FINAL_SCORE, RISK_BAND, FINAL_FACTOR_COUNT, EVALUATED_AT, COMPLETED_AT
        FROM D2_RISK.RISK_ASSESSMENTS WHERE SUBMISSION_SOURCE=? FOR UPDATE
        """;
    static final String SNAPSHOT="""
        SELECT CONFIGURATION_JSON, CONTENT_HASH FROM D2_RISK.RISK_CONFIGURATION_SNAPSHOTS
        WHERE ASSESSMENT_ID=? AND SNAPSHOT_ID=? AND SNAPSHOT_FORMAT_VERSION='risk-engine-v1'
          AND HASH_FORMAT_VERSION='risk-canonical-v1'
        """;
    static final String EVIDENCE="SELECT * FROM D2_RISK.RISK_INPUT_EVIDENCE WHERE ASSESSMENT_ID=?";
    static final String FACTORS="SELECT COUNT(*) FROM D2_RISK.RISK_ASSESSMENT_FACTORS WHERE ASSESSMENT_ID=?";
    static final String MARKER="""
        ASSESSMENT_ID=? AND SUBMISSION_SOURCE=? AND SUBMISSION_KEY=?
        AND EVALUATOR_VERSION=? AND VALIDATION_REFERENCE=?
        AND WORK_STATE IN ('ACCEPTED','IN_PROGRESS','RETRYABLE_FAILURE','BLOCKED')
        AND FROZEN_INPUT_EVIDENCE_ID IS NULL AND INPUT_HASH IS NULL AND INPUT_FROZEN_AT IS NULL
        AND TOTAL_RAW_SCORE IS NULL AND FINAL_SCORE IS NULL AND RISK_BAND IS NULL
        AND FINAL_FACTOR_COUNT IS NULL AND EVALUATED_AT IS NULL AND COMPLETED_AT IS NULL
        """;
    static final String DETACH="UPDATE D2_RISK.RISK_ASSESSMENTS SET CONFIGURATION_SNAPSHOT_ID=NULL WHERE "+MARKER;
    static final String DELETE_PARENT="DELETE FROM D2_RISK.RISK_ASSESSMENTS WHERE CONFIGURATION_SNAPSHOT_ID IS NULL AND "+MARKER;
    static final String DELETE_EVIDENCE="""
        DELETE FROM D2_RISK.RISK_INPUT_EVIDENCE E WHERE E.ASSESSMENT_ID=?
          AND E.RECORD_CLASS='ATTEMPT_DIAGNOSTIC' AND E.EVIDENCE_KEY='RESULT' AND E.EVIDENCE_KIND='ATTEMPT_RESULT'
          AND E.SOURCE_SERVICE='risk-assessment-service' AND E.EVIDENCE_FORMAT_VERSION='risk-work-diagnostic-v1'
          AND E.HASH_FORMAT_VERSION='risk-canonical-v1'
          AND EXISTS (SELECT 1 FROM D2_RISK.RISK_ASSESSMENTS WHERE
        """+MARKER+")";
    static final String DELETE_SNAPSHOT="""
        DELETE FROM D2_RISK.RISK_CONFIGURATION_SNAPSHOTS S WHERE S.ASSESSMENT_ID=? AND S.SNAPSHOT_ID=?
          AND EXISTS (SELECT 1 FROM D2_RISK.RISK_ASSESSMENTS WHERE
        """+MARKER+")";
    record Row(long id,String key,String state,String evaluator,String validation,long snapshot,boolean untouched) {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    OracleWorkFixtureCleaner(JdbcTemplate jdbc,PlatformTransactionManager manager) {
        this.jdbc=jdbc; tx=new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    void clean(String namespace) {
        OracleWorkFixtures.requireNamespace(namespace);
        tx.executeWithoutResult(status -> {
            var rows=jdbc.query(LOCK,(rs,n) -> {
                boolean untouched=true;
                for(String field:List.of("FROZEN_INPUT_EVIDENCE_ID","INPUT_HASH","INPUT_FROZEN_AT","TOTAL_RAW_SCORE",
                    "FINAL_SCORE","RISK_BAND","FINAL_FACTOR_COUNT","EVALUATED_AT","COMPLETED_AT"))
                    untouched &= rs.getObject(field)==null;
                return new Row(PersistenceValues.number(rs.getObject("ASSESSMENT_ID"),true),rs.getString("SUBMISSION_KEY"),
                    rs.getString("WORK_STATE"),rs.getString("EVALUATOR_VERSION"),rs.getString("VALIDATION_REFERENCE"),
                    PersistenceValues.number(rs.getObject("CONFIGURATION_SNAPSHOT_ID"),true),untouched);
            },namespace);
            var counts=new HashMap<Long,Integer>(); var keys=new HashSet<String>();
            for(var row:rows) {
                require(OracleWorkFixtures.KEYS.contains(row.key) && keys.add(row.key) && row.untouched
                    && Set.of("ACCEPTED","IN_PROGRESS","RETRYABLE_FAILURE","BLOCKED").contains(row.state)
                    && OracleWorkFixtures.EVALUATOR.equals(row.evaluator) && ("synthetic-work-pass/"+row.key).equals(row.validation));
                require(Objects.equals(0L,jdbc.queryForObject(FACTORS,Long.class,row.id)));
                var snapshots=jdbc.query(SNAPSHOT,(rs,n) -> {
                    String json=rs.getString("CONFIGURATION_JSON");
                    var body=CanonicalAcceptance.read(json);
                    require(body.get("configuration") instanceof Map<?,?> config
                        && ("ORACLE_WORK_TEST:"+namespace).equals(config.get("contentHash"))
                        && CanonicalAcceptance.hash(json).equals(rs.getString("CONTENT_HASH")));
                    return true;
                },row.id,row.snapshot);
                require(snapshots.size()==1);
                var diagnostics=jdbc.query(EVIDENCE,(rs,n) -> {
                    require("ATTEMPT_DIAGNOSTIC".equals(rs.getString("RECORD_CLASS"))
                        && "RESULT".equals(rs.getString("EVIDENCE_KEY")) && "ATTEMPT_RESULT".equals(rs.getString("EVIDENCE_KIND"))
                        && "risk-assessment-service".equals(rs.getString("SOURCE_SERVICE"))
                        && "risk-work-diagnostic-v1".equals(rs.getString("EVIDENCE_FORMAT_VERSION"))
                        && "risk-canonical-v1".equals(rs.getString("HASH_FORMAT_VERSION")));
                    for(String field:List.of("SOURCE_RECORD_REFERENCE","SOURCE_SNAPSHOT_REFERENCE","SOURCE_REVISION",
                        "WINDOW_START_AT","WINDOW_END_AT","RECORD_COUNT","AVAILABILITY")) require(rs.getObject(field)==null);
                    String json=rs.getString("EVIDENCE_JSON"); var body=CanonicalAcceptance.read(json);
                    WorkDiagnostic code;
                    try { code=WorkDiagnostic.valueOf((String)body.get("code")); } catch(RuntimeException e) { throw refused(); }
                    require(body.keySet().equals(Set.of("attempt","code","epoch","state")) && code.state().equals(body.get("state"))
                        && PersistenceValues.number(body.get("attempt"),true)==PersistenceValues.number(rs.getObject("ATTEMPT_NO"),true)
                        && Long.parseLong((String)body.get("epoch"))>0 && CanonicalAcceptance.hash(json).equals(rs.getString("CONTENT_HASH")));
                    return true;
                },row.id);
                counts.put(row.id,diagnostics.size());
            }
            for(var row:rows) {
                Object[] marker={row.id,namespace,row.key,row.evaluator,row.validation};
                require(jdbc.update(DETACH,marker)==1);
                require(jdbc.update(DELETE_EVIDENCE,prepend(marker,row.id))==counts.get(row.id));
                require(jdbc.update(DELETE_SNAPSHOT,prepend(marker,row.id,row.snapshot))==1);
                require(jdbc.update(DELETE_PARENT,marker)==1);
            }
        });
    }
    private static Object[] prepend(Object[] marker,Object... before) {
        var result=Arrays.copyOf(before,before.length+marker.length); System.arraycopy(marker,0,result,before.length,marker.length); return result;
    }
    private static void require(boolean valid) { if(!valid) throw refused(); }
    private static IllegalStateException refused() { return new IllegalStateException("Work fixture cleanup refused: unexpected identity or workflow data"); }
}
