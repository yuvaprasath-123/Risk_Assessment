package com.ofss.risk.engine;

import com.ofss.risk.contract.RuleCode;
import java.math.*;
import java.time.Instant;
import java.util.*;

/** Immutable evidence supplied by adapters. LocalDateTime must be resolved to an agreed offset before construction. */
public record RiskInput(Payment current, Instant cutoff, Set<String> currentAndRetrySubmissionIds,
        Evidence<Payment> payments, Evidence<Supplier> supplier,
        Evidence<StatusTransition> supplierTransitions, Evidence<PriorAssessment> previousAssessments) {
    public RiskInput {
        Objects.requireNonNull(current); Objects.requireNonNull(cutoff);
        currentAndRetrySubmissionIds = Set.copyOf(currentAndRetrySubmissionIds);
        if (!currentAndRetrySubmissionIds.contains(current.submissionId()) || current.submittedAt().isAfter(cutoff)
                || (current.completedAt() != null && current.completedAt().isAfter(cutoff)))
            throw new IllegalArgumentException("Current submission identity/time does not match cutoff");
        if (!current.validated()) throw new IllegalArgumentException("Current payment must have passed Developer 1 validation");
        if (!"INR".equals(current.currencyCode())) throw new IllegalArgumentException("Only INR inputs are supported");
        Objects.requireNonNull(payments); Objects.requireNonNull(supplier);
        Objects.requireNonNull(supplierTransitions); Objects.requireNonNull(previousAssessments);
    }
    public enum Availability { COMPLETE, UNAVAILABLE, INCOMPLETE }
    public enum PaymentStatus { DRAFT, PENDING_VALIDATION, PENDING_APPROVAL, APPROVED, REJECTED, PROCESSING, COMPLETE, FAILED }
    public enum SupplierStatus { ACTIVE, INACTIVE }
    public record Evidence<T>(Availability availability, String sourceSnapshotId, String supplierId,
            String currencyCode, Instant cutoff, Instant coveredFrom, List<T> records) {
        public Evidence {
            Objects.requireNonNull(availability); Objects.requireNonNull(cutoff);
            RiskConfiguration.required(supplierId); RiskConfiguration.required(currencyCode);
            if (availability == Availability.COMPLETE) {
                RiskConfiguration.required(sourceSnapshotId); Objects.requireNonNull(coveredFrom);
            }
            if (coveredFrom != null && coveredFrom.isAfter(cutoff)) throw new IllegalArgumentException("Invalid evidence coverage");
            records = List.copyOf(records);
        }
        public static <T> Evidence<T> unavailable(String supplierId, String currencyCode, Instant cutoff) {
            return new Evidence<>(Availability.UNAVAILABLE, null, supplierId, currencyCode, cutoff, null, List.of());
        }
    }
    public record Payment(String evidenceId, String submissionId, String invoiceId, String invoiceNumber,
            String supplierId, String currencyCode, BigDecimal amount, Instant submittedAt,
            Instant completedAt, PaymentStatus statusAsOfCutoff, boolean validated) {
        public Payment {
            for (String text : List.of(evidenceId, submissionId, invoiceId, invoiceNumber, supplierId, currencyCode)) RiskConfiguration.required(text);
            money(amount);
            // NUMBER(15,2) monetary values have one exact canonical scale for logical-record equality.
            amount = amount.setScale(2, RoundingMode.UNNECESSARY);
            Objects.requireNonNull(submittedAt); Objects.requireNonNull(statusAsOfCutoff);
            if (statusAsOfCutoff == PaymentStatus.COMPLETE && completedAt == null) throw new IllegalArgumentException("Completion evidence is missing");
            if (completedAt != null && completedAt.isBefore(submittedAt)) throw new IllegalArgumentException("Completion precedes submission");
        }
    }
    public record Supplier(String evidenceId, String supplierId, Instant createdAt, SupplierStatus status) {
        public Supplier { RiskConfiguration.required(evidenceId); RiskConfiguration.required(supplierId); Objects.requireNonNull(createdAt); Objects.requireNonNull(status); }
    }
    public record StatusTransition(String evidenceId, String supplierId, SupplierStatus previous,
            SupplierStatus next, Instant changedAt) {
        public StatusTransition { RiskConfiguration.required(evidenceId); RiskConfiguration.required(supplierId); Objects.requireNonNull(previous); Objects.requireNonNull(next); Objects.requireNonNull(changedAt); }
    }
    public record PriorAssessment(String evidenceId, String submissionId, String supplierId,
            Instant assessedAt, Set<RuleCode> triggeredFactors) {
        public PriorAssessment {
            RiskConfiguration.required(evidenceId); RiskConfiguration.required(submissionId); RiskConfiguration.required(supplierId);
            Objects.requireNonNull(assessedAt); triggeredFactors = Set.copyOf(triggeredFactors);
        }
    }
    static void money(BigDecimal value) {
        Objects.requireNonNull(value);
        if (value.signum() < 0 || value.compareTo(new BigDecimal("9999999999999.99")) > 0)
            throw new IllegalArgumentException("Money must fit nonnegative NUMBER(15,2)");
        if (value.stripTrailingZeros().scale() > 2) throw new IllegalArgumentException("Money must not require rounding");
    }
}
