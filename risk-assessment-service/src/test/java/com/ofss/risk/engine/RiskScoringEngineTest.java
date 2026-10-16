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

class RiskScoringEngineTest {
    private final RiskScoringEngine engine = new RiskScoringEngine();
    private Factor factor(RiskResult result, RuleCode code) { return result.factors().stream().filter(f -> f.ruleCode() == code).findFirst().orElseThrow(); }
    private RiskResult evaluate(RuleCode code, RiskInput input) { return engine.evaluate(config(code), input); }
    private void outcome(RiskResult result, RuleCode code, Outcome expected) {
        assertEquals(expected, factor(result, code).outcome());
        assertEquals(expected == Outcome.TRIGGERED ? WEIGHTS.get(code) : 0, factor(result, code).contribution());
        assertFalse(factor(result, code).explanation().isBlank());
    }
    @ParameterizedTest @CsvSource({"59.99,NOT_TRIGGERED", "60.00,TRIGGERED", "100.00,NOT_TRIGGERED"})
    void structuringUsesInclusiveBoundaryAndIndividuallySubBoundaryAmounts(String amount, Outcome expected) {
        var cfg = replace(config(STRUCTURING), STRUCTURING, new Structuring(WEEK, 2, List.of(2), HistoryEligibility.VALIDATED_SUBMISSIONS), 35);
        outcome(engine.evaluate(cfg, input(amount, List.of(payment("old", "40.00", T.minusSeconds(1))))), STRUCTURING, expected);
    }
    @Test void structuringMultipleBoundariesContributeOnlyOnceAndPreserveMatches() {
        var result = evaluate(STRUCTURING, input("1.00", List.of(payment("a", "99.00", T.minusSeconds(1)), payment("b", "999.00", T.minusSeconds(2)))));
        outcome(result, STRUCTURING, Outcome.TRIGGERED);
        assertEquals(35, result.finalScore()); assertEquals(List.of("a", "b", "current"), factor(result, STRUCTURING).matchedEvidenceIds());
    }
    @ParameterizedTest @ValueSource(ints = {0, 1, 2, 3, 4})
    void frequencyIncludesCurrentOnceAndUsesStrictGreaterThan(int count) {
        var records = new ArrayList<Payment>(); for (int i = 0; i < count; i++) records.add(payment("p" + i, "1.00", T.minusSeconds(i + 1)));
        outcome(evaluate(UNUSUAL_FREQUENCY, input("1.00", records)), UNUSUAL_FREQUENCY, count + 1 > 3 ? Outcome.TRIGGERED : Outcome.NOT_TRIGGERED);
    }
    @Test void currentRetriesAndDuplicateFetchedRowsCannotInflateHistory() {
        var prior = payment("prior", "1.00", T.minusSeconds(1));
        var result = evaluate(UNUSUAL_FREQUENCY, input("1.00", List.of(prior, prior, payment("current", "1.00", T.minusSeconds(1)), payment("retry-current", "1.00", T.minusSeconds(1)))));
        outcome(result, UNUSUAL_FREQUENCY, Outcome.NOT_TRIGGERED); assertEquals("2", factor(result, UNUSUAL_FREQUENCY).observations().get("count"));
    }
    @ParameterizedTest @ValueSource(longs = {WEEK - 1, WEEK, WEEK + 1})
    void lowerHistoryBoundaryIsInclusive(long age) {
        var cfg = replace(config(UNUSUAL_FREQUENCY), UNUSUAL_FREQUENCY, new Frequency(WEEK, 1, HistoryEligibility.VALIDATED_SUBMISSIONS), 15);
        var result = engine.evaluate(cfg, input("1.00", List.of(payment("old", "1.00", T.minusSeconds(age)))));
        outcome(result, UNUSUAL_FREQUENCY, age <= WEEK ? Outcome.TRIGGERED : Outcome.NOT_TRIGGERED);
    }
    @Test void upperHistoryBoundaryIsExclusive() {
        var cfg = replace(config(UNUSUAL_FREQUENCY), UNUSUAL_FREQUENCY, new Frequency(WEEK, 1, HistoryEligibility.VALIDATED_SUBMISSIONS), 15);
        outcome(engine.evaluate(cfg, input("1.00", List.of(payment("other", "1.00", T)))), UNUSUAL_FREQUENCY, Outcome.NOT_TRIGGERED);
    }
    @ParameterizedTest @CsvSource({"20.00,NOT_TRIGGERED", "20.01,TRIGGERED"})
    void unusualAmountUsesExactStrictComparisonAgainstFiveCompletedPayments(String amount, Outcome expected) {
        var records = new ArrayList<Payment>(); for (int i = 0; i < 5; i++) records.add(payment("p" + i, "10.00", T.minusSeconds(i + 1)));
        outcome(evaluate(UNUSUAL_AMOUNT, input(amount, records)), UNUSUAL_AMOUNT, expected);
    }
    @Test void decimalMeanIsNotRoundedAndCurrentIsNotInBaseline() {
        var records = new ArrayList<Payment>(); for (int i = 0; i < 5; i++) records.add(payment("p" + i, i == 0 ? "0.01" : "0.00", T.minusSeconds(i + 1)));
        records.add(payment("current", "999.00", T.minusSeconds(1)));
        var result = evaluate(UNUSUAL_AMOUNT, input("0.01", records));
        outcome(result, UNUSUAL_AMOUNT, Outcome.TRIGGERED); assertEquals("5", factor(result, UNUSUAL_AMOUNT).observations().get("sampleCount"));
    }
    @Test void insufficientCompleteHistoryContributesZeroAndOtherRulesContinue() {
        var supplier = new Supplier("s", "supplier-1", T, SupplierStatus.ACTIVE);
        var result = engine.evaluate(config(UNUSUAL_AMOUNT, NEW_SUPPLIER), input("500.00", List.of(), supplier, List.of(), List.of()));
        outcome(result, UNUSUAL_AMOUNT, Outcome.INSUFFICIENT_HISTORY); outcome(result, NEW_SUPPLIER, Outcome.TRIGGERED);
        assertEquals(Status.COMPLETED, result.status()); assertEquals(15, result.finalScore());
    }
    @Test void baselineUsesCompletionTimeAndOnlyValidatedCompletedRecords() {
        var oldSubmission = new Payment("p", "p", "i", "I", "supplier-1", "INR", new BigDecimal("10.00"), T.minusSeconds(BASELINE + 100),
                T.minusSeconds(1), PaymentStatus.COMPLETE, true);
        var notComplete = new Payment("q", "q", "j", "J", "supplier-1", "INR", new BigDecimal("1.00"), T.minusSeconds(1), null, PaymentStatus.APPROVED, true);
        var cfg = replace(config(UNUSUAL_AMOUNT), UNUSUAL_AMOUNT, new Amount(BASELINE, 1, new BigDecimal("2.00"), ZeroBaselinePolicy.UNRESOLVED), 20);
        outcome(engine.evaluate(cfg, input("20.01", List.of(oldSubmission, notComplete))), UNUSUAL_AMOUNT, Outcome.TRIGGERED);
    }
    @ParameterizedTest @EnumSource(ZeroBaselinePolicy.class)
    void zeroBaselineRequiresAnExplicitPolicy(ZeroBaselinePolicy policy) {
        var records = new ArrayList<Payment>(); for (int i = 0; i < 5; i++) records.add(payment("p" + i, "0.00", T.minusSeconds(i + 1)));
        var cfg = replace(config(UNUSUAL_AMOUNT), UNUSUAL_AMOUNT, new Amount(BASELINE, 5, new BigDecimal("2.00"), policy), 20);
        var result = engine.evaluate(cfg, input("1.00", records));
        outcome(result, UNUSUAL_AMOUNT, switch (policy) { case UNRESOLVED -> Outcome.POLICY_UNRESOLVED; case INSUFFICIENT_HISTORY -> Outcome.INSUFFICIENT_HISTORY; case COMPARE_TO_ZERO -> Outcome.TRIGGERED; });
        if (policy == ZeroBaselinePolicy.UNRESOLVED) { assertNull(result.finalScore()); assertNull(result.rawTotal()); assertEquals(Status.POLICY_UNRESOLVED, result.status()); }
    }
    @ParameterizedTest @ValueSource(longs = {0, MONTH, MONTH + 1})
    void reactivationNeedsActualTransitionWithinInclusiveWindow(long age) {
        var transition = new StatusTransition("change", "supplier-1", SupplierStatus.INACTIVE, SupplierStatus.ACTIVE, T.minusSeconds(age));
        outcome(evaluate(RECENTLY_REACTIVATED_SUPPLIER, input("1.00", List.of(), oldSupplier(), List.of(transition), List.of())),
                RECENTLY_REACTIVATED_SUPPLIER, age <= MONTH ? Outcome.TRIGGERED : Outcome.NOT_TRIGGERED);
    }
    @Test void repeatedActiveStatusIsNotReactivation() {
        var transition = new StatusTransition("change", "supplier-1", SupplierStatus.ACTIVE, SupplierStatus.ACTIVE, T.minusSeconds(1));
        outcome(evaluate(RECENTLY_REACTIVATED_SUPPLIER, input("1.00", List.of(), oldSupplier(), List.of(transition), List.of())), RECENTLY_REACTIVATED_SUPPLIER, Outcome.NOT_TRIGGERED);
    }
    @ParameterizedTest @ValueSource(longs = {0, MONTH, MONTH + 1})
    void newSupplierUsesCreationAgeBoundary(long age) {
        outcome(evaluate(NEW_SUPPLIER, input("1.00", List.of(), new Supplier("s", "supplier-1", T.minusSeconds(age), SupplierStatus.ACTIVE), List.of(), List.of())),
                NEW_SUPPLIER, age <= MONTH ? Outcome.TRIGGERED : Outcome.NOT_TRIGGERED);
    }
    @Test void previousFlagsExcludeRecursiveFlagsCurrentAndRetriesAndContributeOnce() {
        var assessments = List.of(new PriorAssessment("a", "a", "supplier-1", T.minusSeconds(1), Set.of(STRUCTURING)),
                new PriorAssessment("b", "b", "supplier-1", T.minusSeconds(2), Set.of(NEW_SUPPLIER)),
                new PriorAssessment("c", "c", "supplier-1", T.minusSeconds(3), Set.of(PREVIOUS_RISK_FLAGS)),
                new PriorAssessment("d", "current", "supplier-1", T.minusSeconds(4), Set.of(STRUCTURING)),
                new PriorAssessment("e", "retry-current", "supplier-1", T.minusSeconds(5), Set.of(STRUCTURING)));
        var result = evaluate(PREVIOUS_RISK_FLAGS, input("1.00", List.of(), oldSupplier(), List.of(), assessments));
        outcome(result, PREVIOUS_RISK_FLAGS, Outcome.TRIGGERED); assertEquals(List.of("a", "b"), factor(result, PREVIOUS_RISK_FLAGS).matchedEvidenceIds());
    }
    @ParameterizedTest @ValueSource(longs = {BASELINE, BASELINE + 1})
    void previousFlagWindowBoundary(long age) {
        var a = new PriorAssessment("a", "a", "supplier-1", T.minusSeconds(age), Set.of(STRUCTURING));
        outcome(evaluate(PREVIOUS_RISK_FLAGS, input("1.00", List.of(), oldSupplier(), List.of(), List.of(a))), PREVIOUS_RISK_FLAGS,
                age <= BASELINE ? Outcome.TRIGGERED : Outcome.NOT_TRIGGERED);
    }
    @Test void nearDuplicateUsesExactAmountDifferentInvoiceNumberAndContributesOnce() {
        var result = evaluate(NEAR_DUPLICATE_INVOICE, input("10.00", List.of(payment("a", "10.0", T.minusSeconds(1)), payment("b", "10.00", T.minusSeconds(2)),
                payment("c", "10.01", T.minusSeconds(3)))));
        outcome(result, NEAR_DUPLICATE_INVOICE, Outcome.TRIGGERED); assertEquals(List.of("a", "b"), factor(result, NEAR_DUPLICATE_INVOICE).matchedEvidenceIds());
    }
    @Test void sameInvoiceNumberIsNotScoredAsNearDuplicate() {
        var a = new Payment("a", "a", "different-id", "INV-current", "supplier-1", "INR", new BigDecimal("10.00"), T.minusSeconds(1), null, PaymentStatus.APPROVED, true);
        outcome(evaluate(NEAR_DUPLICATE_INVOICE, input("10.00", List.of(a))), NEAR_DUPLICATE_INVOICE, Outcome.NOT_TRIGGERED);
    }
    @ParameterizedTest @EnumSource(HistoryEligibility.class)
    void nearDuplicateEligibilityIsAnExplicitChoice(HistoryEligibility eligibility) {
        var a = new Payment("a", "a", "i", "DIFFERENT", "supplier-1", "INR", new BigDecimal("10.00"), T.minusSeconds(1), null, PaymentStatus.APPROVED, true);
        var cfg = replace(config(NEAR_DUPLICATE_INVOICE), NEAR_DUPLICATE_INVOICE, new NearDuplicate(MONTH, eligibility, InvoiceNumberNormalization.EXACT), 25);
        outcome(engine.evaluate(cfg, input("10.00", List.of(a))), NEAR_DUPLICATE_INVOICE, eligibility == HistoryEligibility.UNRESOLVED ? Outcome.POLICY_UNRESOLVED
                : eligibility == HistoryEligibility.COMPLETED_PAYMENTS ? Outcome.NOT_TRIGGERED : Outcome.TRIGGERED);
    }
    @ParameterizedTest @EnumSource(InvoiceNumberNormalization.class)
    void invoiceNumberNormalizationIsNeverImplicit(InvoiceNumberNormalization policy) {
        var a = new Payment("a", "a", "i", " inv-CURRENT ", "supplier-1", "INR", new BigDecimal("10.00"), T.minusSeconds(1), null, PaymentStatus.APPROVED, true);
        var cfg = replace(config(NEAR_DUPLICATE_INVOICE), NEAR_DUPLICATE_INVOICE, new NearDuplicate(MONTH, HistoryEligibility.VALIDATED_SUBMISSIONS, policy), 25);
        outcome(engine.evaluate(cfg, input("10.00", List.of(a))), NEAR_DUPLICATE_INVOICE, policy == InvoiceNumberNormalization.UNRESOLVED ? Outcome.POLICY_UNRESOLVED
                : policy == InvoiceNumberNormalization.EXACT ? Outcome.TRIGGERED : Outcome.NOT_TRIGGERED);
    }
    @ParameterizedTest @EnumSource(value = Availability.class, names = {"UNAVAILABLE", "INCOMPLETE"})
    void missingHistoryIsTechnicalFailureNotInsufficientOrZeroRisk(Availability availability) {
        var input = input("1.00", List.of());
        var result = evaluate(UNUSUAL_AMOUNT, payments(input, new Evidence<>(availability, "s", "supplier-1", "INR", T, T.minusSeconds(BASELINE), List.of())));
        outcome(result, UNUSUAL_AMOUNT, Outcome.TECHNICAL_FAILURE); assertEquals(Status.TECHNICAL_FAILURE, result.status());
        assertNull(result.finalScore()); assertNull(result.riskBand()); assertNull(result.rawTotal());
    }
    @Test void incompleteCoverageOrWrongCutoffFailsClosed() {
        var input = input("1.00", List.of());
        for (var e : List.of(new Evidence<Payment>(Availability.COMPLETE, "s", "supplier-1", "INR", T, T.minusSeconds(WEEK - 1), List.of()),
                new Evidence<Payment>(Availability.COMPLETE, "s", "supplier-1", "INR", T.minusSeconds(1), T.minusSeconds(WEEK), List.of())))
            outcome(evaluate(UNUSUAL_FREQUENCY, payments(input, e)), UNUSUAL_FREQUENCY, Outcome.TECHNICAL_FAILURE);
    }
    @Test void disabledRulesDoNotRequireTheirDependencies() {
        var input = input("1.00", List.of());
        input = payments(input, new Evidence<>(Availability.UNAVAILABLE, "s", "supplier-1", "INR", T, T.minusSeconds(WEEK), List.of()));
        var result = engine.evaluate(config(), input);
        assertEquals(Status.COMPLETED, result.status()); assertEquals(BigInteger.ZERO, result.rawTotal()); assertEquals("LOW", result.riskBand());
        assertTrue(result.factors().stream().allMatch(f -> f.outcome() == Outcome.DISABLED));
    }
    @Test void rawTotalAboveCapIsPreservedAndInputSnapshotsAreRetained() {
        var list = List.of(payment("a", "10.00", T.minusSeconds(1)), payment("b", "10.00", T.minusSeconds(2)), payment("c", "10.00", T.minusSeconds(3)),
                payment("d", "10.00", T.minusSeconds(4)), payment("e", "10.00", T.minusSeconds(5)), payment("f", "40.00", T.minusSeconds(6)));
        var supplier = new Supplier("s", "supplier-1", T.minusSeconds(10), SupplierStatus.ACTIVE);
        var input = input("40.00", list, supplier, List.of(new StatusTransition("change", "supplier-1", SupplierStatus.INACTIVE, SupplierStatus.ACTIVE, T.minusSeconds(1))),
                List.of(new PriorAssessment("flag", "prior", "supplier-1", T.minusSeconds(1), Set.of(STRUCTURING))));
        var cfg = config(RuleCode.values()); var result = engine.evaluate(cfg, input);
        assertEquals(new BigInteger("135"), result.rawTotal()); assertEquals(100, result.finalScore()); assertEquals("HIGH", result.riskBand());
        assertSame(input, result.inputSnapshot()); assertSame(cfg, result.configurationSnapshot()); assertEquals("9", result.configurationRowVersion());
    }
    @ParameterizedTest @CsvSource({"29,LOW", "30,MEDIUM", "60,MEDIUM", "61,HIGH", "100,HIGH"})
    void configuredBandBoundariesAreInclusive(int weight, String band) {
        var cfg = replace(config(NEW_SUPPLIER), NEW_SUPPLIER, parameters(NEW_SUPPLIER), weight);
        var result = engine.evaluate(cfg, input("1.00", List.of(), new Supplier("s", "supplier-1", T, SupplierStatus.ACTIVE), List.of(), List.of()));
        assertEquals(weight, result.finalScore()); assertEquals(band, result.riskBand());
    }
    @Test void rawTotalCannotOverflowWhenWeightsAreLarge() {
        var cfg = replace(config(NEW_SUPPLIER, RECENTLY_REACTIVATED_SUPPLIER), NEW_SUPPLIER, parameters(NEW_SUPPLIER), Integer.MAX_VALUE);
        cfg = replace(cfg, RECENTLY_REACTIVATED_SUPPLIER, parameters(RECENTLY_REACTIVATED_SUPPLIER), Integer.MAX_VALUE);
        var input = input("1.00", List.of(), new Supplier("s", "supplier-1", T, SupplierStatus.ACTIVE),
                List.of(new StatusTransition("change", "supplier-1", SupplierStatus.INACTIVE, SupplierStatus.ACTIVE, T)), List.of());
        var result = engine.evaluate(cfg, input);
        assertEquals(new BigInteger("4294967294"), result.rawTotal()); assertEquals(100, result.finalScore());
    }
    @Test void frozenModelsDefensivelyCopyCollectionsAndResultsAreRepeatable() {
        var records = new ArrayList<>(List.of(payment("a", "1.00", T.minusSeconds(1))));
        var input = input("1.00", records); records.clear();
        var first = evaluate(UNUSUAL_FREQUENCY, input); assertEquals(first, evaluate(UNUSUAL_FREQUENCY, input));
        assertEquals(1, input.payments().records().size());
        assertThrows(UnsupportedOperationException.class, () -> first.factors().clear());
        assertThrows(UnsupportedOperationException.class, () -> input.payments().records().clear());
        assertThrows(UnsupportedOperationException.class, () -> first.configurationSnapshot().approvalBoundaries().clear());
    }
}
