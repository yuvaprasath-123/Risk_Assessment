package com.ofss.risk.persistence;

import com.ofss.risk.engine.*;
import java.math.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Offline only: no environment credentials, real DataSource or Oracle execution. */
class OracleFinalizationTestSafetyTest {
    static final String NAMESPACE=OracleFinalizationFixtures.PREFIX+"12345678-1234-1234-1234-123456789abc";
    static final Instant CREATED=Instant.parse("2026-10-15T10:00:00.123456890Z");
    static FrozenAssessmentLoader.Snapshot bundle(String key) {
        var fixtures=new OracleFinalizationFixtures(NAMESPACE); var request=fixtures.acceptance(key,CREATED); var config=new CanonicalAcceptance().configuration(request.configuration());
        var facts=new CanonicalAcceptance().acceptance(request,request.configuration().versionId(),request.configuration().rowVersion(),config.hash());
        var accepted=new AcceptedAssessment("17",request.identity(),"23",request.configuration().versionId(),request.configuration().rowVersion(),"2","IN_PROGRESS",request.cutoff(),CREATED,facts.hash(),config.hash(),facts.json(),config.json());
        var work=new RiskWorkRepository.Row(17,23,"IN_PROGRESS","synthetic-final-safety",1,1,2,CREATED.plusSeconds(120),CREATED,request.cutoff(),null,null);
        var freeze=fixtures.evidence(work.lease(),request); var document=new CanonicalEvidence().input(freeze,accepted); var rows=new ArrayList<RiskEvidenceRepository.Stored>();
        for(var row:RiskEvidenceService.inputs(freeze,accepted,CREATED.plusSeconds(5))) rows.add(row.withId(100+rows.size()));
        rows.add(RiskEvidenceService.manifest(freeze,document,rows,CREATED.plusSeconds(5)).withId(105));
        return new FrozenAssessmentLoader.Snapshot(accepted,work,new RiskEvidenceRepository.Header(document.hash(),105L,CREATED.plusSeconds(5)),rows,StoredConfigurationDecoder.read(config.json()),FrozenInputDecoder.read(document.json()),PinnedRiskEvaluators.CURRENT);
    }
    static Map<String,Object> parent(FrozenAssessmentLoader.Snapshot s,RiskResult result) {
        var values=new HashMap<String,Object>(); values.put("FINAL_FACTOR_COUNT",BigDecimal.valueOf(7)); values.put("TOTAL_RAW_SCORE",new BigDecimal(result.rawTotal()));
        values.put("FINAL_SCORE",BigDecimal.valueOf(result.finalScore())); values.put("RISK_BAND",result.riskBand());
        values.put("EVALUATED_AT",s.header().frozenAt().plusNanos(1)); values.put("COMPLETED_AT",s.header().frozenAt().plusNanos(2)); return values;
    }
    static List<Map<String,Object>> factors(FrozenAssessmentLoader.Snapshot s,RiskResult result) {
        var rows=new ArrayList<Map<String,Object>>();
        for(var f:result.factors()) {
            var row=new HashMap<String,Object>(); var rule=s.config().rules().stream().filter(r->r.code()==f.ruleCode()).findFirst().orElseThrow();
            row.put("FACTOR_ID",BigDecimal.valueOf(rows.size()+1)); row.put("ASSESSMENT_ID",BigDecimal.valueOf(s.work().id())); row.put("RULE_CODE",f.ruleCode().name());
            row.put("FACTOR_ORDER",BigDecimal.valueOf(rule.evaluationOrder())); row.put("EVALUATION_OUTCOME",f.outcome().name());
            row.put("CONFIGURED_WEIGHT",BigDecimal.valueOf(f.configuredWeight())); row.put("SCORE_CONTRIBUTION",BigDecimal.valueOf(f.contribution()));
            row.put("EXPLANATION",f.explanation()); row.put("RESULT_FORMAT_VERSION",FinalizedFactors.FORMAT); row.put("EVALUATED_AT",s.header().frozenAt().plusNanos(1));
            row.put("PRIMARY_EVIDENCE_ID",BigDecimal.valueOf(s.header().manifest())); row.put("DETAILS_JSON",FinalizedFactors.details(f,s,"a".repeat(64))); rows.add(row);
        }
        return rows;
    }
    @ParameterizedTest @NullSource @ValueSource(strings={"D2_RISK","D2_RISK_FINAL_","D2_RISK_FINAL_1-1-1-1-1",
        "D2_RISK_FREEZE_12345678-1234-1234-1234-123456789abc","D2_RISK_FINAL_12345678-1234-1234-1234-123456789ABC"})
    void invalidOrDifferentMilestoneNamespaceCannotReachSql(String namespace) {
        var jdbc=mock(JdbcTemplate.class); var manager=mock(PlatformTransactionManager.class);
        assertThrows(IllegalArgumentException.class,()->new OracleFinalizationFixtureCleaner(jdbc,manager).clean(namespace)); verifyNoInteractions(jdbc,manager);
    }
    @ParameterizedTest @ValueSource(strings={"success","nanoseconds","maximum-score","insufficient","retries","concurrent","fencing","final-expiry","rollback-factor","rollback-completed","rollback-readback","completed-work","policy-unresolved"})
    void exactFixtureGraphRoundTripsAndProducesIntendedEngineOutcome(String key) {
        var s=bundle(key); var fixtures=new OracleFinalizationFixtures(NAMESPACE); var accepted=fixtures.acceptance(key,CREATED);
        var request=fixtures.evidence(s.work().lease(),accepted); EvidenceValidation.matches(request,s.accepted());
        RiskEvidenceService.verifyStored(request,s.accepted(),new CanonicalEvidence().input(request,s.accepted()),s.header(),s.evidence());
        assertEquals(123456789,s.input().cutoff().getNano()); assertEquals(new BigDecimal("40.01"),s.input().current().amount());
        var result=PinnedRiskEvaluators.current().evaluate(s.evaluator(),s.config(),s.input());
        if(key.equals("policy-unresolved")) { assertEquals(RiskResult.Status.POLICY_UNRESOLVED,result.status()); assertNull(result.finalScore()); }
        else {
            FinalizedFactors.validate(result,s); assertEquals(key.equals("insufficient")?BigInteger.ZERO:key.equals("maximum-score")?new BigInteger("15032385529"):BigInteger.valueOf(135),result.rawTotal());
            var rows=factors(s,result); OracleFinalizationFixtureCleaner.verifyCompletion(parent(s,result),s,rows);
            if(!key.equals("insufficient")) assertTrue(rows.stream().anyMatch(r->((String)r.get("DETAILS_JSON")).length()>32767));
        }
    }
    @ParameterizedTest @ValueSource(strings={"score","factor-count","factor-weight","factor-code","explanation","evidence-reference","details","timestamp","lease"})
    void completionCleanerRefusesCorruptOrUnrecognizedResult(String changed) {
        var s=bundle("success"); var result=PinnedRiskEvaluators.current().evaluate(s.evaluator(),s.config(),s.input()); var parent=parent(s,result); var rows=factors(s,result);
        switch(changed) {
            case "score" -> parent.put("FINAL_SCORE",BigDecimal.ZERO);
            case "factor-count" -> rows.removeLast();
            case "factor-weight" -> rows.getFirst().put("CONFIGURED_WEIGHT",BigDecimal.ZERO);
            case "factor-code" -> rows.getFirst().put("RULE_CODE","UNRECOGNIZED");
            case "explanation" -> rows.getFirst().put("EXPLANATION","unexpected");
            case "evidence-reference" -> rows.getFirst().put("PRIMARY_EVIDENCE_ID",BigDecimal.valueOf(999));
            case "details" -> rows.getFirst().put("DETAILS_JSON",((String)rows.getFirst().get("DETAILS_JSON")).replace("risk-factor-v1","risk-factor-v2"));
            case "timestamp" -> parent.put("COMPLETED_AT",CREATED);
            case "lease" -> parent.put("LEASE_TOKEN","unexpected");
        }
        assertThrows(RuntimeException.class,()->OracleFinalizationFixtureCleaner.verifyCompletion(parent,s,rows));
    }
    @SuppressWarnings("unchecked") @ParameterizedTest @ValueSource(strings={"key","evaluator","validation","correlation","blocked","namespace"})
    void unexpectedNamespaceParentRefusesEntireCleanupBeforeMutations(String changed) {
        var jdbc=mock(JdbcTemplate.class); var manager=mock(PlatformTransactionManager.class); when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var row=new HashMap<String,Object>(); row.put("SUBMISSION_KEY",changed.equals("key")?"unrelated":"success"); row.put("SUBMISSION_SOURCE",changed.equals("namespace")?"other":NAMESPACE);
        row.put("EVALUATOR_VERSION",changed.equals("evaluator")?"unknown":PinnedRiskEvaluators.CURRENT); row.put("VALIDATION_REFERENCE",changed.equals("validation")?"unrelated":"synthetic-final-pass/success");
        row.put("CORRELATION_ID",changed.equals("correlation")?"unrelated":"final-fixture/success"); row.put("WORK_STATE",changed.equals("blocked")?"BLOCKED":"COMPLETED");
        when(jdbc.query(eq(OracleFinalizationFixtureCleaner.LOCK),any(RowMapper.class),eq(NAMESPACE))).thenReturn(List.of(row));
        assertThrows(IllegalStateException.class,()->new OracleFinalizationFixtureCleaner(jdbc,manager).clean(NAMESPACE));
        verify(jdbc,never()).update(anyString(),any(Object[].class)); verify(manager).rollback(any()); verify(manager,never()).commit(any());
    }
    @SuppressWarnings("unchecked") @Test void emptyExactNamespaceDoesNothingAndCommits() {
        var jdbc=mock(JdbcTemplate.class); var manager=mock(PlatformTransactionManager.class); when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(jdbc.query(eq(OracleFinalizationFixtureCleaner.LOCK),any(RowMapper.class),eq(NAMESPACE))).thenReturn(List.of());
        new OracleFinalizationFixtureCleaner(jdbc,manager).clean(NAMESPACE); verify(jdbc,never()).update(anyString(),any(Object[].class)); verify(manager).commit(any());
    }
    @Test void everyMutationHasExactIdentityGuardsAndCompletionIsDetachedConsistently() {
        for(String sql:List.of(OracleFinalizationFixtureCleaner.DELETE_FACTORS,OracleFinalizationFixtureCleaner.DETACH,OracleFinalizationFixtureCleaner.DELETE_EVIDENCE,
            OracleFinalizationFixtureCleaner.DELETE_SNAPSHOT,OracleFinalizationFixtureCleaner.DELETE_PARENT,OracleFinalizationPersistenceTest.EXPIRE)) {
            for(String guard:List.of("ASSESSMENT_ID=?","SUBMISSION_SOURCE=?","SUBMISSION_KEY=?","EVALUATOR_VERSION=?","VALIDATION_REFERENCE=?","ROW_VERSION=?")) assertTrue(sql.contains(guard));
            for(String forbidden:List.of("TRUNCATE","DROP","ALTER","SEQUENCE","SYSTIMESTAMP=")) assertFalse(sql.contains(forbidden));
        }
        for(String required:List.of("WORK_STATE='ACCEPTED'","CONFIGURATION_SNAPSHOT_ID=NULL","FROZEN_INPUT_EVIDENCE_ID=NULL","INPUT_HASH=NULL","INPUT_FROZEN_AT=NULL",
            "TOTAL_RAW_SCORE=NULL","FINAL_SCORE=NULL","RISK_BAND=NULL","FINAL_FACTOR_COUNT=NULL","EVALUATED_AT=NULL","COMPLETED_AT=NULL","LEASE_TOKEN=NULL","LEASE_EXPIRES_AT=NULL")) assertTrue(OracleFinalizationFixtureCleaner.DETACH.contains(required));
    }
    @Test void writeTestsAndRecoveryRequireDistinctExplicitOptIns() {
        assertTrue(Arrays.stream(OracleFinalizationPersistenceTest.class.getAnnotationsByType(EnabledIfEnvironmentVariable.class))
            .anyMatch(a->a.named().equals("RISK_VERIFY_ORACLE_FINALIZATION_WRITES") && a.matches().equals("true")));
        var recovery=Arrays.stream(OracleFinalizationFixtureCleanupTest.class.getAnnotationsByType(EnabledIfEnvironmentVariable.class)).map(EnabledIfEnvironmentVariable::named).collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("RISK_VERIFY_ORACLE_FINALIZATION_WRITES","RISK_ORACLE_FINALIZATION_CLEANUP_ONLY","RISK_ORACLE_FINALIZATION_WORKERS_STOPPED"),recovery);
    }
}
