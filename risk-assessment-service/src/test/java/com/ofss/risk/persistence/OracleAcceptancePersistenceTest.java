package com.ofss.risk.persistence;

import java.io.*;
import java.math.BigDecimal;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real Oracle WRITES and scoped cleanup. No application context, HTTP or DDL. */
@EnabledIfEnvironmentVariable(named = "RISK_VERIFY_ORACLE_WRITES", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OracleAcceptancePersistenceTest {
    final OracleAcceptanceFixtures fixtures = new OracleAcceptanceFixtures();
    final OracleAcceptanceDiagnostics diagnostics = new OracleAcceptanceDiagnostics();
    DataSource source;
    JdbcTemplate jdbc;
    JdbcTransactionManager manager;
    RiskAcceptanceService service;
    boolean cleanupAllowed;

    @BeforeAll void connect() {
        System.out.println("Synthetic Oracle acceptance namespace (retain for cleanup recovery): " + fixtures.namespace);
        source = OracleAcceptanceConnection.externalDataSource();
        jdbc = diagnostics.jdbc(source); manager = new JdbcTransactionManager(source);
        service = service(new RiskAcceptanceRepository(jdbc));
        checked(() -> assertEquals(0, namespaceCount(), "New UUID namespace must be empty"));
        cleanupAllowed = true; // Never clean pre-existing rows if namespace ownership could not be established.
    }
    static JdbcTemplate jdbc(DataSource source) { var jdbc = new JdbcTemplate(source); jdbc.setQueryTimeout(15); return jdbc; }
    RiskAcceptanceService service(RiskAcceptanceRepository repository) {
        var observed = new RiskAcceptanceRepository(jdbc) {
            @Override public Optional<AcceptedAssessment> find(SubmissionIdentity identity) {
                try { return repository.find(identity); }
                catch (RuntimeException failure) { diagnostics.record("FIND_OR_MAP", failure); throw failure; }
            }
            @Override AcceptedAssessment insert(AcceptanceRequest request, CanonicalAcceptance.Document accepted,
                    CanonicalAcceptance.Document config, java.time.Instant created) {
                try { return repository.insert(request, accepted, config, created); }
                catch (RuntimeException failure) { diagnostics.record("INSERT_OR_READBACK", failure); throw failure; }
            }
        };
        return new RiskAcceptanceService(observed, new CanonicalAcceptance(), manager, fixtures.clock);
    }
    @AfterAll void cleanup() {
        diagnostics.print();
        if (!cleanupAllowed) { System.out.println("Fixture cleanup deferred; namespace: " + fixtures.namespace); return; }
        try {
            new OracleAcceptanceFixtureCleaner(jdbc, manager).clean(fixtures.namespace);
            assertEquals(0, namespaceCount(), "Synthetic rows must be removed");
            System.out.println("Fixture cleanup completed; remaining parents=0; namespace: " + fixtures.namespace);
        } catch (RuntimeException failure) {
            diagnostics.record("FIXTURE_CLEANUP", failure); diagnostics.print();
            throw new AssertionError("Fixture cleanup failed/refused. Retain this exact namespace for recovery: " + fixtures.namespace);
        }
    }
    @Test void createsAndAssociatesExactlyOneParentAndSnapshot() {
        checked(() -> {
            var r = fixtures.request("creation"); var saved = service.accept(r);
            assertTrue(Long.parseLong(saved.assessmentId()) > 0); assertTrue(Long.parseLong(saved.configurationSnapshotId()) > 0);
            assertEquals("ACCEPTED", saved.workState()); assertEquals("0", saved.rowVersion());
            assertEquals(saved, service.find(r.identity()).orElseThrow());
            assertCounts(r, saved, 1);
        });
    }
    @Test void matchingExplicitAndOmittedConfigurationRetriesPreserveBothRows() {
        checked(() -> {
            var r = fixtures.request("retries"); var first = service.accept(r); var before = fingerprint(r, first);
            for (var config : Arrays.asList(null, r.configuration())) {
                var retry = AcceptanceFixtures.copy(r, config, r.paymentId(), r.validationRevision(), r.cutoff(), r.currentAndRetrySubmissionIds(), "later transport");
                assertEquals(first, service.accept(retry));
                assertTrue(before.equals(fingerprint(r, first)), "Matching retries must leave every parent/snapshot column unchanged");
            }
            assertCounts(r, first, 1);
        });
    }
    @Test void conflictingPaymentValidationAndConfigurationFactsCannotMutateAcceptedData() {
        checked(() -> {
            var r = fixtures.request("conflicts"); var first = service.accept(r); var before = fingerprint(r, first);
            var c = r.configuration();
            var differentConfig = new com.ofss.risk.engine.RiskConfiguration("2", c.rowVersion(), c.contentHash(), c.currencyCode(), c.maxFinalScore(), c.rules(), c.bands(), c.approvalBoundaries());
            var changedRules = new ArrayList<>(c.rules()); var rule = changedRules.getFirst();
            changedRules.set(0, new com.ofss.risk.engine.RiskConfiguration.Rule(rule.code(), rule.enabled(), rule.weight() + 1,
                    rule.evaluationOrder(), rule.parameterSchemaVersion(), rule.parameters()));
            var differentContents = new com.ofss.risk.engine.RiskConfiguration(c.versionId(), c.rowVersion(), c.contentHash(), c.currencyCode(), c.maxFinalScore(), changedRules, c.bands(), c.approvalBoundaries());
            var p = r.current();
            var differentMoney = new com.ofss.risk.engine.RiskInput.Payment(p.evidenceId(), p.submissionId(), p.invoiceId(), p.invoiceNumber(), p.supplierId(), p.currencyCode(),
                    new BigDecimal("123.45"), p.submittedAt(), p.completedAt(), p.statusAsOfCutoff(), p.validated());
            var cases = List.of(
                    AcceptanceFixtures.copy(r, null, 17, null, r.cutoff(), r.currentAndRetrySubmissionIds(), r.correlationId()),
                    AcceptanceFixtures.copy(r, null, r.paymentId(), "changed-validation-revision", r.cutoff(), r.currentAndRetrySubmissionIds(), r.correlationId()),
                    AcceptanceFixtures.copy(r, differentConfig, r.paymentId(), null, r.cutoff(), r.currentAndRetrySubmissionIds(), r.correlationId()),
                    AcceptanceFixtures.copy(r, differentContents, r.paymentId(), null, r.cutoff(), r.currentAndRetrySubmissionIds(), r.correlationId()),
                    new AcceptanceRequest(r.identity(), r.paymentId(), r.requesterId(), "different-pass-reference", null, r.current(), r.cutoff(), r.currentAndRetrySubmissionIds(), r.evaluatorVersion(), null, r.correlationId()),
                    new AcceptanceRequest(r.identity(), r.paymentId(), r.requesterId(), r.validationReference(), null, differentMoney, r.cutoff(), r.currentAndRetrySubmissionIds(), r.evaluatorVersion(), null, r.correlationId()));
            for (var conflict : cases) {
                assertEquals(AcceptanceFailure.Code.SUBMISSION_CONFLICT, assertThrows(AcceptanceFailure.class, () -> service.accept(conflict)).code());
                assertEquals(first, service.find(r.identity()).orElseThrow());
                assertTrue(before.equals(fingerprint(r, first)), "Conflicts must leave every persisted column unchanged");
            }
            assertCounts(r, first, 1);
        });
    }
    @Test void exactNumbersLargeUnicodeClobsAndNanosecondsRoundTrip() {
        checked(() -> {
            var r = fixtures.request("roundtrip"); var saved = service.accept(r);
            var canonical = new CanonicalAcceptance(); var config = canonical.configuration(r.configuration());
            var accepted = canonical.acceptance(r, r.configuration().versionId(), r.configuration().rowVersion(), config.hash());
            assertTrue(config.json().length() > 32767, "Exercise a real large CLOB");
            assertTrue(config.json().equals(saved.configurationJson()), "Exact Unicode/escaped configuration CLOB");
            assertTrue(accepted.json().equals(saved.acceptanceJson()), "Exact accepted input CLOB including explicit nulls");
            assertEquals(config.hash(), saved.configurationHash()); assertEquals(accepted.hash(), saved.acceptanceHash());
            assertEquals(fixtures.cutoff, saved.cutoff()); assertEquals(fixtures.created, saved.createdAt());
            assertEquals(saved, service.find(r.identity()).orElseThrow());
            jdbc.query("SELECT PAYMENT_ID, REQUESTER_ID, PAYMENT_AMOUNT, CONFIGURATION_ROW_VERSION, EVIDENCE_AS_OF, CREATED_AT FROM D2_RISK.RISK_ASSESSMENTS WHERE ASSESSMENT_ID = ?", rs -> {
                assertTrue(rs.next()); assertEquals(Long.MAX_VALUE, rs.getBigDecimal("PAYMENT_ID").longValueExact());
                assertEquals(9007199254740993L, rs.getBigDecimal("REQUESTER_ID").longValueExact());
                assertEquals(Long.MAX_VALUE, rs.getBigDecimal("CONFIGURATION_ROW_VERSION").longValueExact());
                assertEquals(new BigDecimal("9999999999999.99"), rs.getBigDecimal("PAYMENT_AMOUNT"));
                assertEquals(fixtures.cutoff, rs.getObject("EVIDENCE_AS_OF", OffsetDateTime.class).toInstant());
                assertEquals(fixtures.created, rs.getObject("CREATED_AT", OffsetDateTime.class).toInstant()); return null;
            }, Long.parseLong(saved.assessmentId()));
        });
    }
    @ParameterizedTest @ValueSource(ints = {1, 2, 3})
    void injectedFailureAfterParentSnapshotOrAssociationRollsBack(int stage) {
        checked(() -> {
            String key = List.of("rollback-parent", "rollback-snapshot", "rollback-associated").get(stage - 1);
            var r = fixtures.request(key); var failingJdbc = new FailingJdbc(source, stage, diagnostics);
            var repo = stage == 3 ? new RiskAcceptanceRepository(failingJdbc) {
                @Override AcceptedAssessment insert(AcceptanceRequest request, CanonicalAcceptance.Document acceptance,
                        CanonicalAcceptance.Document config, java.time.Instant created) {
                    var saved = super.insert(request, acceptance, config, created);
                    assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                    assertEquals(1, parentCount(request)); // Same thread/datasource sees uncommitted parent.
                    failingJdbc.injectedAt.set(3);
                    throw new InjectedFailure();
                }
            } : new RiskAcceptanceRepository(failingJdbc);
            assertEquals(AcceptanceFailure.Code.RISK_PERSISTENCE_UNAVAILABLE, assertThrows(AcceptanceFailure.class, () -> service(repo).accept(r)).code());
            requireInjectionReached(failingJdbc, stage);
            assertTrue(service.find(r.identity()).isEmpty()); assertEquals(0, parentCount(r));
            assertNotNull(failingJdbc.parentId.get(), "Injection must occur after successful INSERT/identity retrieval");
            assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM D2_RISK.RISK_CONFIGURATION_SNAPSHOTS WHERE ASSESSMENT_ID = ?", Long.class, failingJdbc.parentId.get()));
        });
    }
    @Test void concurrentRealTransactionsProduceOneAssessmentAndOneSnapshot() throws Exception {
        var r = fixtures.request("concurrency"); var barrier = new CyclicBarrier(2);
        var firstReads = new AtomicInteger(); var collisions = new AtomicInteger();
        var repo = new RiskAcceptanceRepository(jdbc) {
            @Override public Optional<AcceptedAssessment> find(SubmissionIdentity identity) {
                var result = super.find(identity);
                if (identity.equals(r.identity()) && result.isEmpty() && firstReads.incrementAndGet() <= 2) {
                    try { barrier.await(10, TimeUnit.SECONDS); }
                    catch (Exception e) { throw new IllegalStateException("Concurrent fixture barrier failed"); }
                }
                return result;
            }
            @Override AcceptedAssessment insert(AcceptanceRequest request, CanonicalAcceptance.Document accepted,
                    CanonicalAcceptance.Document config, java.time.Instant created) {
                try { return super.insert(request, accepted, config, created); }
                catch (SubmissionCollision collision) { collisions.incrementAndGet(); throw collision; }
            }
        };
        var concurrentService = service(repo);
        var results = OracleAcceptanceWorkers.pair(() -> concurrentService.accept(r), 30000, () -> cleanupAllowed = false,
                event -> System.out.println("Concurrent test lifecycle: " + event));
        assertEquals(results.get(0), results.get(1)); assertEquals(1, collisions.get(), "Exercise the actual Oracle uniqueness collision path");
        checked(() -> assertCounts(r, results.get(0), 1));
    }
    int namespaceCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM D2_RISK.RISK_ASSESSMENTS WHERE SUBMISSION_SOURCE = ?", Integer.class, fixtures.namespace); }
    int parentCount(AcceptanceRequest r) { return jdbc.queryForObject("SELECT COUNT(*) FROM D2_RISK.RISK_ASSESSMENTS WHERE SUBMISSION_SOURCE = ? AND SUBMISSION_KEY = ?", Integer.class, r.identity().source(), r.identity().key()); }
    void assertCounts(AcceptanceRequest r, AcceptedAssessment saved, int count) {
        assertEquals(count, parentCount(r));
        assertEquals((long) count, jdbc.queryForObject("SELECT COUNT(*) FROM D2_RISK.RISK_CONFIGURATION_SNAPSHOTS WHERE ASSESSMENT_ID = ?", Long.class, Long.parseLong(saved.assessmentId())));
        assertEquals((long) count, jdbc.queryForObject("SELECT COUNT(*) FROM D2_RISK.RISK_ASSESSMENTS A JOIN D2_RISK.RISK_CONFIGURATION_SNAPSHOTS S ON S.ASSESSMENT_ID=A.ASSESSMENT_ID AND S.SNAPSHOT_ID=A.CONFIGURATION_SNAPSHOT_ID WHERE A.SUBMISSION_SOURCE=? AND A.SUBMISSION_KEY=?", Long.class, r.identity().source(), r.identity().key()));
    }
    List<Map<String, Object>> fingerprint(AcceptanceRequest r, AcceptedAssessment saved) {
        return List.of(jdbc.queryForObject("SELECT * FROM D2_RISK.RISK_ASSESSMENTS WHERE SUBMISSION_SOURCE=? AND SUBMISSION_KEY=?", OracleAcceptancePersistenceTest::immutableRow, r.identity().source(), r.identity().key()),
                jdbc.queryForObject("SELECT * FROM D2_RISK.RISK_CONFIGURATION_SNAPSHOTS WHERE ASSESSMENT_ID=?", OracleAcceptancePersistenceTest::immutableRow, Long.parseLong(saved.assessmentId())));
    }
    static Map<String, Object> immutableRow(ResultSet rs, int row) throws SQLException {
        var result = new TreeMap<String, Object>(); var columns = rs.getMetaData();
        for (int i = 1; i <= columns.getColumnCount(); i++) {
            String type = columns.getColumnTypeName(i); Object value;
            if (type.equals("CLOB")) {
                try (var reader = rs.getCharacterStream(i)) {
                    if (reader == null) value = null;
                    else { var text = new StringWriter(); reader.transferTo(text); value = text.toString(); }
                } catch (IOException e) { throw new SQLException("Synthetic CLOB read failed"); }
            } else if (type.startsWith("TIMESTAMP")) {
                var time = rs.getObject(i, OffsetDateTime.class); value = time == null ? null : time.toInstant();
            } else if (type.equals("NUMBER")) value = rs.getBigDecimal(i);
            else value = rs.getString(i);
            result.put(columns.getColumnLabel(i), value);
        }
        return result;
    }
    void checked(Runnable work) {
        try { work.run(); }
        catch (AcceptanceFailure failure) { diagnostics.print(); throw failure; }
        catch (DataAccessException | TransactionException failure) { diagnostics.record("TEST_READ_OR_TRANSACTION", failure); diagnostics.print(); throw new AssertionError("Live Oracle acceptance verification failed; synthetic namespace: " + fixtures.namespace); }
    }
    static void requireInjectionReached(FailingJdbc jdbc, int stage) {
        assertEquals(stage, jdbc.injectedAt.get(), "Intended post-insert failure must be reached; an earlier persistence failure is not rollback proof");
    }
    static final class InjectedFailure extends RuntimeException {}
    static final class FailingJdbc extends OracleAcceptanceDiagnostics.DiagnosticJdbc {
        final int stage; final AtomicInteger inserts = new AtomicInteger(); final AtomicReference<Long> parentId = new AtomicReference<>();
        final AtomicInteger injectedAt = new AtomicInteger();
        FailingJdbc(DataSource source, int stage) { this(source, stage, new OracleAcceptanceDiagnostics()); }
        FailingJdbc(DataSource source, int stage, OracleAcceptanceDiagnostics diagnostics) { super(source, diagnostics); this.stage = stage; }
        @Override public <T> T execute(PreparedStatementCreator creator, PreparedStatementCallback<T> action) {
            T result = super.execute(creator, action); int inserted = inserts.incrementAndGet();
            if (inserted == 1) parentId.set((Long) result);
            if (inserted == stage) { assertTrue(TransactionSynchronizationManager.isActualTransactionActive()); injectedAt.set(stage); throw new InjectedFailure(); }
            return result;
        }
    }
}
