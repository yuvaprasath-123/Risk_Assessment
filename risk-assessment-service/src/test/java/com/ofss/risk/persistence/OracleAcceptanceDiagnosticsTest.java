package com.ofss.risk.persistence;

import java.sql.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.PreparedStatementCreator;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OracleAcceptanceDiagnosticsTest {
    @Test void causeNextAndSuppressedSqlCodesAreRecoveredWithoutMessagesValuesOrUnknownIdentities() {
        var diagnostics = new OracleAcceptanceDiagnostics();
        var original = new SQLException("private-password payload-value jdbc:oracle:thin:secret ORA-22816", "99999", 22816);
        var duplicate = new SQLException("ORA-00001: unique constraint (D2_RISK.UK_RSK_SUBMISSION) violated; private-password", "23000", 1);
        original.setNextException(duplicate); original.addSuppressed(new SQLException("private-password payload-value", "secret-state", 17909));
        diagnostics.record("SNAPSHOT_INSERT.executeUpdate", new IllegalStateException("private-password", original));
        String safe = diagnostics.summary();
        assertTrue(safe.contains("22816") && safe.contains("17909") && safe.contains("UK_RSK_SUBMISSION"));
        assertFalse(safe.contains("private-password") || safe.contains("payload-value") || safe.contains("jdbc:oracle") || safe.contains("secret-state"));
    }
    @Test void jdbcCaptureRecordsTheOriginalPhaseBeforeAProductionBoundaryCanDiscardTheCause() throws Exception {
        var diagnostics = new OracleAcceptanceDiagnostics(); var ds = mock(DataSource.class); var c = mock(Connection.class); var ps = mock(PreparedStatement.class);
        when(ds.getConnection()).thenReturn(c); when(c.prepareStatement(RiskAcceptanceRepository.SNAPSHOT, new String[]{"SNAPSHOT_ID"})).thenReturn(ps);
        when(ps.executeUpdate()).thenThrow(new SQLException("private-sensitive-payload", "99999", 22816));
        assertThrows(RuntimeException.class, () -> diagnostics.jdbc(ds).execute((PreparedStatementCreator) connection ->
                connection.prepareStatement(RiskAcceptanceRepository.SNAPSHOT, new String[]{"SNAPSHOT_ID"}), PreparedStatement::executeUpdate));
        assertTrue(diagnostics.events().stream().anyMatch(e -> e.phase().equals("SNAPSHOT_INSERT.executeUpdate") && Integer.valueOf(22816).equals(e.vendorCode())));
        assertFalse(diagnostics.summary().contains("private-sensitive-payload"));
    }
    @ParameterizedTest @ValueSource(ints = {1, 2, 3})
    void rollbackCheckRejectsEarlierFailuresEvenWhenParentIdentityExists(int stage) {
        var hook = new OracleAcceptancePersistenceTest.FailingJdbc(mock(DataSource.class), stage);
        hook.parentId.set(17L);
        assertThrows(AssertionError.class, () -> OracleAcceptancePersistenceTest.requireInjectionReached(hook, stage));
        hook.injectedAt.set(stage); assertDoesNotThrow(() -> OracleAcceptancePersistenceTest.requireInjectionReached(hook, stage));
    }
    @Test void oneWorkerFailureDoesNotInterruptTheOtherWorkerOrHideItsOutcome() {
        var calls = new AtomicInteger(); var completed = new AtomicBoolean(); var interrupted = new AtomicBoolean(); var cleanupDeferred = new AtomicBoolean();
        assertThrows(AssertionError.class, () -> OracleAcceptanceWorkers.pair(() -> {
            if (calls.incrementAndGet() == 1) throw new IllegalStateException("private failure message");
            try { Thread.sleep(30); completed.set(true); return "done"; }
            catch (InterruptedException e) { interrupted.set(true); throw e; }
        }, 1000, () -> cleanupDeferred.set(true)));
        assertTrue(completed.get()); assertFalse(interrupted.get()); assertFalse(cleanupDeferred.get());
    }
    @Test void bothSuccessfulWorkersAreReturnedOnlyAfterTermination() {
        var finished = new AtomicInteger();
        var results = OracleAcceptanceWorkers.pair(() -> { finished.incrementAndGet(); return "done"; }, 1000, () -> fail("Unexpected termination uncertainty"));
        assertEquals(2, results.size()); assertEquals(2, finished.get());
    }
    @Test void timeoutCancellationIsExplicitAndTerminationIsObservedBeforeReturning() {
        var finished = new AtomicInteger(); var gate = new CountDownLatch(1);
        var lifecycle = new java.util.concurrent.CopyOnWriteArrayList<String>();
        assertThrows(AssertionError.class, () -> OracleAcceptanceWorkers.pair(() -> {
            try { gate.await(); return "never"; } finally { finished.incrementAndGet(); }
        }, 100, () -> fail("Interruptible workers should terminate"), lifecycle::add));
        assertEquals(2, finished.get());
        assertTrue(lifecycle.contains("worker-1:TIMEOUT_CANCEL")); assertTrue(lifecycle.contains("worker-2:TIMEOUT_CANCEL"));
        assertEquals("workers:TERMINATED", lifecycle.getLast());
    }
}
