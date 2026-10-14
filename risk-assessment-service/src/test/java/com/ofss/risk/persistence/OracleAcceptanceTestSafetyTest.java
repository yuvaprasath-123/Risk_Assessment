package com.ofss.risk.persistence;

import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Offline guards and failure-hook tests. No Oracle credentials, connections or environment reads. */
class OracleAcceptanceTestSafetyTest {
    final String namespace = OracleAcceptanceFixtures.PREFIX + "12345678-1234-1234-1234-123456789abc";
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    final TransactionStatus status = new SimpleTransactionStatus();
    OracleAcceptanceFixtureCleaner cleaner;
    @BeforeEach void setup() {
        when(manager.getTransaction(any())).thenReturn(status);
        cleaner = new OracleAcceptanceFixtureCleaner(jdbc, manager);
    }
    OracleAcceptanceFixtureCleaner.Row row(String key, String state, String evaluator, String validation, Object input, long attempts) {
        return new OracleAcceptanceFixtureCleaner.Row(17, key, state, evaluator, validation, BigDecimal.valueOf(23), input, null, attempts, 0, 0);
    }
    OracleAcceptanceFixtureCleaner.Row validRow() { return row("creation", "ACCEPTED", OracleAcceptanceFixtures.EVALUATOR, "synthetic-pass/creation", null, 0); }
    @SuppressWarnings("unchecked")
    void rows(List<OracleAcceptanceFixtureCleaner.Row> rows) {
        when(jdbc.query(eq(OracleAcceptanceFixtureCleaner.LOCK), any(RowMapper.class), eq(namespace))).thenReturn(rows);
        when(jdbc.queryForObject(eq(OracleAcceptanceFixtureCleaner.DEPENDENTS), eq(Long.class), eq(17L), eq(17L))).thenReturn(0L);
    }
    @ParameterizedTest @ValueSource(strings = {"developer1", "D2_RISK", "D2_RISK_TEST_", "D2_RISK_TEST_not-a-uuid",
            "D2_RISK_TEST_1-1-1-1-1", "D2_RISK_TEST_12345678-1234-1234-1234-123456789ABC", "D2_RISK_TEST_12345678-1234-1234-1234-123456789abc extra"})
    void invalidNamespaceCannotReachTransactionOrSql(String source) {
        assertThrows(IllegalArgumentException.class, () -> cleaner.clean(source));
        verifyNoInteractions(jdbc); verify(manager, never()).getTransaction(any());
    }
    @Test void nullNamespaceIsAlsoRejectedBeforeSql() {
        assertThrows(IllegalArgumentException.class, () -> cleaner.clean(null)); verifyNoInteractions(jdbc);
    }
    @Test void deletionIsExactAndBreaksOnlyTheFixtureSnapshotCycleInOneTransaction() {
        rows(List.of(validRow())); when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        cleaner.clean(namespace);
        var order = inOrder(jdbc, manager);
        order.verify(jdbc).query(eq(OracleAcceptanceFixtureCleaner.LOCK), any(RowMapper.class), eq(namespace));
        order.verify(jdbc).queryForObject(OracleAcceptanceFixtureCleaner.DEPENDENTS, Long.class, 17L, 17L);
        order.verify(jdbc).update(OracleAcceptanceFixtureCleaner.DETACH, 17L, namespace, "creation", OracleAcceptanceFixtures.EVALUATOR, "synthetic-pass/creation");
        order.verify(jdbc).update(OracleAcceptanceFixtureCleaner.DELETE_SNAPSHOT, 17L, 23L, namespace, "creation", OracleAcceptanceFixtures.EVALUATOR, "synthetic-pass/creation");
        order.verify(jdbc).update(OracleAcceptanceFixtureCleaner.DELETE_PARENT, 17L, namespace, "creation", OracleAcceptanceFixtures.EVALUATOR, "synthetic-pass/creation");
        order.verify(manager).commit(status); verify(manager, never()).rollback(any());
        for (var sql : List.of(OracleAcceptanceFixtureCleaner.DETACH, OracleAcceptanceFixtureCleaner.DELETE_SNAPSHOT, OracleAcceptanceFixtureCleaner.DELETE_PARENT)) {
            assertTrue(sql.contains("SUBMISSION_SOURCE = ?") && sql.contains("SUBMISSION_KEY = ?"));
            assertTrue(sql.contains("EVALUATOR_VERSION = ?") && sql.contains("VALIDATION_REFERENCE = ?"));
        }
    }
    @ParameterizedTest @ValueSource(strings = {"KEY", "STATE", "EVALUATOR", "VALIDATION", "FROZEN", "ATTEMPTS", "DEPENDENTS"})
    void unexpectedRowsAreRefusedBeforeAnyMutation(String defect) {
        var r = row(defect.equals("KEY") ? "unrelated-record" : "creation", defect.equals("STATE") ? "COMPLETED" : "ACCEPTED",
                defect.equals("EVALUATOR") ? "another-engine" : OracleAcceptanceFixtures.EVALUATOR,
                defect.equals("VALIDATION") ? "another-pass" : "synthetic-pass/creation", defect.equals("FROZEN") ? BigDecimal.ONE : null, defect.equals("ATTEMPTS") ? 1 : 0);
        rows(List.of(r));
        if (defect.equals("DEPENDENTS")) when(jdbc.queryForObject(OracleAcceptanceFixtureCleaner.DEPENDENTS, Long.class, 17L, 17L)).thenReturn(1L);
        assertThrows(IllegalStateException.class, () -> cleaner.clean(namespace));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        verify(manager).rollback(status); verify(manager, never()).commit(any());
    }
    @Test void lateCleanupFailureRollsBackDetachmentAndDeletionTogether() {
        rows(List.of(validRow())); when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        when(jdbc.update(OracleAcceptanceFixtureCleaner.DELETE_PARENT, 17L, namespace, "creation", OracleAcceptanceFixtures.EVALUATOR, "synthetic-pass/creation")).thenReturn(0);
        assertThrows(IllegalStateException.class, () -> cleaner.clean(namespace));
        verify(jdbc).update(OracleAcceptanceFixtureCleaner.DETACH, 17L, namespace, "creation", OracleAcceptanceFixtures.EVALUATOR, "synthetic-pass/creation");
        verify(manager).rollback(status); verify(manager, never()).commit(any());
    }
    @Test void fixturesHaveSeparateNamespacedIdentitiesExactMoneyAndLargeSyntheticUnicodeSnapshot() {
        var fixtures = new OracleAcceptanceFixtures(); OracleAcceptanceFixtures.requireNamespace(fixtures.namespace);
        assertTrue(fixtures.namespace.length() <= 64);
        var r = fixtures.request("roundtrip"); var c = new CanonicalAcceptance().configuration(r.configuration());
        assertTrue(c.json().length() > 32767); assertTrue(c.json().contains("1.0000000000000001"));
        assertEquals(new BigDecimal("9999999999999.99"), r.current().amount());
        assertNotEquals(r.identity().key(), Long.toString(r.paymentId())); assertNotEquals(r.identity().key(), r.current().submissionId());
        assertEquals(123456789, r.cutoff().getNano());
        assertThrows(IllegalArgumentException.class, () -> fixtures.request("unrelated-record"));
    }
    @ParameterizedTest @ValueSource(ints = {1, 2})
    void injectionHooksReallyThrowAfterSuccessfulJdbcExecutionWithinASpringTransaction(int stage) throws Exception {
        var source = mock(DataSource.class); var connection = mock(Connection.class); var statement = mock(PreparedStatement.class);
        when(source.getConnection()).thenReturn(connection); when(connection.getAutoCommit()).thenReturn(true);
        when(connection.prepareStatement("TEST_ONLY_INSERT")).thenReturn(statement); when(statement.executeUpdate()).thenReturn(1);
        var failing = new OracleAcceptancePersistenceTest.FailingJdbc(source, stage);
        var transaction = new TransactionTemplate(new JdbcTransactionManager(source));
        assertThrows(OracleAcceptancePersistenceTest.InjectedFailure.class, () -> transaction.executeWithoutResult(s -> {
            for (int i = 1; i <= stage; i++) {
                long id = i;
                failing.execute((PreparedStatementCreator) c -> c.prepareStatement("TEST_ONLY_INSERT"), p -> { assertEquals(1, p.executeUpdate()); return id; });
            }
        }));
        assertEquals(1L, failing.parentId.get()); verify(statement, times(stage)).executeUpdate();
        OracleAcceptancePersistenceTest.requireInjectionReached(failing, stage);
        verify(connection).rollback(); verify(connection, never()).commit();
    }
    @Test void validatesEveryRowBeforeDeletingAnyFixture() {
        var later = new OracleAcceptanceFixtureCleaner.Row(18, "retries", "COMPLETED", OracleAcceptanceFixtures.EVALUATOR,
                "synthetic-pass/retries", BigDecimal.valueOf(24), null, null, 0, 0, 0);
        rows(List.of(validRow(), later));
        assertThrows(IllegalStateException.class, () -> cleaner.clean(namespace));
        verify(jdbc, never()).update(anyString(), any(Object[].class)); verify(manager).rollback(status);
    }
}
