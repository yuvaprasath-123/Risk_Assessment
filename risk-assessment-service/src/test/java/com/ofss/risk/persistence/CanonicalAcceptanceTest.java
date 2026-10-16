package com.ofss.risk.persistence;

import com.ofss.risk.contract.*;
import com.ofss.risk.engine.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class CanonicalAcceptanceTest {
    private final CanonicalAcceptance canonical = new CanonicalAcceptance();
    private CanonicalAcceptance.Document accepted(AcceptanceRequest r) {
        var c = canonical.configuration(r.configuration());
        return canonical.acceptance(r, "9007199254740993", "9", c.hash());
    }
    @Test void exactNumbersNullUnicodeAndNanosecondsArePreserved() {
        var r = AcceptanceFixtures.request(AcceptanceFixtures.config());
        var a = accepted(r); var c = canonical.configuration(r.configuration());
        assertTrue(a.json().contains("\"paymentId\":\"9223372036854775807\""));
        assertTrue(a.json().contains("9999999999999.99"));
        assertTrue(a.json().contains("2026-10-14T00:00:00.123456789Z"));
        assertTrue(a.json().contains("\"validationRevision\":null"));
        assertTrue(c.json().contains("\"meanMultiplier\":\"1.0000000000000001\""));
        assertTrue(c.json().contains("\"versionId\":\"9007199254740993\""));
        assertEquals(r.current().invoiceNumber(), ((Map<?, ?>) CanonicalAcceptance.read(a.json()).get("current")).get("invoiceNumber"));
        assertTrue(a.hash().matches("[0-9a-f]{64}"));
        assertNotEquals(a.hash(), c.hash());
        assertNotEquals(r.configuration().contentHash(), c.hash());
    }
    @Test void semanticallyUnorderedCollectionsAndExactDecimalScalesHaveStableHashes() {
        var c = AcceptanceFixtures.config();
        var rules = new ArrayList<>(c.rules()); Collections.reverse(rules);
        rules.replaceAll(r -> r.code() == RuleCode.STRUCTURING ? new RiskConfiguration.Rule(r.code(), r.enabled(), r.weight(), r.evaluationOrder(), r.parameterSchemaVersion(),
                new RuleParameters.Structuring(604800, 2, List.of(3, 2), RuleParameters.HistoryEligibility.VALIDATED_SUBMISSIONS)) : r);
        rules.replaceAll(r -> r.code() == RuleCode.PREVIOUS_RISK_FLAGS ? new RiskConfiguration.Rule(r.code(), r.enabled(), r.weight(), r.evaluationOrder(), r.parameterSchemaVersion(),
                new RuleParameters.PreviousFlags(7776000, 1, Arrays.stream(RuleCode.values()).filter(code -> code != RuleCode.PREVIOUS_RISK_FLAGS).sorted(Comparator.reverseOrder()).toList())) : r);
        var same = new RiskConfiguration("09007199254740993", "09", c.contentHash(), c.currencyCode(), c.maxFinalScore(), rules,
                List.of(c.bands().get(2), c.bands().get(0), c.bands().get(1)), Map.of(3, new BigDecimal("456.000"), 2, new BigDecimal("123")));
        assertEquals(canonical.configuration(c), canonical.configuration(same));
        var r = AcceptanceFixtures.request(c);
        assertEquals(accepted(r), accepted(AcceptanceFixtures.copy(r, same, r.paymentId(), null, r.cutoff(),
                new LinkedHashSet<>(List.of("retry-alias", "opaque-engine-id")), "new transport correlation")));
    }
    @ParameterizedTest @ValueSource(strings = {"WEIGHT", "WINDOW", "CAP", "SOURCE_HASH", "VERSION", "ROW_VERSION"})
    void everyConfigurationChangeChangesLocalHash(String field) {
        var c = AcceptanceFixtures.config(); var rules = new ArrayList<>(c.rules());
        var r = rules.get(0);
        if (field.equals("WEIGHT")) rules.set(0, new RiskConfiguration.Rule(r.code(), r.enabled(), r.weight() + 1, r.evaluationOrder(), r.parameterSchemaVersion(), r.parameters()));
        if (field.equals("WINDOW")) rules.set(0, new RiskConfiguration.Rule(r.code(), r.enabled(), r.weight(), r.evaluationOrder(), r.parameterSchemaVersion(),
                new RuleParameters.Structuring(604801, 2, List.of(2, 3), RuleParameters.HistoryEligibility.VALIDATED_SUBMISSIONS)));
        var changed = new RiskConfiguration(field.equals("VERSION") ? "2" : c.versionId(), field.equals("ROW_VERSION") ? "10" : c.rowVersion(),
                field.equals("SOURCE_HASH") ? "changed source claim" : c.contentHash(), "INR", field.equals("CAP") ? 99 : 100, rules, c.bands(), c.approvalBoundaries());
        assertNotEquals(canonical.configuration(c).hash(), canonical.configuration(changed).hash());
    }
    @Test void cutoffExclusionsAcceptedFactsAndValidationRevisionAreFrozen() {
        var r = AcceptanceFixtures.request(AcceptanceFixtures.config());
        for (var changed : List.of(
                AcceptanceFixtures.copy(r, r.configuration(), 7, null, r.cutoff(), r.currentAndRetrySubmissionIds(), null),
                AcceptanceFixtures.copy(r, r.configuration(), r.paymentId(), "revision2", r.cutoff(), r.currentAndRetrySubmissionIds(), null),
                AcceptanceFixtures.copy(r, r.configuration(), r.paymentId(), null, r.cutoff().plusNanos(1), r.currentAndRetrySubmissionIds(), null),
                AcceptanceFixtures.copy(r, r.configuration(), r.paymentId(), null, r.cutoff(), Set.of("opaque-engine-id"), null)))
            assertNotEquals(accepted(r).hash(), accepted(changed).hash());
    }
    @Test void namespacedIdentityIsOpaqueAndPaymentIdIsSeparate() {
        assertNotEquals(new SubmissionIdentity("a", "b:c"), new SubmissionIdentity("a:b", "c"));
        assertNotEquals(new SubmissionIdentity("a", "Key"), new SubmissionIdentity("a", "key"));
        assertDoesNotThrow(() -> new SubmissionIdentity("\uD83D\uDE00".repeat(64), "x"));
        assertThrows(IllegalArgumentException.class, () -> new SubmissionIdentity("\uD83D\uDE00".repeat(65), "x"));
        assertThrows(IllegalArgumentException.class, () -> new SubmissionIdentity("x", "\uD800"));
    }
}
