package com.ofss.risk.engine;

import com.ofss.risk.contract.*;
import java.math.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.ofss.risk.engine.EngineFixtures.*;
import static com.ofss.risk.engine.RiskInput.*;
import static com.ofss.risk.engine.RiskResult.*;
import static com.ofss.risk.contract.RuleCode.*;
import static com.ofss.risk.contract.RuleParameters.*;

class RiskEvidenceFailureTest {
    private final RiskScoringEngine engine = new RiskScoringEngine();
    private void technical(RiskConfiguration cfg, RiskInput input) {
        var result = engine.evaluate(cfg, input);
        assertEquals(Status.TECHNICAL_FAILURE, result.status()); assertNull(result.rawTotal()); assertNull(result.finalScore()); assertNull(result.riskBand());
        assertTrue(result.factors().stream().anyMatch(f -> f.outcome() == Outcome.TECHNICAL_FAILURE));
    }
    private <T> Evidence<T> missing(Evidence<T> source) {
        return new Evidence<>(Availability.UNAVAILABLE, source.sourceSnapshotId(), source.supplierId(), source.currencyCode(), source.cutoff(), source.coveredFrom(), source.records());
    }
    @ParameterizedTest @ValueSource(strings = {"PAYMENTS", "SUPPLIER", "TRANSITIONS", "ASSESSMENTS"})
    void everyRequiredEvidenceSourceFailsClosed(String source) {
        var i = input("1.00", List.of());
        var broken = new RiskInput(i.current(), T, i.currentAndRetrySubmissionIds(), source.equals("PAYMENTS") ? missing(i.payments()) : i.payments(),
                source.equals("SUPPLIER") ? missing(i.supplier()) : i.supplier(), source.equals("TRANSITIONS") ? missing(i.supplierTransitions()) : i.supplierTransitions(),
                source.equals("ASSESSMENTS") ? missing(i.previousAssessments()) : i.previousAssessments());
        technical(config(RuleCode.values()), broken);
    }
    @Test void completeButEmptySupplierSnapshotCannotMeanOldSupplier() {
        var i = input("1.00", List.of());
        technical(config(NEW_SUPPLIER), new RiskInput(i.current(), T, i.currentAndRetrySubmissionIds(), i.payments(), evidence(List.of()), i.supplierTransitions(), i.previousAssessments()));
    }
    @Test void unavailableDependenciesNeedNoFabricatedSnapshotOrCoverage() {
        var i = input("1.00", List.of());
        var unavailable = Evidence.<Payment>unavailable("supplier-1", "INR", T);
        assertNull(unavailable.sourceSnapshotId()); assertNull(unavailable.coveredFrom());
        technical(config(UNUSUAL_AMOUNT), payments(i, unavailable));
    }
    @Test void zeroWeightStillPreservesTriggeredOutcomeAndExplanation() {
        var cfg = replace(config(NEW_SUPPLIER), NEW_SUPPLIER, parameters(NEW_SUPPLIER), 0);
        var result = engine.evaluate(cfg, input("1.00", List.of(), new Supplier("s", "supplier-1", T, SupplierStatus.ACTIVE), List.of(), List.of()));
        var factor = result.factors().stream().filter(f -> f.ruleCode() == NEW_SUPPLIER).findFirst().orElseThrow();
        assertEquals(Outcome.TRIGGERED, factor.outcome()); assertEquals(0, factor.contribution()); assertFalse(factor.matchedEvidenceIds().isEmpty());
    }
    @ParameterizedTest @ValueSource(strings = {"SUPPLIER", "CURRENCY", "FUTURE", "CONFLICT"})
    void inconsistentHistoryIsTechnicalFailure(String scenario) {
        var p = payment("a", "1.00", T.minusSeconds(1));
        var q = new Payment("a", "a", p.invoiceId(), p.invoiceNumber(), scenario.equals("SUPPLIER") ? "other" : p.supplierId(),
                scenario.equals("CURRENCY") ? "USD" : "INR", new BigDecimal("2.00"), scenario.equals("FUTURE") ? T.plusSeconds(1) : p.submittedAt(),
                scenario.equals("FUTURE") ? T.plusSeconds(1) : p.completedAt(), PaymentStatus.COMPLETE, true);
        technical(config(UNUSUAL_FREQUENCY), input("1.00", scenario.equals("CONFLICT") ? List.of(p, q) : List.of(q)));
    }
    @Test void currentAndRetryEvidenceIsExcludedBeforeOtherHistoryChecks() {
        var current = payment("current", "1.00", T.plusSeconds(1));
        var retry = payment("retry-current", "1.00", T.plusSeconds(1));
        var result = engine.evaluate(config(UNUSUAL_FREQUENCY), input("1.00", List.of(current, retry)));
        assertEquals(Status.COMPLETED, result.status()); assertEquals(Outcome.NOT_TRIGGERED, result.factors().get(1).outcome());
    }
    @Test void failedDependencyDoesNotEraseOtherExplainedOutcomes() {
        var i = input("1.00", List.of(), new Supplier("s", "supplier-1", T, SupplierStatus.ACTIVE), List.of(), List.of());
        var result = engine.evaluate(config(UNUSUAL_AMOUNT, NEW_SUPPLIER), payments(i, missing(i.payments())));
        assertEquals(Status.TECHNICAL_FAILURE, result.status());
        assertEquals(15, result.factors().stream().filter(f -> f.ruleCode() == NEW_SUPPLIER).findFirst().orElseThrow().contribution());
        assertNull(result.finalScore());
    }
    @Test void configuredWindowWeightCapAndBandsChangeTheResult() {
        var original = replace(config(UNUSUAL_FREQUENCY), UNUSUAL_FREQUENCY, new Frequency(2, 1, HistoryEligibility.VALIDATED_SUBMISSIONS), 80);
        var cfg = new RiskConfiguration("2", "10", "altered-test", "INR", 50, original.rules(),
                List.of(new RiskConfiguration.Band("LOW", 0, 10), new RiskConfiguration.Band("MEDIUM", 11, 20), new RiskConfiguration.Band("HIGH", 21, 50)), original.approvalBoundaries());
        var result = engine.evaluate(cfg, input("1.00", List.of(payment("a", "1.00", T.minusSeconds(2)))));
        assertEquals(new BigInteger("80"), result.rawTotal()); assertEquals(50, result.finalScore()); assertEquals("HIGH", result.riskBand());
        assertEquals(0, engine.evaluate(cfg, input("1.00", List.of(payment("a", "1.00", T.minusSeconds(3))))).finalScore());
        var weight = replace(cfg, UNUSUAL_FREQUENCY, original.rules().get(1).parameters(), 15);
        assertEquals("MEDIUM", engine.evaluate(weight, input("1.00", List.of(payment("a", "1.00", T.minusSeconds(1))))).riskBand());
    }
    @Test void decimalMultiplierNeverPassesThroughDouble() {
        var cfg = replace(config(UNUSUAL_AMOUNT), UNUSUAL_AMOUNT, new Amount(BASELINE, 1, new BigDecimal("1.9999999999999999"), ZeroBaselinePolicy.UNRESOLVED), 20);
        var result = engine.evaluate(cfg, input("20.00", List.of(payment("a", "10.00", T.minusSeconds(1)))));
        assertEquals(20, result.finalScore());
    }
    @ParameterizedTest @EnumSource(value = PaymentStatus.class, names = {"DRAFT", "PENDING_VALIDATION", "REJECTED", "FAILED"})
    void explicitValidatedSubmissionPolicyExcludesIneligibleStates(PaymentStatus status) {
        var p = new Payment("a", "a", "i", "I", "supplier-1", "INR", new BigDecimal("1.00"), T.minusSeconds(1), null, status, true);
        var cfg = replace(config(UNUSUAL_FREQUENCY), UNUSUAL_FREQUENCY, new Frequency(WEEK, 1, HistoryEligibility.VALIDATED_SUBMISSIONS), 15);
        assertEquals(0, engine.evaluate(cfg, input("1.00", List.of(p))).finalScore());
    }
    @ParameterizedTest @ValueSource(longs = {MONTH, MONTH + 1})
    void nearDuplicateWindowUsesExactLowerBoundary(long age) {
        var result = engine.evaluate(config(NEAR_DUPLICATE_INVOICE), input("10.00", List.of(payment("a", "10.00", T.minusSeconds(age)))));
        assertEquals(age <= MONTH ? 25 : 0, result.finalScore());
    }
    @Test void invalidCurrentAndConfigurationAreRejectedBeforeScoring() {
        var i = input("1.00", List.of());
        var usd = new Payment("current", "current", "i", "I", "supplier-1", "USD", new BigDecimal("1.00"), T, null, PaymentStatus.PENDING_APPROVAL, true);
        assertThrows(IllegalArgumentException.class, () -> new RiskInput(usd, T, Set.of("current"), i.payments(), i.supplier(), i.supplierTransitions(), i.previousAssessments()));
        assertThrows(IllegalArgumentException.class, () -> new RiskInput(i.current(), T, Set.of("wrong"), i.payments(), i.supplier(), i.supplierTransitions(), i.previousAssessments()));
        var cfg = config();
        assertThrows(IllegalArgumentException.class, () -> new RiskConfiguration("9223372036854775808", "0", "s", "INR", 100, cfg.rules(), cfg.bands(), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new RiskConfiguration("1", "0", "s", "INR", 100, cfg.rules(), cfg.bands(), Map.of(2, new BigDecimal("1000.00"), 3, new BigDecimal("100.00"))));
        assertThrows(IllegalArgumentException.class, () -> payment("a", "0.001", T));
        assertThrows(IllegalArgumentException.class, () -> payment("a", "10000000000000.00", T));
    }
}
