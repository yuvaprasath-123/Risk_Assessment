package com.ofss.risk.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class PersistenceValuesTest {
    @ParameterizedTest @ValueSource(strings = {"0", "-1", "1.5", "1e2", "9223372036854775808", "supplier-1", ""})
    void externalIdsMustBeExactPositiveLongs(String id) { assertThrows(IllegalArgumentException.class, () -> PersistenceValues.id(id)); }
    @Test void maximumLongAndZeroRevisionAreExact() {
        assertEquals(Long.MAX_VALUE, PersistenceValues.id("9223372036854775807"));
        assertEquals(Long.MAX_VALUE, PersistenceValues.number(new BigDecimal("9223372036854775807"), true));
        assertEquals(0, PersistenceValues.number(BigDecimal.ZERO, false));
    }
    @Test void oracleTimestampDomainRetainsNanosecondsAndRejectsOutOfRangeInstants() {
        assertDoesNotThrow(() -> PersistenceValues.timestamp(Instant.parse("9999-12-31T23:59:59.999999999Z")));
        assertThrows(IllegalArgumentException.class, () -> PersistenceValues.timestamp(Instant.parse("+10000-01-01T00:00:00Z")));
        assertThrows(IllegalArgumentException.class, () -> PersistenceValues.timestamp(Instant.parse("0000-01-01T00:00:00Z")));
    }
    @Test void nullableTextIsNullOrNonblankWithCodePointLimits() {
        assertDoesNotThrow(() -> PersistenceValues.optionalText(null, 128));
        assertDoesNotThrow(() -> PersistenceValues.text("\uD83D\uDE00".repeat(128), 128));
        assertThrows(IllegalArgumentException.class, () -> PersistenceValues.optionalText("", 128));
        assertThrows(IllegalArgumentException.class, () -> PersistenceValues.text("x".repeat(129), 128));
        assertThrows(IllegalArgumentException.class, () -> PersistenceValues.text("\uDC00", 128));
    }
    @Test void acceptanceRequiresUpstreamPassAndAnExclusionOfCurrentEngineIdentity() {
        var r = AcceptanceFixtures.request(AcceptanceFixtures.config());
        assertThrows(IllegalArgumentException.class, () -> AcceptanceFixtures.copy(r, r.configuration(), r.paymentId(), null,
                r.cutoff(), java.util.Set.of("unrelated"), null));
        var p = r.current();
        var invalid = new com.ofss.risk.engine.RiskInput.Payment(p.evidenceId(), p.submissionId(), p.invoiceId(), p.invoiceNumber(), p.supplierId(), p.currencyCode(),
                p.amount(), p.submittedAt(), p.completedAt(), p.statusAsOfCutoff(), false);
        assertThrows(IllegalArgumentException.class, () -> new AcceptanceRequest(r.identity(), r.paymentId(), r.requesterId(), r.validationReference(), null,
                invalid, r.cutoff(), r.currentAndRetrySubmissionIds(), r.evaluatorVersion(), r.configuration(), null));
    }
}
