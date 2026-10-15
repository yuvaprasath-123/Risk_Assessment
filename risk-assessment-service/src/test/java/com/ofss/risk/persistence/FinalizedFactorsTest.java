package com.ofss.risk.persistence;

import com.ofss.risk.engine.*;
import com.ofss.risk.contract.*;
import java.math.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class FinalizedFactorsTest {
    final RiskScoringEngine engine=new RiskScoringEngine();
    @ParameterizedTest @ValueSource(ints={0,29,30,60,61,100,135}) void configuredContributionsAndBandBoundaries(int weight) {
        var c=FinalizationFixtures.config(); var rules=c.rules().stream().map(r->new RiskConfiguration.Rule(r.code(),r.code()==RuleCode.UNUSUAL_FREQUENCY,
            weight,r.evaluationOrder(),r.parameterSchemaVersion(),r.parameters())).toList();
        c=new RiskConfiguration(c.versionId(),c.rowVersion(),c.contentHash(),"INR",100,rules,c.bands(),c.approvalBoundaries());
        var s=FinalizationFixtures.snapshot(c,FinalizationFixtures.input(true)); var result=engine.evaluate(c,s.input()); FinalizedFactors.validate(result,s);
        assertEquals(BigInteger.valueOf(weight),result.rawTotal()); assertEquals(Math.min(weight,100),result.finalScore());
        assertEquals(weight<30?"LOW":weight<=60?"MEDIUM":"HIGH",result.riskBand()); assertEquals(6,result.factors().stream().filter(f->f.outcome()==RiskResult.Outcome.DISABLED).count());
    }
    @Test void maximumSevenIntegerWeightsRemainExactBeyondIntegerRange() {
        var c=FinalizationFixtures.config(); c=new RiskConfiguration(c.versionId(),c.rowVersion(),c.contentHash(),"INR",100,c.rules().stream()
            .map(r->new RiskConfiguration.Rule(r.code(),true,Integer.MAX_VALUE,r.evaluationOrder(),r.parameterSchemaVersion(),r.parameters())).toList(),c.bands(),c.approvalBoundaries());
        var s=FinalizationFixtures.snapshot(c,FinalizationFixtures.input(true)); var result=engine.evaluate(c,s.input());
        FinalizedFactors.validate(result,s); assertEquals(new BigInteger("15032385529"),result.rawTotal()); assertEquals(100,result.finalScore());
    }
    @Test void insufficientHistoryIsACompletedZeroContributionNotTechnicalFailure() {
        var s=FinalizationFixtures.snapshot(FinalizationFixtures.config(),FinalizationFixtures.input(false)); var result=engine.evaluate(s.config(),s.input());
        FinalizedFactors.validate(result,s); assertEquals(RiskResult.Status.COMPLETED,result.status()); assertEquals(BigInteger.ZERO,result.rawTotal());
        assertEquals(RiskResult.Outcome.INSUFFICIENT_HISTORY,result.factors().stream().filter(f->f.ruleCode()==RuleCode.UNUSUAL_AMOUNT).findFirst().orElseThrow().outcome());
    }
    @ParameterizedTest @ValueSource(strings={"missing","duplicate","weight","disabled","total","score","band","identity","explanation"}) void rejectsIncompleteOrInconsistentEvaluatorOutput(String changed) {
        var s=FinalizationFixtures.snapshot(); var good=engine.evaluate(s.config(),s.input()); var factors=new ArrayList<>(good.factors());
        if(changed.equals("missing")) factors.removeLast();
        if(changed.equals("duplicate")) factors.set(1,factors.getFirst());
        if(Set.of("weight","disabled","explanation").contains(changed)) {
            var f=factors.getFirst(); int weight=changed.equals("weight")?1:f.configuredWeight(); var outcome=changed.equals("disabled")?RiskResult.Outcome.DISABLED:f.outcome();
            factors.set(0,new RiskResult.Factor(f.ruleCode(),outcome,weight,outcome==RiskResult.Outcome.TRIGGERED?weight:0,changed.equals("explanation")?"x".repeat(2001):f.explanation(),f.matchedEvidenceIds(),f.observations()));
        }
        var bad=new RiskResult(good.status(),changed.equals("identity")?"other":good.submissionId(),good.configurationVersionId(),good.configurationRowVersion(),good.configurationContentHash(),good.cutoff(),
            changed.equals("total")?BigInteger.ZERO:good.rawTotal(),changed.equals("score")?0:good.finalScore(),changed.equals("band")?"LOW":good.riskBand(),factors,s.config(),s.input());
        assertThrows(RuntimeException.class,()->FinalizedFactors.validate(bad,s));
    }
    @Test void factorDetailsPreserveOriginalEvidenceObservationsAndNoPlaintextLease() {
        var s=FinalizationFixtures.snapshot(); var r=FinalizationFixtures.request(s); var good=engine.evaluate(s.config(),s.input());
        for(var factor:good.factors()) {
            String json=FinalizedFactors.details(factor,s,FinalizedFactors.receipt(r));
            assertEquals(factor,FinalizedFactors.decode(factor.ruleCode(),factor.outcome(),factor.configuredWeight(),factor.contribution(),factor.explanation(),json));
            assertFalse(json.contains(r.lease().token())); assertTrue(json.contains("evidenceReferences"));
        }
    }
    @Test void unknownPinnedEvaluatorCannotFallBackToCurrentCode() {
        var s=FinalizationFixtures.snapshot(); assertEquals(FinalizationFailure.Code.EVALUATOR_UNAVAILABLE,
            assertThrows(FinalizationFailure.class,()->PinnedRiskEvaluators.current().evaluate("engine-build-1",s.config(),s.input())).code());
    }
    @Test void currentPinnedVersionUsesActualEngine() {
        var s=FinalizationFixtures.snapshot(); assertEquals(engine.evaluate(s.config(),s.input()),PinnedRiskEvaluators.current().evaluate(PinnedRiskEvaluators.CURRENT,s.config(),s.input()));
    }
}
