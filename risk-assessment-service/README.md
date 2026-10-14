# Standalone risk engine

Java 21 pure Java library, built with the Spring Boot 4.1.1 dependency baseline and installed Maven 3.9.16. There is no HTTP endpoint, Spring application, persistence, scheduler or live adapter. Developer 1 owns validation; `RiskInput.current.validated` certifies its upstream PASS. Exact duplicates must already have been blocked upstream.

From the repository root, build and test the shared contracts, configuration service and engine together:

```powershell
mvn -f developer2-pom.xml '-Dtest=!OracleReadOnlyVerificationTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
```

`RiskConfiguration` is a frozen, immutable capped-score snapshot with all seven typed rule entries (enabled or disabled), configurable nonnegative weights, cap, contiguous LOW/MEDIUM/HIGH bands and selected approval boundaries. Version ID/rowVersion are decimal strings within Java Long limits. `contentHash` identifies the supplied frozen snapshot, not a claim that a draft has a published content hash. No hash is computed or verified by this library; the later configuration adapter must supply one using an agreed canonical representation. The existing configuration service still allows SUM drafts; converting one into a capped engine snapshot is not automatic and must not silently change its policy.

Approved demonstration values appear only in tests: weights 35/15/20/15/10/15/25, 7/7/90/30/90/30/30-day windows, frequency >3, amount >2x mean with at least 5 observations, cap 100, LOW 0–29/MEDIUM 30–60/HIGH 61–100. There is no seeded or active configuration. Numeric values remain caller-supplied configuration. Monetary inputs and selected boundaries fit nonnegative NUMBER(15,2); sums and comparisons use exact BigDecimal, raw totals use BigInteger. INR only; no FX conversion. Ratios use decimal strings in parameter JSON.

`RiskScoringEngine.evaluate(configuration, input)` evaluates rules independently in evaluation order, contributing each triggered weight once. Results retain factor outcomes, explanations, observations, matched evidence IDs and the complete immutable configuration/input snapshots. A complete but short amount baseline yields INSUFFICIENT_HISTORY with zero contribution; other rules continue. Any enabled rule's missing/incomplete/inconsistent dependency yields TECHNICAL_FAILURE and null raw/final score/band. Unresolved applicable policy yields POLICY_UNRESOLVED and likewise no final result. Valid current input/configuration is checked by model constructors; malformed caller data is rejected rather than converted to low risk.

## Evidence adapter contract

`EvidenceAdapter` is an integration port only. Adapters must provide same-supplier INR evidence, an exact common UTC cutoff, stable source snapshot IDs, sufficient coverage and an explicit COMPLETE/UNAVAILABLE/INCOMPLETE status. COMPLETE is an authoritative completeness guarantee, including pagination and as-of status, not merely a successful API response. Payment windows are `[cutoff-windowSeconds, cutoff)`; amount baselines use completedAt, activity/near-duplicate histories use submittedAt. Supplier creation/reactivation age windows include both endpoints. New-supplier evaluation needs exactly one supplier snapshot; reactivation also needs actual status transitions. PriorAssessment represents a completed assessment's original triggered factors; overrides do not erase that original evidence.

For unavailable dependencies, `Evidence.unavailable(supplierId, currencyCode, cutoff)` records the requested scope without inventing a source snapshot or coverage. Those metadata fields are mandatory for COMPLETE evidence; unavailable/incomplete evidence always fails enabled dependent rules.

The engine excludes supplied current/retry submission identities before evaluating history and deduplicates identical historical rows by logical submission identity. Conflicting rows fail closed. Previous flags count distinct submissions, and PREVIOUS_RISK_FLAGS cannot qualify itself. No assessment-once enforcement is claimed. Adapters must agree submission/resubmission identity, canonicalize retry records, certify historical validation and resolve Developer 1 LocalDateTime values to the agreed timezone. Current requester-scoped payment APIs cannot supply authoritative history across requesters. Payment/invoice DTOs lack currency; previous factor history and validation handoff APIs are not available. No endpoint is assumed to exist.

## Approved demonstration choices and configurable alternatives

The user approved VALIDATED_SUBMISSIONS for activity history: validated PENDING_APPROVAL, APPROVED, PROCESSING and COMPLETE submissions. This applies to structuring, frequency and near duplicate. The evaluator still reads the configured historyEligibility; UNRESOLVED and COMPLETED_PAYMENTS remain explicit contract alternatives, with no implicit default.

The user approved EXACT invoice-number comparison. Different exact numbers and exact equal payment amounts trigger near duplicate once. The evaluator retains configurable UNRESOLVED/EXACT/TRIM_UPPERCASE choices (the latter uses Java trim and Locale.ROOT uppercase); no normalization is silently selected. Developer 1 integration must align with its exact-duplicate validation.

The user approved zeroBaselinePolicy INSUFFICIENT_HISTORY: a zero-sum baseline contributes zero while other rules continue, including when the minimum sample count is met. UNRESOLVED and COMPARE_TO_ZERO remain configurable alternatives, not evaluator defaults. Complete empty history can produce no matches for count/age/flag rules; it is not the same as unavailable evidence.

The approved structuring selection is configured L2 and L3 boundaries, contributing once even when both match. Previous flags qualify all six other factors, using historical assessments only within the configured `[cutoff-windowSeconds, cutoff)` window; current/retry identities are excluded and original triggered evidence retained. These choices are explicit test configurations and contracts, not database seeds or active configuration. Retry identity and new linked resubmission identity remain pending Developer 1 confirmation.

Readiness retains Developer 1 history/identity, approval mapping and Developer 3 SLA blockers. Risk contract implementation alone never makes configuration ready. Persistence, HTTP assessment, workflow/audit delivery and once-per-submission enforcement remain later work; no live Oracle verification was performed here.
