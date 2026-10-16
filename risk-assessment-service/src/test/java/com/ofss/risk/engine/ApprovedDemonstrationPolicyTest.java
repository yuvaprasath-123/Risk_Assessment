package com.ofss.risk.engine;

import com.ofss.risk.contract.RuleCode;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;
import static com.ofss.risk.engine.EngineFixtures.*;
import static com.ofss.risk.engine.RiskInput.*;
import static com.ofss.risk.engine.RiskResult.*;
import static com.ofss.risk.contract.RuleCode.*;

class ApprovedDemonstrationPolicyTest {
    private final RiskScoringEngine engine = new RiskScoringEngine();
    private Factor factor(RiskResult result, RuleCode code) {
        return result.factors().stream().filter(f -> f.ruleCode() == code).findFirst().orElseThrow();
    }
    @ParameterizedTest @EnumSource(value = PaymentStatus.class, names = {"PENDING_APPROVAL", "APPROVED", "PROCESSING", "COMPLETE"})
    void allApprovedActivityStatesQualifyForStructuringFrequencyAndNearDuplicate(PaymentStatus status) {
        var history = new ArrayList<Payment>();
        for (int i = 0; i < 3; i++) history.add(new Payment("prior" + i, "prior" + i, "invoice" + i, "INV-" + i,
                "supplier-1", "INR", new BigDecimal(i == 2 ? "20.00" : "40.00"), T.minusSeconds(i + 1),
                status == PaymentStatus.COMPLETE ? T.minusSeconds(i + 1) : null, status, true));
        var result = engine.evaluate(config(STRUCTURING, UNUSUAL_FREQUENCY, NEAR_DUPLICATE_INVOICE), input("40.00", history));
        assertEquals(Status.COMPLETED, result.status()); assertEquals(75, result.finalScore());
        for (var code : List.of(STRUCTURING, UNUSUAL_FREQUENCY, NEAR_DUPLICATE_INVOICE)) {
            assertEquals(Outcome.TRIGGERED, factor(result, code).outcome());
            assertEquals(WEIGHTS.get(code).intValue(), factor(result, code).contribution());
        }
    }
    @Test void approvedZeroBaselineContributesZeroAndOtherRulesContinue() {
        var history = new ArrayList<Payment>();
        for (int i = 0; i < 5; i++) history.add(payment("prior" + i, "0.00", T.minusSeconds(i + 1)));
        var snapshot = input("10.00", history, new Supplier("s", "supplier-1", T, SupplierStatus.ACTIVE), List.of(), List.of());
        var result = engine.evaluate(config(UNUSUAL_AMOUNT, NEW_SUPPLIER), snapshot);
        assertEquals(Status.COMPLETED, result.status());
        assertEquals(Outcome.INSUFFICIENT_HISTORY, factor(result, UNUSUAL_AMOUNT).outcome());
        assertEquals(0, factor(result, UNUSUAL_AMOUNT).contribution()); assertEquals(15, result.finalScore());
    }
    @ParameterizedTest @EnumSource(value = RuleCode.class, names = "PREVIOUS_RISK_FLAGS", mode = EnumSource.Mode.EXCLUDE)
    void eachOfSixOriginalFactorsQualifiesAndItsEvidenceIsPreserved(RuleCode code) {
        var original = new PriorAssessment("assessment", "prior", "supplier-1", T.minusSeconds(1), Set.of(code));
        var result = engine.evaluate(config(PREVIOUS_RISK_FLAGS), input("1.00", List.of(), oldSupplier(), List.of(), List.of(original)));
        assertEquals(Outcome.TRIGGERED, factor(result, PREVIOUS_RISK_FLAGS).outcome()); assertEquals(10, result.finalScore());
        assertEquals(List.of("assessment"), factor(result, PREVIOUS_RISK_FLAGS).matchedEvidenceIds());
        assertEquals(original, result.inputSnapshot().previousAssessments().records().getFirst());
        assertEquals(Set.of(code), result.inputSnapshot().previousAssessments().records().getFirst().triggeredFactors());
    }
    @Test void previousFactorsOutsideHistoricalWindowOrFromCurrentRetriesCannotQualify() {
        var records = List.of(
                new PriorAssessment("expired", "old", "supplier-1", T.minusSeconds(BASELINE + 1), Set.of(STRUCTURING)),
                new PriorAssessment("at-cutoff", "other", "supplier-1", T, Set.of(NEW_SUPPLIER)),
                new PriorAssessment("current-flag", "current", "supplier-1", T.minusSeconds(1), Set.of(UNUSUAL_AMOUNT)),
                new PriorAssessment("retry-flag", "retry-current", "supplier-1", T.minusSeconds(1), Set.of(NEAR_DUPLICATE_INVOICE)));
        var result = engine.evaluate(config(PREVIOUS_RISK_FLAGS), input("1.00", List.of(), oldSupplier(), List.of(), records));
        assertEquals(Status.COMPLETED, result.status()); assertEquals(0, result.finalScore());
        assertEquals(Outcome.NOT_TRIGGERED, factor(result, PREVIOUS_RISK_FLAGS).outcome());
        assertTrue(factor(result, PREVIOUS_RISK_FLAGS).matchedEvidenceIds().isEmpty());
        assertEquals(records, result.inputSnapshot().previousAssessments().records());
    }
}
