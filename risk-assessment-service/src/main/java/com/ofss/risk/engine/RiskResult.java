package com.ofss.risk.engine;

import com.ofss.risk.contract.RuleCode;
import java.math.BigInteger;
import java.time.Instant;
import java.util.*;

public record RiskResult(Status status, String submissionId, String configurationVersionId,
        String configurationRowVersion, String configurationContentHash, Instant cutoff,
        BigInteger rawTotal, Integer finalScore, String riskBand, List<Factor> factors,
        RiskConfiguration configurationSnapshot, RiskInput inputSnapshot) {
    public RiskResult {
        Objects.requireNonNull(status); Objects.requireNonNull(cutoff);
        Objects.requireNonNull(configurationSnapshot); Objects.requireNonNull(inputSnapshot);
        factors = List.copyOf(factors);
        if (status == Status.COMPLETED) {
            if (rawTotal == null || rawTotal.signum() < 0 || finalScore == null || finalScore < 0
                    || finalScore > configurationSnapshot.maxFinalScore() || riskBand == null)
                throw new IllegalArgumentException("Completed results require a valid score and band");
        } else if (rawTotal != null || finalScore != null || riskBand != null) {
            throw new IllegalArgumentException("Failed or unresolved evaluations must not expose final scores");
        }
    }
    public enum Status { COMPLETED, TECHNICAL_FAILURE, POLICY_UNRESOLVED }
    public enum Outcome { TRIGGERED, NOT_TRIGGERED, DISABLED, INSUFFICIENT_HISTORY, TECHNICAL_FAILURE, POLICY_UNRESOLVED }
    public record Factor(RuleCode ruleCode, Outcome outcome, int configuredWeight, int contribution,
            String explanation, List<String> matchedEvidenceIds, Map<String, String> observations) {
        public Factor {
            Objects.requireNonNull(ruleCode); Objects.requireNonNull(outcome); RiskConfiguration.required(explanation);
            if (configuredWeight < 0 || contribution != (outcome == Outcome.TRIGGERED ? configuredWeight : 0))
                throw new IllegalArgumentException("A triggered rule contributes its weight exactly once");
            matchedEvidenceIds = List.copyOf(matchedEvidenceIds);
            observations = Collections.unmodifiableSortedMap(new TreeMap<>(Map.copyOf(observations)));
        }
    }
}
