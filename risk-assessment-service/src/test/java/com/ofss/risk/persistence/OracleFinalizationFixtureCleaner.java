package com.ofss.risk.persistence;

import com.ofss.risk.engine.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/** TEST ONLY: exact namespace, known fixtures and verified graph; never broadens previous milestone cleaners. */
final class OracleFinalizationFixtureCleaner {
    static final String LOCK="SELECT * FROM D2_RISK.RISK_ASSESSMENTS WHERE SUBMISSION_SOURCE=? FOR UPDATE";
    static final String MARKER="""
        ASSESSMENT_ID=? AND SUBMISSION_SOURCE=? AND SUBMISSION_KEY=? AND EVALUATOR_VERSION=? AND VALIDATION_REFERENCE=?
        AND CORRELATION_ID=? AND ACCEPTANCE_HASH=? AND CONFIGURATION_HASH=? AND ROW_VERSION=?
        """;
    static final String DELETE_FACTORS="DELETE FROM D2_RISK.RISK_ASSESSMENT_FACTORS WHERE ASSESSMENT_ID=? AND EXISTS (SELECT 1 FROM D2_RISK.RISK_ASSESSMENTS WHERE "+MARKER+")";
    // Clearing completion and its references in the SAME statement satisfies the deployed completion CHECK.
    static final String DETACH="""
        UPDATE D2_RISK.RISK_ASSESSMENTS SET WORK_STATE='ACCEPTED',CONFIGURATION_SNAPSHOT_ID=NULL,
        FROZEN_INPUT_EVIDENCE_ID=NULL,INPUT_HASH=NULL,INPUT_FROZEN_AT=NULL,TOTAL_RAW_SCORE=NULL,FINAL_SCORE=NULL,
        RISK_BAND=NULL,FINAL_FACTOR_COUNT=NULL,EVALUATED_AT=NULL,COMPLETED_AT=NULL,LEASE_TOKEN=NULL,LEASE_EXPIRES_AT=NULL,
        LAST_ERROR_CODE=NULL,LAST_ERROR_MESSAGE=NULL WHERE
        """+MARKER;
    static final String DELETE_EVIDENCE="DELETE FROM D2_RISK.RISK_INPUT_EVIDENCE WHERE ASSESSMENT_ID=? AND ATTEMPT_NO=0 AND EXISTS (SELECT 1 FROM D2_RISK.RISK_ASSESSMENTS WHERE "+MARKER+")";
    static final String DELETE_SNAPSHOT="DELETE FROM D2_RISK.RISK_CONFIGURATION_SNAPSHOTS WHERE ASSESSMENT_ID=? AND SNAPSHOT_ID=? AND EXISTS (SELECT 1 FROM D2_RISK.RISK_ASSESSMENTS WHERE "+MARKER+")";
    static final String DELETE_PARENT="DELETE FROM D2_RISK.RISK_ASSESSMENTS WHERE WORK_STATE='ACCEPTED' AND CONFIGURATION_SNAPSHOT_ID IS NULL AND FROZEN_INPUT_EVIDENCE_ID IS NULL AND "+MARKER;
    record Verified(long id,long snapshot,int inputs,int factors,Object[] marker) {}
    private final JdbcTemplate jdbc; private final TransactionTemplate tx;
    OracleFinalizationFixtureCleaner(JdbcTemplate jdbc,PlatformTransactionManager manager) {
        this.jdbc=jdbc; tx=new TransactionTemplate(manager); tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    void clean(String namespace) {
        clean(namespace,OracleFinalizationFixtures.KEYS);
    }
    void clean(String namespace,Set<String> allowedKeys) {
        OracleFinalizationFixtures.requireNamespace(namespace);
        require(!allowedKeys.isEmpty() && OracleFinalizationFixtures.KEYS.containsAll(allowedKeys));
        tx.executeWithoutResult(status->{
            var fixtures=new OracleFinalizationFixtures(namespace); var verified=new ArrayList<Verified>(); var keys=new HashSet<String>();
            for(var parent:jdbc.query(LOCK,(rs,n)->OracleWorkPersistenceTest.values(rs),namespace)) {
                String key=(String)parent.get("SUBMISSION_KEY"); require(allowedKeys.contains(key) && keys.add(key));
                require(namespace.equals(parent.get("SUBMISSION_SOURCE")) && PinnedRiskEvaluators.CURRENT.equals(parent.get("EVALUATOR_VERSION"))
                    && ("synthetic-final-pass/"+key).equals(parent.get("VALIDATION_REFERENCE")) && ("final-fixture/"+key).equals(parent.get("CORRELATION_ID"))
                    && Set.of("ACCEPTED","IN_PROGRESS","COMPLETED").contains(parent.get("WORK_STATE")));
                long id=PersistenceValues.number(parent.get("ASSESSMENT_ID"),true),snapshot=PersistenceValues.number(parent.get("CONFIGURATION_SNAPSHOT_ID"),true);
                var expected=fixtures.acceptance(key,(Instant)parent.get("CREATED_AT"));
                var accepted=new RiskAcceptanceRepository(jdbc).find(expected.identity()).orElseThrow(OracleFinalizationFixtureCleaner::refused);
                var canonical=new CanonicalAcceptance(); var config=canonical.configuration(expected.configuration());
                require(accepted.assessmentId().equals(Long.toString(id)) && accepted.configurationSnapshotId().equals(Long.toString(snapshot))
                    && config.json().equals(accepted.configurationJson()) && config.hash().equals(accepted.configurationHash())
                    && canonical.acceptance(expected,expected.configuration().versionId(),expected.configuration().rowVersion(),config.hash()).json().equals(accepted.acceptanceJson()));
                var evidence=new RiskEvidenceRepository(jdbc); var header=evidence.header(id); var inputs=evidence.children(id);
                var factors=jdbc.query(RiskFinalizationRepository.FACTORS,(rs,n)->OracleWorkPersistenceTest.values(rs),id);
                require(Objects.equals((long)inputs.size(),jdbc.queryForObject("SELECT COUNT(*) FROM D2_RISK.RISK_INPUT_EVIDENCE WHERE ASSESSMENT_ID=?",Long.class,id)));
                if(header.hash()==null) require(inputs.isEmpty() && factors.isEmpty() && !"COMPLETED".equals(accepted.workState()));
                else {
                    var row=new RiskWorkRepository(jdbc).read(id);
                    var fake=new WorkLease(Long.toString(id),"cleanup-verifier",Math.max(1,row.epoch()),Math.max(1,row.attempt()),Long.toString(row.revision()),row.created().plusSeconds(1));
                    var request=new FinalizationRequest(expected.identity(),fake,accepted.acceptanceHash(),accepted.configurationHash(),header.hash());
                    var frozen=new FrozenAssessmentLoader(new RiskAcceptanceRepository(jdbc),new RiskWorkRepository(jdbc),evidence).load(request);
                    var expectedEvidence=fixtures.evidence(fake,expected);
                    require(expectedEvidence.input().equals(frozen.input()) && new CanonicalEvidence().input(expectedEvidence,accepted).hash().equals(header.hash()));
                    if("COMPLETED".equals(accepted.workState())) verifyCompletion(parent,frozen,factors);
                    else { require(factors.isEmpty()); noScores(parent); }
                }
                if(!"COMPLETED".equals(accepted.workState())) noScores(parent);
                Object[] marker={id,namespace,key,PinnedRiskEvaluators.CURRENT,"synthetic-final-pass/"+key,"final-fixture/"+key,
                    accepted.acceptanceHash(),accepted.configurationHash(),PersistenceValues.number(parent.get("ROW_VERSION"),false)};
                verified.add(new Verified(id,snapshot,inputs.size(),factors.size(),marker));
            }
            // Verify EVERY parent/child before the first mutation. Unknown or partial finalization data refuses cleanup.
            for(var row:verified) {
                require(jdbc.update(DELETE_FACTORS,prepend(row.marker(),row.id()))==row.factors());
                require(jdbc.update(DETACH,row.marker())==1);
                require(jdbc.update(DELETE_EVIDENCE,prepend(row.marker(),row.id()))==row.inputs());
                require(jdbc.update(DELETE_SNAPSHOT,prepend(row.marker(),row.id(),row.snapshot()))==1);
                require(jdbc.update(DELETE_PARENT,row.marker())==1);
            }
        });
    }
    static void verifyCompletion(Map<String,Object> parent,FrozenAssessmentLoader.Snapshot s,List<Map<String,Object>> factors) {
        var expected=PinnedRiskEvaluators.current().evaluate(s.evaluator(),s.config(),s.input()); FinalizedFactors.validate(expected,s);
        require(factors.size()==7 && PersistenceValues.number(parent.get("FINAL_FACTOR_COUNT"),true)==7
            && expected.rawTotal().longValueExact()==PersistenceValues.number(parent.get("TOTAL_RAW_SCORE"),false)
            && expected.finalScore()==PersistenceValues.number(parent.get("FINAL_SCORE"),false) && expected.riskBand().equals(parent.get("RISK_BAND"))
            && parent.get("LEASE_TOKEN")==null && parent.get("LEASE_EXPIRES_AT")==null && parent.get("LAST_ERROR_CODE")==null && parent.get("LAST_ERROR_MESSAGE")==null);
        Instant evaluated=(Instant)parent.get("EVALUATED_AT"),completed=(Instant)parent.get("COMPLETED_AT");
        require(evaluated!=null && completed!=null && !evaluated.isBefore(s.header().frozenAt()) && !completed.isBefore(evaluated));
        String receipt=null; var seen=new HashSet<String>();
        for(var row:factors) {
            String code=(String)row.get("RULE_CODE"),json=(String)row.get("DETAILS_JSON"); require(seen.add(code));
            var f=expected.factors().stream().filter(v->v.ruleCode().name().equals(code)).findFirst().orElseThrow(OracleFinalizationFixtureCleaner::refused);
            var rule=s.config().rules().stream().filter(v->v.code()==f.ruleCode()).findFirst().orElseThrow();
            var body=CanonicalAcceptance.read(json); String hash=(String)body.get("completionFenceHash");
            require(hash!=null && hash.matches("[0-9a-f]{64}")); if(receipt==null) receipt=hash; else require(receipt.equals(hash));
            require(PersistenceValues.number(row.get("FACTOR_ID"),true)>0 && PersistenceValues.number(row.get("ASSESSMENT_ID"),true)==s.work().id()
                && row.get("RULE_CONFIG_SOURCE_ID")==null && PersistenceValues.number(row.get("FACTOR_ORDER"),true)==rule.evaluationOrder()
                && f.outcome().name().equals(row.get("EVALUATION_OUTCOME")) && f.configuredWeight()==PersistenceValues.number(row.get("CONFIGURED_WEIGHT"),false)
                && f.contribution()==PersistenceValues.number(row.get("SCORE_CONTRIBUTION"),false) && f.explanation().equals(row.get("EXPLANATION"))
                && FinalizedFactors.FORMAT.equals(row.get("RESULT_FORMAT_VERSION")) && evaluated.equals(row.get("EVALUATED_AT"))
                && FinalizedFactors.details(f,s,hash).equals(json));
            require(f.outcome()==RiskResult.Outcome.DISABLED?row.get("PRIMARY_EVIDENCE_ID")==null:
                PersistenceValues.number(row.get("PRIMARY_EVIDENCE_ID"),true)==s.header().manifest());
        }
    }
    private static void noScores(Map<String,Object> parent) {
        for(String key:List.of("TOTAL_RAW_SCORE","FINAL_SCORE","RISK_BAND","FINAL_FACTOR_COUNT","EVALUATED_AT","COMPLETED_AT")) require(parent.get(key)==null);
    }
    private static Object[] prepend(Object[] marker,Object... before) {
        var result=Arrays.copyOf(before,before.length+marker.length); System.arraycopy(marker,0,result,before.length,marker.length); return result;
    }
    private static void require(boolean valid) { if(!valid) throw refused(); }
    private static IllegalStateException refused() { return new IllegalStateException("Finalization cleanup refused: unexpected fixture identity or graph"); }
}
