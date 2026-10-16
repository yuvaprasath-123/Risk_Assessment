package com.ofss.risk.engine;

import com.ofss.risk.contract.*;
import java.math.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import static com.ofss.risk.contract.RuleParameters.*;
import static com.ofss.risk.engine.RiskInput.*;
import static com.ofss.risk.engine.RiskResult.*;

/** Stateless, independent rule evaluation. No clocks, network, database, lifecycle mutation or once-only enforcement. */
public final class RiskScoringEngine {
    public RiskResult evaluate(RiskConfiguration configuration, RiskInput input) {
        Objects.requireNonNull(configuration); Objects.requireNonNull(input);
        var factors = new ArrayList<Factor>();
        for (var rule : configuration.rules().stream().sorted(Comparator.comparingInt(RiskConfiguration.Rule::evaluationOrder)
                .thenComparing(RiskConfiguration.Rule::code)).toList()) {
            if (!rule.enabled()) { factors.add(factor(rule, Outcome.DISABLED, "Rule is disabled", List.of(), Map.of())); continue; }
            try {
                if (!"INR".equals(input.current().currencyCode())) throw new EvidenceFailure("Current payment currency must be INR");
                factors.add(evaluateRule(configuration, input, rule));
            } catch (EvidenceFailure failure) {
                factors.add(factor(rule, Outcome.TECHNICAL_FAILURE, failure.getMessage(), List.of(), Map.of()));
            } catch (DateTimeException | ArithmeticException exception) {
                factors.add(factor(rule, Outcome.TECHNICAL_FAILURE, "Evaluation evidence or time range cannot be represented exactly", List.of(), Map.of()));
            }
        }
        Status status = factors.stream().anyMatch(f -> f.outcome() == Outcome.TECHNICAL_FAILURE) ? Status.TECHNICAL_FAILURE
                : factors.stream().anyMatch(f -> f.outcome() == Outcome.POLICY_UNRESOLVED) ? Status.POLICY_UNRESOLVED : Status.COMPLETED;
        BigInteger evaluatedTotal = factors.stream().map(f -> BigInteger.valueOf(f.contribution())).reduce(BigInteger.ZERO, BigInteger::add);
        BigInteger raw = status == Status.COMPLETED ? evaluatedTotal : null;
        Integer score = raw == null ? null : raw.min(BigInteger.valueOf(configuration.maxFinalScore())).intValueExact();
        String band = score == null ? null : configuration.bands().stream()
                .filter(b -> score >= b.minimum() && (b.maximum() == null || score <= b.maximum())).findFirst().orElseThrow().code();
        return new RiskResult(status, input.current().submissionId(), configuration.versionId(), configuration.rowVersion(),
                configuration.contentHash(), input.cutoff(), raw, score, band, factors, configuration, input);
    }

