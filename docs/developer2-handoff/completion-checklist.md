# Completion and verification checklist

Current implementation baseline: `3031d878975c039e3d390e73afd4953e489a3829`. No runtime changes or live reruns were made for this handoff. Historical results below are recorded user-run/previous automated evidence, not tests claimed to have run again today. **COMPLETE** describes Developer 2 implementation plus the stated local verification scope, not deployment certification.

| Area | Classification | Evidence / remaining boundary |
|---|---|---|
| Configuration drafts, exact rules/bands/money/SLAs | COMPLETE | Authenticated API, JDBC/transaction regressions and user-run Oracle creation/readback/replacement/stale-write checks; no automatic seeding |
| Concrete readiness and real publication | COMPLETE | Seven typed contracts and approval-policy-v1; genuine real-validator offline flow; 3 user-run Oracle readiness→publish tests passed, zero failures/errors/skips |
| Activation and immutable pinned snapshots | COMPLETE | 6 user-run activation mechanics tests passed; later real-readiness publication/configured-client suites also exercised activation. Older controlled-PUBLISHED fixtures alone did not prove readiness-approved publication |
| V2 policy hashing / historical reads | COMPLETE | configuration-content-v2 explicitly hashes approval semantics; legacy V1 reads preserve historical meaning, new submissions reject unsupported legacy semantics |
| Risk engine / acceptance / leases / evidence / finalization | COMPLETE | Seven configurable factors; raw/capped/band verification; immutable local hashes, full fence and atomic finalization; user-run acceptance 8, work 8, evidence 10 and finalization 13 live tests passed at respective checkpoints |
| Local prior-risk history / durable capture | COMPLETE | 8 user-run prior-capture tests passed; visibility worker terminated, cleanup zero parents. Uses collection snapshot's committed population; retries/takeovers reuse immutable stored records/coverage/hash |
| Manager risk reads | COMPLETE within recorded scope | 1,214 regression tests + 69 affected passes (overlap, not additive). Genuine issuer HTTP-to-Oracle suite: 6 tests, zero failures/errors, 1 skipped. Genuine non-manager-token case remains unverified |
| Internal reads / service authentication | COMPLETE for local mechanics | 1,503 baseline + 24 additional offline safety passes at checkpoint; 12 live HTTP-to-Oracle tests passed using ephemeral test issuer. Receiver/client groups terminated and cleanup zero parents |
| Submission/status/recovery | COMPLETE for local mechanics | 1,636 offline tests; 11 user-run live submission tests passed. Completed retries avoid dependencies/evaluation; active work fenced, same-identity POST drives recovery; no scheduler |
| Production configuration HTTP mapping | COMPLETE for implementation/local transport | 1,753 offline tests including 11 native HTTP tests passed. Active/pinned reads verify V2 and use versionRowVersion. JVM-only Unix-domain socket directory used; underlying OS/JDK cause unconfirmed |
| Authoritative Facts HTTP client | COMPLETE for provider protocol/local mechanics | Strict PASS/certification/identity/cutoff/receipt checks; native contract tests. Provider protocol is a D2 contract, not a discovered D1 endpoint |
| Upstream Evidence HTTP client | COMPLETE for provider protocol/local mechanics | Certified scope, retained pagination, complete population digest, limits/timeouts/exclusions; local prior risk stays local |
| Configured-client end-to-end Oracle flow | COMPLETE within synthetic scope | **2,117-test offline baseline; 173 affected passes including 10 new safety cases. Counts overlap and are not added. 7 user-run live tests passed, zero failures/errors/skips.** Real readiness-approved config + all three production HTTP clients + submission/capture/freeze/finalization. All receivers/client/providers/dependency/concurrent workers terminated; both schemas zero fixture parents |
| OpenAPI/examples/Insomnia/setup/handoff | COMPLETE for offline artifact checks | See machine-readable validation report and validator README. Real code accepted the demonstration config and provider messages. **Actual Insomnia desktop import/send not tested** |
| Actual production adapter provisioning | IMPLEMENTED_BUT_UNVERIFIED | Flags default disabled; genuine trusted origins, service credentials, issuer/audience/grants and provider support must be supplied. Test adapters/ephemeral issuer do not certify runtime availability |
| Genuine issuer/TLS/rotation interoperability | IMPLEMENTED_BUT_UNVERIFIED | RS256 verification/trust rotation mechanics tested offline; real D1/D3 issuer provisioning and HTTPS chains/hostname/expiry/renewal remain external integration checks |
| Genuine certified cutoff/retention/restart replay | IMPLEMENTED_BUT_UNVERIFIED | Protocol/client enforcement exists; provider's real durable storage, cross-requester completeness and receipt lifetime are not proven by contract-test fixtures |
| Stable genuine resubmission lineage | MISSING external agreement | Namespaced identity is enforced, payment ID is separate. D1 must confirm retries versus new linked resubmissions and full retry exclusions |
| D1/D3 durable polling/retry coordinator | MISSING cross-team implementation/agreement | Proposed contract in handoff. D1/D3 must persist requests/checkpoints, poll/retry and deduplicate workflow/payment transitions |
| Risk callback/outbox/Kafka/scheduler | MISSING by deliberate scope | No agreed push-delivery responsibility; polling proposal may suffice once adopted. No exactly-once cross-service guarantee |
| D3 approval task/backup/audit execution | MISSING external verification | Immutable approved semantics supplied; D3 must implement eligible backup selection, timers, human authorization and audit from pinned policy |
| UI integration | MISSING separate frontend milestone | D2 API specifications supplied; teammate UI preserved; no frontend integration claim |

## Historical environment/verification corrections

- Original `D2_RISK` USERS quota was 100M. ORA-01536 in a user-run finalization test was resolved by increasing it to **300M**; historical setup SQL retains 100M and review stops. Earlier SQL “future/not executed” notes are preparation history, not current deployment status.
- Configuration's early empty GET/read-only JDBC success was followed by actual draft writes and real readiness-approved publication/activation. Do not retain “all populated rows/write behavior unverified” as a current global claim; full arbitrary constraint enforcement and all production populations are still not certified.
- Earlier unconditional policy blockers were replaced by concrete supported versioned rule/approval contracts. Upstream API availability does **not** block publication; missing/incomplete/uncertified evidence still prevents runtime scoring.
- The earlier evidence connection-guard failure occurred before write tests started; corrected live evidence run passed. The finalization quota failure was resolved before the passing run. Neither failure should be presented as a successful assertion path.
- Genuine manager JWT interoperability was checked locally at earlier checkpoints; current MFA and future genuine service-provider deployments still require integration testing. The skipped genuine non-manager token case is not replaced by ephemeral token tests.

## Bounded remaining handoff work

1. D1 confirms logical/resubmission identities and implements/provisions both versioned providers or agrees an explicit compatibility adapter. Demonstrate supplier-wide coverage and durable receipt replay after provider restart.
2. D1/D3 provision service issuer, audiences, least grants, RSA public-key rotation and TLS; test genuine cross-use denial and renewal/expiry. Do not share signing/private material in chat.
3. Agree durable submit/status/retry/poll ownership; implement caller checkpoints and recipient deduplication. Verify uncertain response, crash recovery, BLOCKED intervention and pinned policy after activation changes.
4. D3 validates its actual threshold/risk routing, human authority, task timer, one eligible L3 backup and audit behavior against a pinned V2 snapshot.
5. Import the supplied collection into the actual Insomnia version and privately populate local credentials; test genuine integrated flow only after separate authorization. No blanket claim of integration-ready production operation is justified before these external checks.
