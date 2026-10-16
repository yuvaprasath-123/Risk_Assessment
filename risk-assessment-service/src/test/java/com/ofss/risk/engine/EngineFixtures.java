package com.ofss.risk.engine;

import com.ofss.risk.contract.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static com.ofss.risk.engine.RiskInput.*;
import static com.ofss.risk.contract.RuleParameters.*;

/** Approved demonstration policies; monetary boundaries remain TEST-ONLY, never seeds or active rows. */
final class EngineFixtures {
    static final Instant T = Instant.parse("2026-10-14T00:00:00Z");
    static final long WEEK = 604800, MONTH = 2592000, BASELINE = 7776000;
    static final Map<RuleCode, Integer> WEIGHTS = Map.of(RuleCode.STRUCTURING, 35, RuleCode.UNUSUAL_FREQUENCY, 15,
            RuleCode.UNUSUAL_AMOUNT, 20, RuleCode.RECENTLY_REACTIVATED_SUPPLIER, 15, RuleCode.PREVIOUS_RISK_FLAGS, 10,
            RuleCode.NEW_SUPPLIER, 15, RuleCode.NEAR_DUPLICATE_INVOICE, 25);
    static RuleParameters parameters(RuleCode code) {
        return switch (code) {
            case STRUCTURING -> new Structuring(WEEK, 2, List.of(2, 3), HistoryEligibility.VALIDATED_SUBMISSIONS);
            case UNUSUAL_FREQUENCY -> new Frequency(WEEK, 3, HistoryEligibility.VALIDATED_SUBMISSIONS);
            case UNUSUAL_AMOUNT -> new Amount(BASELINE, 5, new BigDecimal("2.00"), ZeroBaselinePolicy.INSUFFICIENT_HISTORY);
            case RECENTLY_REACTIVATED_SUPPLIER -> new Reactivation(MONTH);
            case PREVIOUS_RISK_FLAGS -> new PreviousFlags(BASELINE, 1, List.of(RuleCode.STRUCTURING, RuleCode.UNUSUAL_FREQUENCY,
                    RuleCode.UNUSUAL_AMOUNT, RuleCode.RECENTLY_REACTIVATED_SUPPLIER, RuleCode.NEW_SUPPLIER, RuleCode.NEAR_DUPLICATE_INVOICE));
            case NEW_SUPPLIER -> new NewSupplier(MONTH);
            case NEAR_DUPLICATE_INVOICE -> new NearDuplicate(MONTH, HistoryEligibility.VALIDATED_SUBMISSIONS, InvoiceNumberNormalization.EXACT);
        };
    }
    static RiskConfiguration config(RuleCode... enabled) {
        var set = new HashSet<>(Arrays.asList(enabled));
        var rules = Arrays.stream(RuleCode.values()).map(c -> new RiskConfiguration.Rule(c, set.contains(c), WEIGHTS.get(c), c.ordinal() + 1,
                RuleParameterContracts.VERSION, parameters(c))).toList();
        return new RiskConfiguration("1", "9", "frozen-test-snapshot", "INR", 100, rules,
                List.of(new RiskConfiguration.Band("LOW", 0, 29), new RiskConfiguration.Band("MEDIUM", 30, 60), new RiskConfiguration.Band("HIGH", 61, 100)),
                Map.of(2, new BigDecimal("100.00"), 3, new BigDecimal("1000.00"))); // Test-only amount boundaries.
    }
    static RiskConfiguration replace(RiskConfiguration config, RuleCode code, RuleParameters parameters, int weight) {
        return new RiskConfiguration(config.versionId(), config.rowVersion(), config.contentHash(), config.currencyCode(), config.maxFinalScore(),
                config.rules().stream().map(r -> r.code() == code ? new RiskConfiguration.Rule(code, r.enabled(), weight, r.evaluationOrder(),
                        r.parameterSchemaVersion(), parameters) : r).toList(), config.bands(), config.approvalBoundaries());
    }
    static Payment payment(String id, String amount, Instant time) {
        return new Payment(id, id, "invoice-" + id, "INV-" + id, "supplier-1", "INR", new BigDecimal(amount), time, time, PaymentStatus.COMPLETE, true);
    }
    static <T> Evidence<T> evidence(List<T> records) {
        return new Evidence<>(Availability.COMPLETE, "snapshot", "supplier-1", "INR", T, T.minusSeconds(3155760000L), records);
    }
    static Supplier oldSupplier() { return new Supplier("supplier-evidence", "supplier-1", T.minusSeconds(MONTH + 1), SupplierStatus.ACTIVE); }
    static RiskInput input(String amount, List<Payment> payments) {
        return input(amount, payments, oldSupplier(), List.of(), List.of());
    }
    static RiskInput input(String amount, List<Payment> payments, Supplier supplier, List<StatusTransition> transitions, List<PriorAssessment> previous) {
        return new RiskInput(payment("current", amount, T), T, Set.of("current", "retry-current"), evidence(payments),
                evidence(List.of(supplier)), evidence(transitions), evidence(previous));
    }
    static RiskInput payments(RiskInput input, Evidence<Payment> payments) {
        return new RiskInput(input.current(), input.cutoff(), input.currentAndRetrySubmissionIds(), payments, input.supplier(), input.supplierTransitions(), input.previousAssessments());
    }
}
