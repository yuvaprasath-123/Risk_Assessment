package com.ofss.risk.persistence;

import com.ofss.risk.engine.*;
import java.util.*;

/** Version identifies retained executable semantics; never alias an arbitrary accepted version to latest code. */
public final class PinnedRiskEvaluators {
    public static final String CURRENT="risk-scoring-v1";
    @FunctionalInterface interface Evaluator { RiskResult evaluate(RiskConfiguration config,RiskInput input); }
    private final Map<String,Evaluator> evaluators;
    PinnedRiskEvaluators(Map<String,Evaluator> evaluators) { this.evaluators=Map.copyOf(evaluators); }
    public static PinnedRiskEvaluators current() { return new PinnedRiskEvaluators(Map.of(CURRENT,new RiskScoringEngine()::evaluate)); }
    RiskResult evaluate(String version,RiskConfiguration config,RiskInput input) {
        var evaluator=evaluators.get(version);
        if(evaluator==null) throw new FinalizationFailure(FinalizationFailure.Code.EVALUATOR_UNAVAILABLE);
        return evaluator.evaluate(config,input);
    }
}