    private Factor evaluateRule(RiskConfiguration config, RiskInput input, RiskConfiguration.Rule rule) {
        Instant start = input.cutoff().minusSeconds(rule.parameters().windowSeconds());
        return switch (rule.parameters()) {
            case Structuring p -> structuring(config, input, rule, p, start);
            case Frequency p -> frequency(input, rule, p, start);
            case Amount p -> amount(input, rule, p, start);
            case Reactivation ignored -> reactivation(input, rule, start);
            case PreviousFlags p -> previous(input, rule, p, start);
            case NewSupplier ignored -> newSupplier(input, rule, start);
            case NearDuplicate p -> nearDuplicate(input, rule, p, start);
        };
    }
    private Factor structuring(RiskConfiguration config, RiskInput input, RiskConfiguration.Rule rule, Structuring p, Instant start) {
        var history = payments(input, start);
        if (p.historyEligibility() == HistoryEligibility.UNRESOLVED) return unresolved(rule, "Structuring history eligibility is unresolved");
        var candidates = new ArrayList<>(history.stream().filter(v -> eligible(v, p.historyEligibility()) && within(v.submittedAt(), start, input.cutoff())).toList());
        candidates.add(input.current());
        var ids = new TreeSet<String>();
        var observations = new TreeMap<String, String>();
        boolean triggered = false;
        for (int level : p.approvalBoundaryLevels().stream().sorted().toList()) {
            BigDecimal boundary = config.approvalBoundaries().get(level);
            var group = candidates.stream().filter(v -> v.amount().compareTo(boundary) < 0).toList();
            BigDecimal total = group.stream().map(Payment::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            boolean match = input.current().amount().compareTo(boundary) < 0
                    && group.size() >= p.minimumPayments() && total.compareTo(boundary) >= 0;
            observations.put("level" + level + "Boundary", boundary.toPlainString());
            observations.put("level" + level + "Total", total.toPlainString());
            observations.put("level" + level + "Count", Integer.toString(group.size()));
            if (match) { triggered = true; group.forEach(v -> ids.add(v.evidenceId())); }
        }
        return factor(rule, triggered ? Outcome.TRIGGERED : Outcome.NOT_TRIGGERED,
                "Individually sub-boundary payments including current once are compared with selected approval boundaries; contribution applies once",
                List.copyOf(ids), observations);
    }
    private Factor frequency(RiskInput input, RiskConfiguration.Rule rule, Frequency p, Instant start) {
        var history = payments(input, start);
        if (p.historyEligibility() == HistoryEligibility.UNRESOLVED) return unresolved(rule, "Frequency history eligibility is unresolved");
        var matches = history.stream().filter(v -> eligible(v, p.historyEligibility()) && within(v.submittedAt(), start, input.cutoff())).toList();
        long count = (long) matches.size() + 1;
        var ids = new ArrayList<>(matches.stream().map(Payment::evidenceId).toList()); ids.add(input.current().evidenceId());
        return factor(rule, count > p.maximumSubmissions() ? Outcome.TRIGGERED : Outcome.NOT_TRIGGERED,
                "Submission count includes current exactly once and must exceed the configured maximum",
                ids, Map.of("count", Long.toString(count), "maximumSubmissions", Integer.toString(p.maximumSubmissions())));
    }
    private Factor amount(RiskInput input, RiskConfiguration.Rule rule, Amount p, Instant start) {
        var matches = payments(input, start).stream().filter(v -> v.validated() && v.statusAsOfCutoff() == PaymentStatus.COMPLETE
                && within(v.completedAt(), start, input.cutoff())).toList();
        BigDecimal total = matches.stream().map(Payment::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        var ids = matches.stream().map(Payment::evidenceId).toList();
        var values = Map.of("sampleCount", Integer.toString(matches.size()), "historicalSum", total.toPlainString(),
                "currentAmount", input.current().amount().toPlainString(), "meanMultiplier", p.meanMultiplier().toPlainString());
        if (matches.size() < p.minimumObservations()) return factor(rule, Outcome.INSUFFICIENT_HISTORY,
                "Complete history contains fewer than the configured minimum observations; zero contribution, other rules continue", ids, values);
        if (total.signum() == 0) {
            if (p.zeroBaselinePolicy() == ZeroBaselinePolicy.UNRESOLVED) return factor(rule, Outcome.POLICY_UNRESOLVED,
                    "Zero-baseline semantics are unresolved; no final score is produced", ids, values);
            if (p.zeroBaselinePolicy() == ZeroBaselinePolicy.INSUFFICIENT_HISTORY) return factor(rule, Outcome.INSUFFICIENT_HISTORY,
                    "Explicit configured zero-baseline policy treats the sample as insufficient", ids, values);
        }
        boolean triggered = input.current().amount().multiply(BigDecimal.valueOf(matches.size()))
                .compareTo(p.meanMultiplier().multiply(total)) > 0;
        return factor(rule, triggered ? Outcome.TRIGGERED : Outcome.NOT_TRIGGERED,
                "Current amount times sample count must exceed multiplier times historical sum; the mean is never rounded", ids, values);
    }
    private Factor reactivation(RiskInput input, RiskConfiguration.Rule rule, Instant start) {
        Supplier supplier = supplier(input);
        var transitions = checked(input.supplierTransitions(), input, start);
        for (var transition : transitions) {
            if (!transition.supplierId().equals(input.current().supplierId()) || transition.changedAt().isAfter(input.cutoff()))
                throw new EvidenceFailure("Supplier transition does not match frozen evidence scope");
        }
        var latest = distinct(transitions, StatusTransition::evidenceId).stream()
                .filter(v -> v.previous() == SupplierStatus.INACTIVE && v.next() == SupplierStatus.ACTIVE
                        && !v.changedAt().isBefore(start) && !v.changedAt().isAfter(input.cutoff()))
                .max(Comparator.comparing(StatusTransition::changedAt).thenComparing(StatusTransition::evidenceId));
        boolean triggered = supplier.status() == SupplierStatus.ACTIVE && latest.isPresent();
        return factor(rule, triggered ? Outcome.TRIGGERED : Outcome.NOT_TRIGGERED,
                "An actual INACTIVE-to-ACTIVE transition in the inclusive status window is required",
                triggered ? List.of(latest.orElseThrow().evidenceId()) : List.of(),
                latest.map(v -> Map.of("transitionAt", v.changedAt().toString())).orElse(Map.of()));
    }
    private Factor newSupplier(RiskInput input, RiskConfiguration.Rule rule, Instant start) {
        Supplier supplier = supplier(input);
        boolean triggered = !supplier.createdAt().isBefore(start);
        return factor(rule, triggered ? Outcome.TRIGGERED : Outcome.NOT_TRIGGERED,
                "Supplier creation time is within the inclusive age window", triggered ? List.of(supplier.evidenceId()) : List.of(),
                Map.of("createdAt", supplier.createdAt().toString()));
    }
    private Factor previous(RiskInput input, RiskConfiguration.Rule rule, PreviousFlags p, Instant start) {
        var records = checked(input.previousAssessments(), input, start).stream()
                .filter(v -> !input.currentAndRetrySubmissionIds().contains(v.submissionId())).toList();
        for (var record : records) {
            if (!record.supplierId().equals(input.current().supplierId()) || record.assessedAt().isAfter(input.cutoff()))
                throw new EvidenceFailure("Prior assessment does not match frozen evidence scope");
        }
        var prior = distinct(records, PriorAssessment::submissionId);
        var matches = prior.stream().filter(v -> within(v.assessedAt(), start, input.cutoff())
                && v.triggeredFactors().stream().anyMatch(p.qualifyingRuleCodes()::contains)).sorted(Comparator.comparing(PriorAssessment::evidenceId)).toList();
        return factor(rule, matches.size() >= p.minimumFlaggedPayments() ? Outcome.TRIGGERED : Outcome.NOT_TRIGGERED,
                "Distinct previous submissions with original qualifying factors are counted once; PREVIOUS_RISK_FLAGS never qualifies itself",
                matches.stream().map(PriorAssessment::evidenceId).toList(), Map.of("flaggedSubmissions", Integer.toString(matches.size())));
    }
    private Factor nearDuplicate(RiskInput input, RiskConfiguration.Rule rule, NearDuplicate p, Instant start) {
        var history = payments(input, start);
        if (!RuleParameterContracts.unresolved(p).isEmpty()) return unresolved(rule, "Near-duplicate history eligibility or invoice-number normalization is unresolved");
        String currentNumber = normalize(input.current().invoiceNumber(), p.invoiceNumberNormalization());
        var matches = history.stream().filter(v -> eligible(v, p.historyEligibility()) && within(v.submittedAt(), start, input.cutoff())
                && v.amount().compareTo(input.current().amount()) == 0
                && !normalize(v.invoiceNumber(), p.invoiceNumberNormalization()).equals(currentNumber)).toList();
        return factor(rule, matches.isEmpty() ? Outcome.NOT_TRIGGERED : Outcome.TRIGGERED,
                "Same supplier and exact payment amount, different normalized invoice number, within the history window; contribution applies once",
                matches.stream().map(Payment::evidenceId).toList(), Map.of("matches", Integer.toString(matches.size()),
                        "invoiceNumberNormalization", p.invoiceNumberNormalization().name()));
    }
    private String normalize(String value, InvoiceNumberNormalization policy) {
        return policy == InvoiceNumberNormalization.TRIM_UPPERCASE ? value.trim().toUpperCase(Locale.ROOT) : value;
    }
    private boolean eligible(Payment payment, HistoryEligibility eligibility) {
        if (!payment.validated()) return false;
        return eligibility == HistoryEligibility.COMPLETED_PAYMENTS ? payment.statusAsOfCutoff() == PaymentStatus.COMPLETE
                : Set.of(PaymentStatus.PENDING_APPROVAL, PaymentStatus.APPROVED, PaymentStatus.PROCESSING, PaymentStatus.COMPLETE).contains(payment.statusAsOfCutoff());
    }
    private List<Payment> payments(RiskInput input, Instant start) {
        var records = checked(input.payments(), input, start).stream()
                .filter(v -> !input.currentAndRetrySubmissionIds().contains(v.submissionId())).toList();
        for (var payment : records) {
            if (!payment.supplierId().equals(input.current().supplierId()) || !payment.currencyCode().equals("INR")
                    || payment.submittedAt().isAfter(input.cutoff()) || (payment.completedAt() != null && payment.completedAt().isAfter(input.cutoff())))
                throw new EvidenceFailure("Payment history does not match frozen evidence scope");
        }
        return distinct(records, Payment::submissionId)
                .stream().sorted(Comparator.comparing(Payment::submittedAt).thenComparing(Payment::submissionId)).toList();
    }
    private Supplier supplier(RiskInput input) {
        var records = checked(input.supplier(), input, input.cutoff());
        if (records.size() != 1) throw new EvidenceFailure("Exactly one supplier snapshot is required");
        var supplier = records.getFirst();
        if (!supplier.supplierId().equals(input.current().supplierId()) || supplier.createdAt().isAfter(input.cutoff()))
            throw new EvidenceFailure("Supplier snapshot does not match frozen evidence scope");
        return supplier;
    }
    private <T> List<T> checked(Evidence<T> evidence, RiskInput input, Instant requiredStart) {
        if (evidence.availability() != Availability.COMPLETE) throw new EvidenceFailure("Required dependency evidence is unavailable or incomplete");
        if (!evidence.cutoff().equals(input.cutoff()) || !evidence.supplierId().equals(input.current().supplierId())
                || !evidence.currencyCode().equals("INR") || evidence.coveredFrom().isAfter(requiredStart))
            throw new EvidenceFailure("Required dependency evidence has inconsistent scope, cutoff or coverage");
        return evidence.records();
    }
    private <T> List<T> distinct(List<T> records, Function<T, String> key) {
        var unique = new TreeMap<String, T>();
        for (var record : records) {
            var previous = unique.putIfAbsent(key.apply(record), record);
            if (previous != null && !previous.equals(record)) throw new EvidenceFailure("Conflicting evidence for one logical identity");
        }
        return List.copyOf(unique.values());
    }
    private boolean within(Instant value, Instant start, Instant cutoff) { return value != null && !value.isBefore(start) && value.isBefore(cutoff); }
    private Factor unresolved(RiskConfiguration.Rule rule, String explanation) { return factor(rule, Outcome.POLICY_UNRESOLVED, explanation, List.of(), Map.of()); }
    private Factor factor(RiskConfiguration.Rule rule, Outcome outcome, String explanation, List<String> evidence, Map<String, String> values) {
        return new Factor(rule.code(), outcome, rule.weight(), outcome == Outcome.TRIGGERED ? rule.weight() : 0, explanation,
                evidence.stream().distinct().sorted().toList(), values);
    }
    private static final class EvidenceFailure extends RuntimeException { EvidenceFailure(String message) { super(message); } }
}
