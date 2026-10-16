package com.ofss.risk.engine;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.ofss.risk.engine.EngineFixtures.*;
import static com.ofss.risk.engine.RiskInput.*;
import static com.ofss.risk.engine.RiskResult.*;
import static com.ofss.risk.contract.RuleCode.*;
import static com.ofss.risk.contract.RuleParameters.*;

class RiskReviewRegressionTest {
    @Test void currentCompletionCannotLieAfterFrozenCutoffEvenWithAllRulesDisabled() {
        var source = input("1.00", List.of());
        var futureCompletion = new Payment("current", "current", "i", "I", "supplier-1", "INR", source.current().amount(),
                T.minusSeconds(1), T.plusSeconds(1), PaymentStatus.COMPLETE, true);
        assertThrows(IllegalArgumentException.class, () -> new RiskInput(futureCompletion, T, Set.of("current"),
                source.payments(), source.supplier(), source.supplierTransitions(), source.previousAssessments()));
    }
    @Test void currentCompletionAtCutoffIsAccepted() {
        var source = input("1.00", List.of());
        assertDoesNotThrow(() -> new RiskInput(source.current(), T, Set.of("current"), source.payments(),
                source.supplier(), source.supplierTransitions(), source.previousAssessments()));
    }
    @Test void equivalentDecimalFormatsDeduplicateWithoutFalseTechnicalFailure() {
        var a = payment("prior", "10.0", T.minusSeconds(1));
        var b = payment("prior", "10.00", T.minusSeconds(1));
        var cfg = replace(config(UNUSUAL_FREQUENCY), UNUSUAL_FREQUENCY,
                new Frequency(WEEK, 1, HistoryEligibility.VALIDATED_SUBMISSIONS), 15);
        var result = new RiskScoringEngine().evaluate(cfg, input("1.00", List.of(a, b)));
        assertEquals(Status.COMPLETED, result.status()); assertEquals(15, result.finalScore());
        var factor = result.factors().stream().filter(f -> f.ruleCode() == UNUSUAL_FREQUENCY).findFirst().orElseThrow();
        assertEquals("2", factor.observations().get("count")); // one prior submission plus current
        assertEquals(List.of("current", "prior"), factor.matchedEvidenceIds());
    }
    @Test void observationIterationIsStableSortedAndDefensivelyCopied() {
        var keys = new ArrayList<>(List.of("windowStart", "cutoff", "sampleCount", "historicalSum", "currentAmount", "meanMultiplier",
                "level2Count", "level2Boundary", "level2Total", "level3Count", "level3Boundary", "level3Total"));
        var expected = keys.stream().sorted().toList();
        for (int seed = 0; seed < 10; seed++) {
            Collections.shuffle(keys, new Random(seed));
            var source = new LinkedHashMap<String, String>(); keys.forEach(key -> source.put(key, key));
            var factor = new Factor(STRUCTURING, Outcome.NOT_TRIGGERED, 35, 0, "Explained result", List.of(), source);
            assertEquals(expected, new ArrayList<>(factor.observations().keySet()));
            source.clear(); assertEquals(expected.size(), factor.observations().size());
            assertThrows(UnsupportedOperationException.class, () -> factor.observations().clear());
        }
        var invalid = new HashMap<String, String>(); invalid.put("count", null);
        assertThrows(NullPointerException.class, () -> new Factor(STRUCTURING, Outcome.NOT_TRIGGERED, 35, 0,
                "Explained result", List.of(), invalid));
    }
}
