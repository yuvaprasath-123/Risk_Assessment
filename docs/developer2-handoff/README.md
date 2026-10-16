# Developer 2 integration handoff

Current at 16 October 2026, derived from the implementation at Git `3031d878975c039e3d390e73afd4953e489a3829`. This package changes documentation and offline documentation validators only. The Scope PDF supplies business requirements; ownership and subsequent user-approved demonstration decisions supplement it. Q&A material is excluded.

Developer 2 owns **Business Configuration** (8086) and **Risk Assessment** (8087). Developer 1 owns payment lifecycle, validation and authoritative upstream facts/history. Developer 3 owns approval workflow, payment authorization and business audit. The preserved `D2_VALIDATION` account does not make validation a Developer 2 implementation responsibility.

## Artifacts

| Artifact | Contents |
|---|---|
| [Business Configuration OpenAPI](openapi/business-configuration.openapi.json) | All 17 implemented manager/internal operations; draft editing, real readiness, publication, activation and pinned reads |
| [Risk Assessment OpenAPI](openapi/risk-assessment.openapi.json) | All 9 implemented manager/internal operations; reference-only submission, read-only status and completed reads |
| [Facts provider OpenAPI](openapi/facts-provider.openapi.json) | `risk-authoritative-facts-v1`; Developer 2 provider contract, **not an existing Developer 1 endpoint** |
| [Evidence provider OpenAPI](openapi/evidence-provider.openapi.json) | `risk-upstream-evidence-v1`; certified, retained supplier-wide population and pagination contract |
| [Insomnia v5 collection](insomnia/developer2.insomnia-v5.json) | Manager/service sub-environments, blank credentials, ordered MFA/configuration workflow, all operations and 16 demonstration child writes |
| [Insomnia v4 alternative](insomnia/developer2.insomnia.json) | Legacy import structure for clients that prefer v4; import only one collection |
| [Examples and flow](examples/README.md) | Complete publishable draft, real-code-generated snapshots/hashes, provider messages, submission/retry/status/errors |
| [Consolidated operator guide](operations.md) | Environment, build, Oracle, startup, authentication, tests and recovery |
| [Developer 1/3 handoff](developer1-developer3.md) | Identity/permission matrix, provider obligations, pinned version use and proposed durable coordination |
| [Completion checklist](completion-checklist.md) | Local verification evidence and remaining genuine interoperability work |
| [Validation report](validation/validation-report.json) | Machine-readable exact checks; [reproduction](validation/README.md) |
| [Review findings](validation/review-notes.md) | Fixed documentation/tooling defects, retained integration limits and [exact commit candidates](validation/commit-candidates.txt) |

Specifications use OpenAPI 3.1 JSON and JSON Schema 2020-12. IDs/revisions are strings in responses; money uses decimal strings. Exact integers remain JSON numbers where the API requires them. Never pass money or precise rule parameters through JavaScript `Number` before transmission; backend precision cannot recover already-rounded input.

## Import and first use

Import the v5 file using Insomnia's file import. Select **Manager only - local** for public configuration/risk reads and draft/publication operations; use **Services only - local** for internal routes. Populate only the applicable credentials locally, outside version control. The two environments deliberately blank the other credential class. Receiver-specific service tokens use distinct variables; there is no universal service token and no token generator in this package.

Set actual returned `version_id`, `assessment_id` and latest revisions before sending requests. `expected_row_version` is manually refreshed from each response or version GET. The collection has no scripts that automatically submit, publish, activate, retry, harvest tokens or modify a database. First activation explicitly uses null; replacing an existing pointer needs its actual revision from active GET. Do not send the first-activation template against an existing pointer expecting replacement.

Explicit sort keys put MFA first, then the numbered demonstration workflow: create → fetch revision → sixteen child edits → readiness → publication → inspect pointer. Choose **either** 22A (no pointer) **or** 22B (existing pointer and refreshed revision). This is a manual workflow, not a “run all requests” collection. Generic endpoint catalog and recovery/provider folders remain separate. Both export formats disable redirects and cookie sending/storage; actual desktop import/settings behavior is still untested.

Set `payment_reference`, `validation_reference` and `validation_revision` to genuine immutable provider references before using submission/retry templates. For an immutable unversioned validation reference, explicitly change `validationRevision` to JSON null or omit it; an empty string is invalid. POST and status share the same submission-source/key environment variables so uncertainty recovery addresses the same identity. These templates stay unsendable until required references/credentials are supplied privately.

The package was checked against Kong's [official Insomnia v5.1 schema](https://raw.githubusercontent.com/Kong/insomnia/develop/schemas/insomnia.schema.5.1.json). Kong documents [v5 and legacy v4 import](https://developer.konghq.com/insomnia/import-export/). **Actual Insomnia desktop import and sending requests were not tested.** Import structure validation is not UI execution or genuine issuer/provider interoperability.

## Current limits

All three production HTTP clients are implemented and opt-in. Their existence does not establish that production credentials, trusted endpoints, TLS certificates or durable provider receipts have been provisioned. The successful configured-client Oracle suite used explicit contract-test providers and an ephemeral issuer. Genuine Developer 1/3 interoperability remains pending.

Concrete `approval-policy-v1` readiness is implemented; complete supported V2 configurations can be published without an unconditional policy bypass. Earlier documentation describing universal publication blockers is superseded. Unknown/inconsistent policy contracts still fail closed; publication never certifies upstream evidence availability.

Polling-based durable coordination is an **integration contract proposal**, not an implemented cross-team worker. No callback, outbox, scheduler, automatic payment processing or exactly-once delivery guarantee exists. GET status never retries work. Developer 3 must use each assessment's pinned version/hash, never replace it with the active pointer.
