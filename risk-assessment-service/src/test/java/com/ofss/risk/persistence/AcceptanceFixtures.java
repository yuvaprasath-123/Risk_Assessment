package com.ofss.risk.persistence;

import com.ofss.risk.contract.*;
import com.ofss.risk.engine.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static com.ofss.risk.contract.RuleParameters.*;

/** Arbitrary TEST data only; no seed or active configuration. */
final class AcceptanceFixtures {
    static final Instant CUTOFF = Instant.parse("2026-10-14T00:00:00.123456789Z");
    static final Instant CREATED = CUTOFF.plusNanos(101);
    static RiskConfiguration config() {
        var rules = Arrays.stream(RuleCode.values()).map(c -> new RiskConfiguration.Rule(c, true,
                c.ordinal() + 1, c.ordinal() + 1, RuleParameterContracts.VERSION, parameters(c))).toList();
        return new RiskConfiguration("9007199254740993", "9", "unverified publisher provenance", "INR", 100,
                rules, List.of(new RiskConfiguration.Band("LOW", 0, 29), new RiskConfiguration.Band("MEDIUM", 30, 60),
                        new RiskConfiguration.Band("HIGH", 61, 100)), Map.of(2, new BigDecimal("123.00"), 3, new BigDecimal("456.00")));
    }
    static RuleParameters parameters(RuleCode c) {
        return switch (c) {
            case STRUCTURING -> new Structuring(604800, 2, List.of(2, 3), HistoryEligibility.VALIDATED_SUBMISSIONS);
            case UNUSUAL_FREQUENCY -> new Frequency(604800, 3, HistoryEligibility.VALIDATED_SUBMISSIONS);
            case UNUSUAL_AMOUNT -> new Amount(7776000, 5, new BigDecimal("1.0000000000000001"), ZeroBaselinePolicy.INSUFFICIENT_HISTORY);
            case RECENTLY_REACTIVATED_SUPPLIER -> new Reactivation(2592000);
            case PREVIOUS_RISK_FLAGS -> new PreviousFlags(7776000, 1, Arrays.stream(RuleCode.values()).filter(r -> r != RuleCode.PREVIOUS_RISK_FLAGS).toList());
            case NEW_SUPPLIER -> new NewSupplier(2592000);
            case NEAR_DUPLICATE_INVOICE -> new NearDuplicate(2592000, HistoryEligibility.VALIDATED_SUBMISSIONS, InvoiceNumberNormalization.EXACT);
        };
    }
    static AcceptanceRequest request(RiskConfiguration config) {
        return new AcceptanceRequest(new SubmissionIdentity("developer1", "stable-submission"), 9223372036854775807L, 42,
                "PASS-evidence", null, new RiskInput.Payment("current-evidence", "opaque-engine-id", "123", "INV-\uD83D\uDE00-\"\\\n",
                        "456", "INR", new BigDecimal("9999999999999.99"), CUTOFF.minusNanos(1), null,
                        RiskInput.PaymentStatus.PENDING_APPROVAL, true), CUTOFF, Set.of("opaque-engine-id", "retry-alias"), "engine-build-1", config, null);
    }
    static AcceptanceRequest copy(AcceptanceRequest r, RiskConfiguration config, long paymentId, String validationRevision,
            Instant cutoff, Set<String> exclusions, String correlation) {
        return new AcceptanceRequest(r.identity(), paymentId, r.requesterId(), r.validationReference(), validationRevision,
                r.current(), cutoff, exclusions, r.evaluatorVersion(), config, correlation);
    }
}
