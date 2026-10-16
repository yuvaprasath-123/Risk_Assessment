package com.ofss.risk.engine;

import com.ofss.risk.contract.*;
import java.math.BigDecimal;
import java.util.*;

/** Caller supplies a frozen snapshot; no active-version lookup or demonstration seeding occurs here. */
public record RiskConfiguration(String versionId, String rowVersion, String contentHash,
        String currencyCode, int maxFinalScore, List<Rule> rules, List<Band> bands,
        Map<Integer, BigDecimal> approvalBoundaries) {
    public RiskConfiguration {
        required(versionId); required(rowVersion); required(contentHash);
        try {
            if (!versionId.matches("[0-9]+") || !rowVersion.matches("[0-9]+")
                    || Long.parseLong(versionId) < 1 || Long.parseLong(rowVersion) < 0) throw new IllegalArgumentException("Invalid configuration identity");
        } catch (NumberFormatException exception) { throw new IllegalArgumentException("Configuration identity exceeds Java Long"); }
        if (!"INR".equals(currencyCode) || maxFinalScore < 1) throw new IllegalArgumentException("INR and a positive score cap are required");
        rules = List.copyOf(rules); bands = List.copyOf(bands); approvalBoundaries = Map.copyOf(approvalBoundaries);
        if (rules.stream().map(Rule::code).distinct().count() != rules.size()
                || !rules.stream().map(Rule::code).collect(java.util.stream.Collectors.toSet()).equals(EnumSet.allOf(RuleCode.class)))
            throw new IllegalArgumentException("Exactly one entry for each supported rule is required");
        if (rules.stream().map(Rule::evaluationOrder).distinct().count() != rules.size())
            throw new IllegalArgumentException("Rule evaluation orders must be unique");
        var sorted = bands.stream().sorted(Comparator.comparingInt(Band::minimum)).toList();
        if (sorted.size() != 3 || !sorted.stream().map(Band::code).collect(java.util.stream.Collectors.toSet()).equals(Set.of("LOW", "MEDIUM", "HIGH")))
            throw new IllegalArgumentException("LOW, MEDIUM and HIGH bands are required");
        long next = 0;
        for (int i = 0; i < sorted.size(); i++) {
            var band = sorted.get(i);
            if (!band.code().equals(List.of("LOW", "MEDIUM", "HIGH").get(i)) || band.minimum() != next
                    || (i < 2 && band.maximum() == null)) throw new IllegalArgumentException("Bands must be ordered and contiguous from zero");
            next = band.maximum() == null ? Long.MAX_VALUE : (long) band.maximum() + 1;
        }
        if (next <= maxFinalScore) throw new IllegalArgumentException("Bands must cover the configured cap");
        approvalBoundaries.forEach((level, amount) -> {
            if (level < 2 || level > 3) throw new IllegalArgumentException("Selected approval boundaries must be level 2 or 3");
            RiskInput.money(amount);
            if (amount.signum() == 0) throw new IllegalArgumentException("Approval boundaries must be positive");
        });
        if (approvalBoundaries.containsKey(2) && approvalBoundaries.containsKey(3)
                && approvalBoundaries.get(2).compareTo(approvalBoundaries.get(3)) >= 0)
            throw new IllegalArgumentException("Approval boundaries must increase with authority level");
        for (var rule : rules) {
            if (rule.enabled() && rule.parameters() instanceof RuleParameters.Structuring p
                    && !approvalBoundaries.keySet().containsAll(p.approvalBoundaryLevels()))
                throw new IllegalArgumentException("Selected approval boundary is missing from the frozen configuration");
        }
    }
    public record Rule(RuleCode code, boolean enabled, int weight, int evaluationOrder,
            String parameterSchemaVersion, RuleParameters parameters) {
        public Rule {
            Objects.requireNonNull(code); Objects.requireNonNull(parameters);
            if (weight < 0 || evaluationOrder < 1 || evaluationOrder > 99999
                    || !RuleParameterContracts.VERSION.equals(parameterSchemaVersion) || !RuleParameterContracts.matches(code, parameters))
                throw new IllegalArgumentException("Invalid rule configuration/type");
        }
        public static Rule from(RuleCode code, boolean enabled, int weight, int order, String schema, Map<String, ?> json) {
            return new Rule(code, enabled, weight, order, schema, RuleParameterContracts.parse(code, schema, json));
        }
    }
    public record Band(String code, int minimum, Integer maximum) {
        public Band {
            required(code);
            if (minimum < 0 || (maximum != null && maximum < minimum)) throw new IllegalArgumentException("Invalid band bounds");
        }
    }
    static void required(String value) { if (value == null || value.isBlank()) throw new IllegalArgumentException("Required text is missing"); }
}
