package com.ofss.risk.persistence;

import com.ofss.risk.engine.RiskConfiguration;
import com.ofss.risk.engine.RiskInput;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Synthetic data only. No external payment/configuration record is read or modified. */
final class OracleAcceptanceFixtures {
    static final String PREFIX = "D2_RISK_TEST_";
    static final String EVALUATOR = "oracle-acceptance-test-v1";
    static final Set<String> KEYS = Set.of("creation", "retries", "conflicts", "roundtrip", "rollback-parent",
            "rollback-snapshot", "rollback-associated", "concurrency");
    final String namespace = PREFIX + UUID.randomUUID();
    final Instant cutoff = Instant.now().minusSeconds(2).truncatedTo(ChronoUnit.SECONDS).plusNanos(123456789);
    final Instant created = cutoff.plusNanos(101);
    final Clock clock = Clock.fixed(created, ZoneOffset.UTC);

    static void requireNamespace(String namespace) {
        if (namespace == null || !namespace.startsWith(PREFIX)) throw new IllegalArgumentException("Exact synthetic fixture namespace required");
        String id = namespace.substring(PREFIX.length());
        try {
            if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException("Invalid fixture UUID");
        } catch (IllegalArgumentException e) { throw new IllegalArgumentException("Exact synthetic fixture namespace required"); }
    }
    AcceptanceRequest request(String key) {
        if (!KEYS.contains(key)) throw new IllegalArgumentException("Unknown synthetic scenario key");
        String engineId = namespace + "/" + key;
        var base = AcceptanceFixtures.config();
        var config = new RiskConfiguration(base.versionId(), Long.toString(Long.MAX_VALUE),
                "ORACLE_TEST:" + namespace + " ಕನ್ನಡ हिन्दी தமிழ் 日本語 😀 \"\\\n".repeat(2048),
                base.currencyCode(), base.maxFinalScore(), base.rules(), base.bands(), base.approvalBoundaries());
        var current = new RiskInput.Payment("synthetic-evidence/" + engineId, engineId, "9007199254740993",
                "SYNTHETIC-INVOICE ಕನ್ನಡ 😀 \"\\\n", "9223372036854775806", "INR", new BigDecimal("9999999999999.99"),
                cutoff.minusNanos(1), null, RiskInput.PaymentStatus.PENDING_APPROVAL, true);
        return new AcceptanceRequest(new SubmissionIdentity(namespace, key), Long.MAX_VALUE, 9007199254740993L,
                "synthetic-pass/" + key, null, current, cutoff, Set.of(engineId, engineId + "/retry"), EVALUATOR, config, "synthetic-correlation/" + key);
    }
}
