package com.ofss.risk.persistence;

import com.ofss.risk.engine.RiskConfiguration;
import com.ofss.risk.contract.RuleParameters;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Versioned local format, not a publisher's hash contract. No floating-point conversion. */
public final class CanonicalAcceptance {
    public static final String HASH_FORMAT = "risk-canonical-v1";
    public static final String SNAPSHOT_FORMAT = "risk-engine-v1";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    public record Document(String json, String hash) {}

    public Document configuration(RiskConfiguration config) {
        var body = recordMap(config);
        body.put("versionId", Long.toString(PersistenceValues.id(config.versionId())));
        body.put("rowVersion", Long.toString(Long.parseLong(config.rowVersion())));
        body.put("approvalBoundaries", map(config.approvalBoundaries().entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).flatMap(e -> java.util.stream.Stream.of(
                        e.getKey().toString(), e.getValue().setScale(2).toPlainString())).toArray()));
        body.put("rules", config.rules().stream().sorted(Comparator.comparingInt(RiskConfiguration.Rule::evaluationOrder)
                .thenComparing(r -> r.code().name())).map(rule -> {
                    var r = recordMap(rule);
                    var params = recordMap(rule.parameters());
                    if (rule.parameters() instanceof RuleParameters.Structuring p)
                        params.put("approvalBoundaryLevels", p.approvalBoundaryLevels().stream().sorted().toList());
                    if (rule.parameters() instanceof RuleParameters.PreviousFlags p)
                        params.put("qualifyingRuleCodes", p.qualifyingRuleCodes().stream().map(Enum::name).sorted().toList());
                    r.put("parameters", params);
                    return r;
                }).toList());
        body.put("bands", config.bands().stream().sorted(Comparator.comparingInt(RiskConfiguration.Band::minimum)
                .thenComparing(RiskConfiguration.Band::code)).map(CanonicalAcceptance::recordMap).toList());
        return document(map("hashFormatVersion", HASH_FORMAT, "snapshotFormatVersion", SNAPSHOT_FORMAT, "configuration", body));
    }

    public Document acceptance(AcceptanceRequest request, String configId, String configRowVersion, String configHash) {
        var payment = recordMap(request.current());
        payment.put("amount", request.current().amount().toPlainString()); // exact scale 2
        payment.put("supplierId", Long.toString(PersistenceValues.id(request.current().supplierId())));
        payment.put("invoiceId", Long.toString(PersistenceValues.id(request.current().invoiceId())));
        return document(map("hashFormatVersion", HASH_FORMAT, "submission", recordMap(request.identity()),
                "paymentId", Long.toString(request.paymentId()), "requesterId", Long.toString(request.requesterId()),
                "validationReference", request.validationReference(), "validationRevision", request.validationRevision(),
                "current", payment, "cutoff", request.cutoff().toString(),
                "currentAndRetrySubmissionIds", request.currentAndRetrySubmissionIds().stream().sorted().toList(),
                "evaluatorVersion", request.evaluatorVersion(), "configurationVersionId", configId,
                "configurationRowVersion", configRowVersion, "configurationHash", configHash));
    }

    static String hash(String json) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    static Map<?, ?> read(String json) { return JSON.readValue(json, Map.class); }
    private static Document document(Map<String, Object> map) {
        String json = JSON.writeValueAsString(map);
        return new Document(json, hash(json));
    }
    private static TreeMap<String, Object> map(Object... pairs) {
        var result = new TreeMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }
    private static TreeMap<String, Object> recordMap(Object record) {
        var result = new TreeMap<String, Object>();
        for (var component : record.getClass().getRecordComponents()) {
            try { result.put(component.getName(), normalize(component.getAccessor().invoke(record))); }
            catch (ReflectiveOperationException e) { throw new IllegalStateException("Unsupported canonical record"); }
        }
        return result;
    }
    private static Object normalize(Object value) {
        if (value == null || value instanceof Boolean || value instanceof Integer || value instanceof Long) return value;
        if (value instanceof String s) { PersistenceValues.unicode(s); return s; }
        if (value instanceof Enum<?> e) return e.name();
        if (value instanceof BigDecimal d) return d.stripTrailingZeros().toPlainString();
        if (value instanceof Instant i) { PersistenceValues.timestamp(i); return i.toString(); }
        if (value instanceof Collection<?> c) return c.stream().map(CanonicalAcceptance::normalize).toList();
        if (value instanceof Map<?, ?> m) {
            var result = new TreeMap<String, Object>();
            m.forEach((k, v) -> result.put(k.toString(), normalize(v)));
            return result;
        }
        if (value.getClass().isRecord()) return recordMap(value);
        throw new IllegalArgumentException("Unsupported canonical value");
    }
}
