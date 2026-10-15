package com.ofss.risk.persistence;

import com.ofss.risk.submission.PublishedConfigurationMapping;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Test-only exact-source cleanup. Verify every known graph before any deletion; refuse unknown data. */
final class OracleSubmissionFixtureCleaner {
    static final String LOCK="SELECT * FROM D2_RISK.RISK_ASSESSMENTS WHERE SUBMISSION_SOURCE=? FOR UPDATE";
    static final String MARKER="""
        ASSESSMENT_ID=? AND SUBMISSION_SOURCE=? AND SUBMISSION_KEY=? AND VALIDATION_REFERENCE=?
        AND VALIDATION_REVISION='contract-v1' AND EVALUATOR_VERSION='risk-scoring-v1' AND CORRELATION_ID IS NULL
        AND ACCEPTANCE_HASH=? AND CONFIGURATION_HASH=? AND ROW_VERSION=?
        """;
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;
    record Verified(long id,long snapshot,int factors,int evidence,Object[] marker) {}
    OracleSubmissionFixtureCleaner(JdbcTemplate jdbc,PlatformTransactionManager manager){this.jdbc=jdbc;tx=new TransactionTemplate(manager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);}
    void clean(String namespace){
        OracleSubmissionFixtures.requireNamespace(namespace);var fixture=new OracleSubmissionFixtures(namespace);
        tx.executeWithoutResult(status->{
            var parents=jdbc.query(LOCK,(rs,n)->OracleWorkPersistenceTest.values(rs),namespace);var verified=new ArrayList<Verified>();var seen=new HashSet<String>();
            var acceptance=new RiskAcceptanceRepository(jdbc);var work=new RiskWorkRepository(jdbc);var evidence=new RiskEvidenceRepository(jdbc);
            for(var parent:parents){
                String key=(String)parent.get("SUBMISSION_KEY");require(OracleSubmissionFixtures.KEYS.contains(key)&&seen.add(key));
                require(namespace.equals(parent.get("SUBMISSION_SOURCE"))&&("contract-pass/"+key).equals(parent.get("VALIDATION_REFERENCE"))
                    && "contract-v1".equals(parent.get("VALIDATION_REVISION"))&&PinnedRiskEvaluators.CURRENT.equals(parent.get("EVALUATOR_VERSION"))&&parent.get("CORRELATION_ID")==null);
                var stored=acceptance.find(new SubmissionIdentity(namespace,key)).orElseThrow(OracleSubmissionFixtureCleaner::refused);
                var expected=fixture.expected(key,stored.cutoff());var canonical=new CanonicalAcceptance();var config=canonical.configuration(expected.configuration());
                require(config.json().equals(stored.configurationJson())&&config.hash().equals(stored.configurationHash())
                    &&canonical.acceptance(expected,stored.configurationVersionId(),stored.configurationRowVersion(),config.hash()).equals(new CanonicalAcceptance.Document(stored.acceptanceJson(),stored.acceptanceHash())));
                long id=PersistenceValues.id(stored.assessmentId()),snapshot=PersistenceValues.id(stored.configurationSnapshotId());
                require(id==PersistenceValues.number(parent.get("ASSESSMENT_ID"),true)&&snapshot==PersistenceValues.number(parent.get("CONFIGURATION_SNAPSHOT_ID"),true));
                require(jdbc.queryForObject("SELECT COUNT(*) FROM D2_RISK.RISK_CONFIGURATION_SNAPSHOTS WHERE ASSESSMENT_ID=?",Long.class,id)==1);
                var header=evidence.header(id);var inputs=evidence.children(id);var factors=jdbc.query(RiskFinalizationRepository.FACTORS,(rs,n)->OracleWorkPersistenceTest.values(rs),id);
                if(header.hash()==null){require(inputs.isEmpty()&&factors.isEmpty()&&!"COMPLETED".equals(stored.workState()));}
                else{
                    var frozen=new FrozenAssessmentLoader(acceptance,work,evidence).loadStored(stored);var bundle=fixture.bundle(expected);
                    var content=new EvidenceContent.Stored(expected,bundle.input(),bundle.sources());
                    require(frozen.input().equals(bundle.input())&&new CanonicalEvidence().input(content,stored).hash().equals(header.hash()));
                    if("COMPLETED".equals(stored.workState()))OracleFinalizationFixtureCleaner.verifyCompletion(parent,frozen,factors);
                    else require(factors.isEmpty());
                }
                require(Set.of("ACCEPTED","IN_PROGRESS","RETRYABLE_FAILURE","BLOCKED","COMPLETED").contains(stored.workState()));
                if(!"COMPLETED".equals(stored.workState()))for(String field:List.of("TOTAL_RAW_SCORE","FINAL_SCORE","RISK_BAND","FINAL_FACTOR_COUNT","EVALUATED_AT","COMPLETED_AT"))require(parent.get(field)==null);
                var diagnostics=jdbc.query("SELECT * FROM D2_RISK.RISK_INPUT_EVIDENCE WHERE ASSESSMENT_ID=? AND ATTEMPT_NO>0",(rs,n)->{
                    require("ATTEMPT_DIAGNOSTIC".equals(rs.getString("RECORD_CLASS"))&&"RESULT".equals(rs.getString("EVIDENCE_KEY"))&&"ATTEMPT_RESULT".equals(rs.getString("EVIDENCE_KIND"))
                        &&"risk-assessment-service".equals(rs.getString("SOURCE_SERVICE"))&&"risk-work-diagnostic-v1".equals(rs.getString("EVIDENCE_FORMAT_VERSION"))&&"risk-canonical-v1".equals(rs.getString("HASH_FORMAT_VERSION")));
                    for(String name:List.of("SOURCE_RECORD_REFERENCE","SOURCE_SNAPSHOT_REFERENCE","SOURCE_REVISION","WINDOW_START_AT","WINDOW_END_AT","RECORD_COUNT","AVAILABILITY"))require(rs.getObject(name)==null);
                    String json=rs.getString("EVIDENCE_JSON");var body=CanonicalAcceptance.read(json);var code=WorkDiagnostic.valueOf((String)body.get("code"));
                    long attempt=PersistenceValues.number(rs.getObject("ATTEMPT_NO"),true),epoch=Long.parseLong((String)body.get("epoch"));
                    require(body.keySet().equals(Set.of("attempt","code","epoch","state"))&&code.state().equals(body.get("state"))&&PersistenceValues.number(body.get("attempt"),true)==attempt
                        &&attempt<=PersistenceValues.number(parent.get("ATTEMPT_COUNT"),true)&&epoch>0&&epoch<=PersistenceValues.number(parent.get("LEASE_EPOCH"),true)&&CanonicalAcceptance.hash(json).equals(rs.getString("CONTENT_HASH")));
                    return true;
                },id);
                int allEvidence=inputs.size()+diagnostics.size();require(jdbc.queryForObject("SELECT COUNT(*) FROM D2_RISK.RISK_INPUT_EVIDENCE WHERE ASSESSMENT_ID=?",Long.class,id)==allEvidence);
                Object[] marker={id,namespace,key,"contract-pass/"+key,stored.acceptanceHash(),stored.configurationHash(),Long.parseLong(stored.rowVersion())};
                verified.add(new Verified(id,snapshot,factors.size(),allEvidence,marker));
            }
            for(var row:verified){
                require(jdbc.update("DELETE FROM D2_RISK.RISK_ASSESSMENT_FACTORS WHERE ASSESSMENT_ID=? AND EXISTS (SELECT 1 FROM D2_RISK.RISK_ASSESSMENTS WHERE "+MARKER+")",prepend(row.marker(),row.id()))==row.factors());
                require(jdbc.update("""
                    UPDATE D2_RISK.RISK_ASSESSMENTS SET WORK_STATE='ACCEPTED',CONFIGURATION_SNAPSHOT_ID=NULL,FROZEN_INPUT_EVIDENCE_ID=NULL,
                    INPUT_HASH=NULL,INPUT_FROZEN_AT=NULL,TOTAL_RAW_SCORE=NULL,FINAL_SCORE=NULL,RISK_BAND=NULL,FINAL_FACTOR_COUNT=NULL,
                    EVALUATED_AT=NULL,COMPLETED_AT=NULL,LEASE_TOKEN=NULL,LEASE_EXPIRES_AT=NULL,LAST_ERROR_CODE=NULL,LAST_ERROR_MESSAGE=NULL WHERE
                    """+MARKER,row.marker())==1);
                require(jdbc.update("DELETE FROM D2_RISK.RISK_INPUT_EVIDENCE WHERE ASSESSMENT_ID=? AND EXISTS (SELECT 1 FROM D2_RISK.RISK_ASSESSMENTS WHERE "+MARKER+")",prepend(row.marker(),row.id()))==row.evidence());
                require(jdbc.update("DELETE FROM D2_RISK.RISK_CONFIGURATION_SNAPSHOTS WHERE ASSESSMENT_ID=? AND SNAPSHOT_ID=? AND EXISTS (SELECT 1 FROM D2_RISK.RISK_ASSESSMENTS WHERE "+MARKER+")",prepend(row.marker(),row.id(),row.snapshot()))==1);
                require(jdbc.update("DELETE FROM D2_RISK.RISK_ASSESSMENTS WHERE WORK_STATE='ACCEPTED' AND CONFIGURATION_SNAPSHOT_ID IS NULL AND FROZEN_INPUT_EVIDENCE_ID IS NULL AND "+MARKER,row.marker())==1);
            }
        });
    }
    private static Object[] prepend(Object[] marker,Object... before){var result=Arrays.copyOf(before,before.length+marker.length);System.arraycopy(marker,0,result,before.length,marker.length);return result;}
    private static void require(boolean valid){if(!valid)throw refused();}
    private static IllegalStateException refused(){return new IllegalStateException("Submission fixture cleanup refused: unexpected identity or graph");}
}
