package com.ofss.risk.persistence;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Exact synthetic UUID namespace + fixed keys + hashed fixture markers; validates ALL rows before any delete. */
final class OraclePriorCaptureCleaner {
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;
    OraclePriorCaptureCleaner(JdbcTemplate jdbc,PlatformTransactionManager manager){this.jdbc=jdbc;tx=new TransactionTemplate(manager);}
    void clean(String namespace){
        OraclePriorCaptureFixtures.validate(namespace);
        tx.executeWithoutResult(status->{
            var locked=jdbc.queryForList("SELECT ASSESSMENT_ID,SUBMISSION_KEY,CONFIGURATION_SNAPSHOT_ID FROM D2_RISK.RISK_ASSESSMENTS WHERE SUBMISSION_SOURCE=? FOR UPDATE",namespace);
            var acceptance=new RiskAcceptanceRepository(jdbc);var work=new RiskWorkRepository(jdbc);var evidence=new RiskEvidenceRepository(jdbc);var results=new RiskFinalizationRepository(jdbc);
            for(var parent:locked){String key=(String)parent.get("SUBMISSION_KEY");require(OraclePriorCaptureFixtures.KEYS.contains(key));
                var a=acceptance.find(new SubmissionIdentity(namespace,key)).orElseThrow(OraclePriorCaptureCleaner::refused);var facts=CanonicalAcceptance.read(a.acceptanceJson());
                require(a.assessmentId().equals(Long.toString(PersistenceValues.number(parent.get("ASSESSMENT_ID"),true)))&&("synthetic-prior-pass/"+key).equals(facts.get("validationReference"))&&PinnedRiskEvaluators.CURRENT.equals(facts.get("evaluatorVersion")));
                var config=StoredConfigurationDecoder.read(a.configurationJson());require(config.contentHash().equals("ORACLE_PRIOR_TEST:"+namespace));long id=PersistenceValues.id(a.assessmentId());var header=evidence.header(id);var children=evidence.children(id);
                if(header.hash()==null){PriorHistoryCaptureCodec.partial(children,a);require(jdbc.queryForObject("SELECT COUNT(*) FROM D2_RISK.RISK_ASSESSMENT_FACTORS WHERE ASSESSMENT_ID=?",Long.class,id)==0L);}
                else {var frozen=new FrozenAssessmentLoader(acceptance,work,evidence).loadStored(a);if(a.workState().equals("COMPLETED"))results.readPersisted(frozen);
                    else require(jdbc.queryForObject("SELECT COUNT(*) FROM D2_RISK.RISK_ASSESSMENT_FACTORS WHERE ASSESSMENT_ID=?",Long.class,id)==0L);}
                jdbc.query("SELECT * FROM D2_RISK.RISK_INPUT_EVIDENCE WHERE ASSESSMENT_ID=? AND ATTEMPT_NO>0",(rs,n)->{
                    String json=rs.getString("EVIDENCE_JSON");var body=CanonicalAcceptance.read(json);require("ATTEMPT_DIAGNOSTIC".equals(rs.getString("RECORD_CLASS"))&&"RESULT".equals(rs.getString("EVIDENCE_KEY"))&&"risk-work-diagnostic-v1".equals(rs.getString("EVIDENCE_FORMAT_VERSION"))&&"risk-canonical-v1".equals(rs.getString("HASH_FORMAT_VERSION"))&&CanonicalAcceptance.hash(json).equals(rs.getString("CONTENT_HASH"))&&body.keySet().equals(Set.of("attempt","code","epoch","state")));
                    require("risk-assessment-service".equals(rs.getString("SOURCE_SERVICE"))&&"ATTEMPT_RESULT".equals(rs.getString("EVIDENCE_KIND"))
                        &&WorkDiagnostic.valueOf((String)body.get("code")).state().equals(body.get("state"))&&PersistenceValues.number(body.get("attempt"),true)==PersistenceValues.number(rs.getObject("ATTEMPT_NO"),true)&&Long.parseLong((String)body.get("epoch"))>0);
                    for(String field:List.of("SOURCE_RECORD_REFERENCE","SOURCE_SNAPSHOT_REFERENCE","SOURCE_REVISION","WINDOW_START_AT","WINDOW_END_AT","RECORD_COUNT","AVAILABILITY"))require(rs.getObject(field)==null);
                    return true;
                },id);
            }
            for(var parent:locked){long id=PersistenceValues.number(parent.get("ASSESSMENT_ID"),true);String key=(String)parent.get("SUBMISSION_KEY");
                Object[] marker={id,namespace,key,"synthetic-prior-pass/"+key};String guard="ASSESSMENT_ID=? AND SUBMISSION_SOURCE=? AND SUBMISSION_KEY=? AND VALIDATION_REFERENCE=?";
                jdbc.update("DELETE FROM D2_RISK.RISK_ASSESSMENT_FACTORS WHERE ASSESSMENT_ID=? AND EXISTS (SELECT 1 FROM D2_RISK.RISK_ASSESSMENTS WHERE "+guard+")",id,id,namespace,key,"synthetic-prior-pass/"+key);
                require(jdbc.update("UPDATE D2_RISK.RISK_ASSESSMENTS SET WORK_STATE='ACCEPTED',TOTAL_RAW_SCORE=NULL,FINAL_SCORE=NULL,RISK_BAND=NULL,FINAL_FACTOR_COUNT=NULL,EVALUATED_AT=NULL,COMPLETED_AT=NULL,LEASE_TOKEN=NULL,LEASE_EXPIRES_AT=NULL,LAST_ERROR_CODE=NULL,LAST_ERROR_MESSAGE=NULL,CONFIGURATION_SNAPSHOT_ID=NULL,FROZEN_INPUT_EVIDENCE_ID=NULL,INPUT_HASH=NULL,INPUT_FROZEN_AT=NULL WHERE "+guard,marker)==1);
                jdbc.update("DELETE FROM D2_RISK.RISK_INPUT_EVIDENCE WHERE ASSESSMENT_ID=? AND EXISTS (SELECT 1 FROM D2_RISK.RISK_ASSESSMENTS WHERE "+guard+")",id,id,namespace,key,"synthetic-prior-pass/"+key);
                require(jdbc.update("DELETE FROM D2_RISK.RISK_CONFIGURATION_SNAPSHOTS WHERE ASSESSMENT_ID=? AND SNAPSHOT_ID=?",id,PersistenceValues.number(parent.get("CONFIGURATION_SNAPSHOT_ID"),true))==1);
                require(jdbc.update("DELETE FROM D2_RISK.RISK_ASSESSMENTS WHERE "+guard,marker)==1);
            }
            require(jdbc.queryForObject("SELECT COUNT(*) FROM D2_RISK.RISK_ASSESSMENTS WHERE SUBMISSION_SOURCE=?",Long.class,namespace)==0L);
        });
    }
    private static void require(boolean value){if(!value)throw refused();}
    private static IllegalStateException refused(){return new IllegalStateException("Prior capture cleanup refused: unexpected synthetic fixture data");}
}
