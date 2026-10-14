package com.ofss.risk.persistence;

import java.io.*;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Actual TransactionTemplate + JdbcTransactionManager + JdbcTemplate; mocked JDBC, NOT Oracle. */
class RiskAcceptanceJdbcTransactionTest {
    final DataSource source = mock(DataSource.class);
    final Connection connection = mock(Connection.class);
    final Clob parentClob = mock(Clob.class), snapshotClob = mock(Clob.class);
    final PreparedStatement find = mock(PreparedStatement.class), parent = mock(PreparedStatement.class),
            snapshot = mock(PreparedStatement.class), associate = mock(PreparedStatement.class);
    final ArrayDeque<ResultSet> queries = new ArrayDeque<>();
    final CanonicalAcceptance canonical = new CanonicalAcceptance();
    final AcceptanceRequest original = AcceptanceFixtures.request(AcceptanceFixtures.config());
    RiskAcceptanceService service;

    @BeforeEach void setup() throws Exception {
        when(source.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        when(connection.createClob()).thenReturn(parentClob, snapshotClob);
        when(parentClob.setString(eq(1L), anyString())).thenAnswer(c -> ((String)c.getArgument(1)).length());
        when(snapshotClob.setString(eq(1L), anyString())).thenAnswer(c -> ((String)c.getArgument(1)).length());
        when(connection.prepareStatement(RiskAcceptanceRepository.FIND)).thenReturn(find);
        when(connection.prepareStatement(eq(RiskAcceptanceRepository.INSERT), any(String[].class))).thenReturn(parent);
        when(connection.prepareStatement(eq(RiskAcceptanceRepository.SNAPSHOT), any(String[].class))).thenReturn(snapshot);
        when(connection.prepareStatement(RiskAcceptanceRepository.ASSOCIATE)).thenReturn(associate);
        when(find.executeQuery()).thenAnswer(call -> queries.isEmpty() ? empty() : queries.remove());
        when(parent.executeUpdate()).thenReturn(1); when(snapshot.executeUpdate()).thenReturn(1);
        when(associate.executeUpdate()).thenReturn(1);
        keys(parent, BigDecimal.valueOf(17)); keys(snapshot, BigDecimal.valueOf(23));
        var jdbc = new JdbcTemplate(source); jdbc.setExceptionTranslator(new SQLExceptionSubclassTranslator());
        service = new RiskAcceptanceService(new RiskAcceptanceRepository(jdbc), canonical,
                new JdbcTransactionManager(source), Clock.fixed(AcceptanceFixtures.CREATED, ZoneOffset.UTC));
    }
    ResultSet empty() throws Exception { var rs = mock(ResultSet.class); when(rs.next()).thenReturn(false); return rs; }
    ResultSet row() throws Exception {
        var rs = mock(ResultSet.class); when(rs.next()).thenReturn(true, false);
        var config = canonical.configuration(original.configuration());
        var accepted = canonical.acceptance(original, original.configuration().versionId(), "9", config.hash());
        for (var entry : Map.ofEntries(Map.entry("ASSESSMENT_ID", 17L), Map.entry("CONFIGURATION_SNAPSHOT_ID", 23L),
                Map.entry("SAVED_SNAPSHOT_ID", 23L), Map.entry("SNAPSHOT_PARENT_ID", 17L), Map.entry("PAYMENT_ID", original.paymentId()),
                Map.entry("REQUESTER_ID", original.requesterId()), Map.entry("SUPPLIER_ID", 456L), Map.entry("INVOICE_ID", 123L),
                Map.entry("CONFIGURATION_VERSION_ID", 9007199254740993L), Map.entry("SOURCE_VERSION_ID", 9007199254740993L),
                Map.entry("CONFIGURATION_ROW_VERSION", 9L), Map.entry("SOURCE_ROW_VERSION", 9L), Map.entry("ROW_VERSION", 0L)).entrySet())
            when(rs.getObject(entry.getKey())).thenReturn(BigDecimal.valueOf(entry.getValue()));
        for (var entry : Map.ofEntries(Map.entry("HASH_FORMAT_VERSION", CanonicalAcceptance.HASH_FORMAT),
                Map.entry("SNAPSHOT_HASH_FORMAT", CanonicalAcceptance.HASH_FORMAT), Map.entry("SNAPSHOT_FORMAT_VERSION", CanonicalAcceptance.SNAPSHOT_FORMAT),
                Map.entry("SUBMISSION_SOURCE", original.identity().source()), Map.entry("SUBMISSION_KEY", original.identity().key()),
                Map.entry("EVALUATOR_VERSION", original.evaluatorVersion()), Map.entry("VALIDATION_REFERENCE", original.validationReference()),
                Map.entry("CURRENCY_CODE", "INR"), Map.entry("WORK_STATE", "ACCEPTED"), Map.entry("ACCEPTANCE_HASH", accepted.hash()),
                Map.entry("CONFIGURATION_HASH", config.hash()), Map.entry("SNAPSHOT_HASH", config.hash())).entrySet())
            when(rs.getString(entry.getKey())).thenReturn(entry.getValue());
        when(rs.getCharacterStream("ACCEPTANCE_JSON")).thenAnswer(c -> new StringReader(accepted.json()));
        when(rs.getCharacterStream("CONFIGURATION_JSON")).thenAnswer(c -> new StringReader(config.json()));
        when(rs.getObject("EVIDENCE_AS_OF", OffsetDateTime.class)).thenReturn(original.cutoff().atOffset(ZoneOffset.ofHoursMinutes(5,30)));
        when(rs.getObject("CREATED_AT", OffsetDateTime.class)).thenReturn(AcceptanceFixtures.CREATED.atOffset(ZoneOffset.UTC));
        when(rs.getObject("CAPTURED_AT", OffsetDateTime.class)).thenReturn(AcceptanceFixtures.CREATED.atOffset(ZoneOffset.UTC));
        when(rs.getBigDecimal("PAYMENT_AMOUNT")).thenReturn(original.current().amount());
        return rs;
    }
    void keys(PreparedStatement statement, Object value) throws Exception {
        var keys = mock(ResultSet.class); var metadata = mock(ResultSetMetaData.class);
        when(statement.getGeneratedKeys()).thenReturn(keys); when(keys.next()).thenReturn(true, false);
        when(keys.getMetaData()).thenReturn(metadata); when(metadata.getColumnCount()).thenReturn(1);
        when(keys.getObject(1)).thenReturn(value);
    }
    void rollback() throws Exception {
        verify(connection).rollback(); verify(connection, never()).commit(); verify(source).getConnection();
    }
    AcceptanceFailure failure(AcceptanceFailure.Code code, AcceptanceRequest request) {
        var e = assertThrows(AcceptanceFailure.class, () -> service.accept(request));
        assertEquals(code, e.code()); assertNull(e.getCause());
        assertFalse(e.getMessage().contains("ORA-")); assertFalse(e.getMessage().contains("private")); return e;
    }
    @Test void createsBothRowsAssociatesVerifiesThenCommitsOnOneConnection() throws Exception {
        queries.add(empty()); queries.add(row());
        var saved = service.accept(original);
        assertEquals("17", saved.assessmentId()); assertEquals("23", saved.configurationSnapshotId());
        assertEquals("ACCEPTED", saved.workState()); assertEquals("0", saved.rowVersion());
        assertEquals(original.cutoff(), saved.cutoff()); assertEquals(AcceptanceFixtures.CREATED, saved.createdAt());
        verify(source).getConnection(); verify(connection, never()).rollback();
        var order = inOrder(parent, snapshot, associate, find, connection);
        order.verify(parent).executeUpdate(); order.verify(snapshot).executeUpdate(); order.verify(associate).executeUpdate();
        order.verify(find).executeQuery(); order.verify(connection).commit();
        verify(connection).prepareStatement(RiskAcceptanceRepository.INSERT, new String[]{"ASSESSMENT_ID"});
        verify(connection).prepareStatement(RiskAcceptanceRepository.SNAPSHOT, new String[]{"SNAPSHOT_ID"});
        verify(parent).setLong(3, Long.MAX_VALUE); verify(parent).setLong(4, 456); verify(parent).setLong(5, 123);
        verify(parent).setBigDecimal(9, new BigDecimal("9999999999999.99"));
        verify(parent).setNull(8, Types.VARCHAR); verify(parent).setNull(20, Types.VARCHAR);
        verify(parent).setObject(18, original.cutoff().atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE);
        verify(parent).setObject(19, AcceptanceFixtures.CREATED.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE);
        verify(snapshot).setObject(8, AcceptanceFixtures.CREATED.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE);
        var accepted = canonical.acceptance(original, original.configuration().versionId(), "9", canonical.configuration(original.configuration()).hash());
        verify(parentClob).setString(1L, accepted.json()); verify(parent).setClob(15, parentClob);
        verify(snapshotClob).setString(1L, canonical.configuration(original.configuration()).json()); verify(snapshot).setClob(6, snapshotClob);
        verify(parentClob).free(); verify(snapshotClob).free();
        verify(parent, never()).setCharacterStream(anyInt(), any(Reader.class), anyLong());
        verify(snapshot, never()).setCharacterStream(anyInt(), any(Reader.class), anyLong());
        verify(associate).setObject(1, 23L); verify(associate).setObject(2, 17L);
    }
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void retriesReusePinnedConfigurationWithoutAnyWrite(boolean explicitConfiguration) throws Exception {
        queries.add(row());
        var retry = AcceptanceFixtures.copy(original, explicitConfiguration ? original.configuration() : null,
                original.paymentId(), null, original.cutoff(), original.currentAndRetrySubmissionIds(), "different transport");
        var saved = service.accept(retry);
        assertEquals("17", saved.assessmentId()); assertEquals("23", saved.configurationSnapshotId());
        assertEquals(canonical.configuration(original.configuration()).json(), saved.configurationJson());
        verify(parent, never()).executeUpdate(); verify(snapshot, never()).executeUpdate(); verify(associate, never()).executeUpdate();
        verify(connection).commit(); verify(connection, never()).rollback();
    }
    @ParameterizedTest @ValueSource(strings = {"PAYMENT", "VALIDATION", "CUTOFF", "EXCLUSIONS", "CONFIGURATION"})
    void conflictsNeverChangeExistingRows(String field) throws Exception {
        queries.add(row());
        var c = original.configuration();
        if (field.equals("CONFIGURATION")) c = new com.ofss.risk.engine.RiskConfiguration("2", "9", c.contentHash(), "INR", c.maxFinalScore(), c.rules(), c.bands(), c.approvalBoundaries());
        var r = AcceptanceFixtures.copy(original, c, field.equals("PAYMENT") ? 1 : original.paymentId(),
                field.equals("VALIDATION") ? "revision2" : null, field.equals("CUTOFF") ? original.cutoff().plusNanos(1) : original.cutoff(),
                field.equals("EXCLUSIONS") ? Set.of("opaque-engine-id") : original.currentAndRetrySubmissionIds(), null);
        failure(AcceptanceFailure.Code.SUBMISSION_CONFLICT, r); rollback();
        verify(parent, never()).executeUpdate(); verify(snapshot, never()).executeUpdate(); verify(associate, never()).executeUpdate();
    }
    @Test void firstAcceptanceRequiresExplicitConfigurationAndNeverLooksUpActiveVersion() throws Exception {
        failure(AcceptanceFailure.Code.CONFIGURATION_REQUIRED, AcceptanceFixtures.copy(original, null, original.paymentId(), null,
                original.cutoff(), original.currentAndRetrySubmissionIds(), null)); rollback(); verify(parent, never()).executeUpdate();
    }
    static Stream<Arguments> badKeys() {
        return Stream.of(Arguments.of((Object) null), Arguments.of(BigDecimal.ZERO), Arguments.of(new BigDecimal("-1")),
                Arguments.of(new BigDecimal("1.5")), Arguments.of(new BigDecimal("9223372036854775808")),
                Arguments.of("ROWID"), Arguments.of(1.0d));
    }
    @ParameterizedTest @MethodSource("badKeys") void invalidParentIdentityRollsBack(Object key) throws Exception {
        keys(parent, key); failure(AcceptanceFailure.Code.RISK_PERSISTENCE_UNAVAILABLE, original); rollback();
        verify(snapshot, never()).executeUpdate(); verify(associate, never()).executeUpdate();
    }
    @ParameterizedTest @MethodSource("badKeys") void invalidSnapshotIdentityRollsBackParentAndSnapshot(Object key) throws Exception {
        keys(snapshot, key); failure(AcceptanceFailure.Code.RISK_PERSISTENCE_UNAVAILABLE, original); rollback();
        verify(parent).executeUpdate(); verify(snapshot).executeUpdate(); verify(associate, never()).executeUpdate();
    }
    @ParameterizedTest @ValueSource(strings = {"SNAPSHOT_INSERT", "ASSOCIATION", "NO_READBACK", "READBACK_SQL", "MAPPING", "HASH", "ASSOCIATION_MISMATCH", "TIMESTAMP"})
    void partialAcceptanceCannotCommitOrReturnSuccess(String failure) throws Exception {
        queries.add(empty());
        switch (failure) {
            case "SNAPSHOT_INSERT" -> when(snapshot.executeUpdate()).thenThrow(new SQLRecoverableException("private data", "08006", 17002));
            case "ASSOCIATION" -> when(associate.executeUpdate()).thenReturn(0);
            case "READBACK_SQL" -> { var initial = empty(); when(find.executeQuery()).thenReturn(initial).thenThrow(new SQLRecoverableException("private data", "08006", 17002)); }
            case "MAPPING" -> { var rs = row(); when(rs.getObject("ASSESSMENT_ID")).thenReturn(new BigDecimal("1.5")); queries.add(rs); }
            case "HASH" -> { var rs = row(); when(rs.getString("CONFIGURATION_HASH")).thenReturn("0".repeat(64)); queries.add(rs); }
            case "ASSOCIATION_MISMATCH" -> { var rs = row(); when(rs.getObject("SAVED_SNAPSHOT_ID")).thenReturn(BigDecimal.ONE); queries.add(rs); }
            case "TIMESTAMP" -> { var rs = row(); when(rs.getObject("CAPTURED_AT", OffsetDateTime.class)).thenReturn(AcceptanceFixtures.CREATED.minusNanos(1).atOffset(ZoneOffset.UTC)); queries.add(rs); }
            default -> { /* empty readback */ }
        }
        failure(AcceptanceFailure.Code.RISK_PERSISTENCE_UNAVAILABLE, original); rollback(); verify(parent).executeUpdate();
    }
    static Stream<Arguments> constraints() {
        return Stream.of(Arguments.of("D2_RISK.UK_RSK_SUBMISSION", true), Arguments.of("\"D2_RISK\".\"UK_RSK_SUBMISSION\"", true),
                Arguments.of("D2_RISK.PK_RSK_ASM", false), Arguments.of("D2_RISK.UNKNOWN", false),
                Arguments.of("OTHER.UK_RSK_SUBMISSION", false), Arguments.of("D2_RISK.UK_RSK_SUBMISSION_EXTRA", false));
    }
    @ParameterizedTest @MethodSource("constraints")
    void onlySubmissionInsertCollisionReusesConcurrentWinner(String constraint, boolean recognized) throws Exception {
        when(parent.executeUpdate()).thenThrow(new SQLIntegrityConstraintViolationException("ORA-00001: unique constraint (" + constraint + ") violated", "23000", 1));
        queries.add(empty()); queries.add(row());
        if (recognized) {
            assertEquals("17", service.accept(original).assessmentId());
            var order = inOrder(connection, find);
            order.verify(find).executeQuery(); order.verify(connection).rollback(); order.verify(find).executeQuery(); order.verify(connection).commit();
            verify(source, times(2)).getConnection();
        } else { failure(AcceptanceFailure.Code.RISK_PERSISTENCE_UNAVAILABLE, original); rollback(); verify(find).executeQuery(); }
        verify(snapshot, never()).executeUpdate(); verify(associate, never()).executeUpdate();
    }
    @Test void concurrentWinnerWithDifferentAcceptedFactsReturnsConflictAfterLosingTransactionRollback() throws Exception {
        when(parent.executeUpdate()).thenThrow(collision()); queries.add(empty()); queries.add(row());
        failure(AcceptanceFailure.Code.SUBMISSION_CONFLICT, AcceptanceFixtures.copy(original, original.configuration(), 7, null,
                original.cutoff(), original.currentAndRetrySubmissionIds(), null));
        verify(connection, times(2)).rollback(); verify(connection, never()).commit(); verify(source, times(2)).getConnection();
        verify(snapshot, never()).executeUpdate();
    }
    @Test void missingConcurrentWinnerIsUnavailableAndDoesNotAttemptAnotherInsert() throws Exception {
        when(parent.executeUpdate()).thenThrow(collision());
        failure(AcceptanceFailure.Code.RISK_PERSISTENCE_UNAVAILABLE, original);
        verify(connection, times(2)).rollback(); verify(parent).executeUpdate(); verify(snapshot, never()).executeUpdate();
    }
    @Test void duplicateFromGeneratedKeyReadOrSnapshotIsNotAnIdentityRace() throws Exception {
        when(parent.getGeneratedKeys()).thenThrow(collision());
        failure(AcceptanceFailure.Code.RISK_PERSISTENCE_UNAVAILABLE, original); rollback(); verify(find).executeQuery();
    }
    @Test void snapshotDuplicateCannotCommitParentAndIsNotRetried() throws Exception {
        when(snapshot.executeUpdate()).thenThrow(collision());
        failure(AcceptanceFailure.Code.RISK_PERSISTENCE_UNAVAILABLE, original); rollback(); verify(find).executeQuery();
    }
    @Test void lookupIsReadOnlyAndPreservesStateWithoutClaimingScores() throws Exception {
        var rs = row(); when(rs.getString("WORK_STATE")).thenReturn("COMPLETED"); queries.add(rs);
        assertEquals("COMPLETED", service.find(original.identity()).orElseThrow().workState());
        verify(connection).setReadOnly(true); verify(parent, never()).executeUpdate(); verify(connection).commit();
    }
    @Test void absentLookupHasNoSideEffects() throws Exception {
        assertTrue(service.find(original.identity()).isEmpty()); verify(parent, never()).executeUpdate(); verify(connection).commit();
    }
    @Test void repeatedCallAfterCreationReusesBothIdentitiesAndNeverUpdatesEitherRow() throws Exception {
        queries.add(empty()); queries.add(row()); queries.add(row());
        var first = service.accept(original);
        var second = service.accept(AcceptanceFixtures.copy(original, null, original.paymentId(), null,
                original.cutoff(), original.currentAndRetrySubmissionIds(), "later delivery"));
        assertEquals(first, second);
        verify(parent).executeUpdate(); verify(snapshot).executeUpdate(); verify(associate).executeUpdate();
        verify(connection, times(2)).commit(); verify(connection, never()).rollback();
    }
    @Test void lookupCannotReturnAnAssessmentFromAnotherNamespaceOrKey() throws Exception {
        queries.add(row());
        var e = assertThrows(AcceptanceFailure.class, () -> service.find(new SubmissionIdentity("another-source", original.identity().key())));
        assertEquals(AcceptanceFailure.Code.RISK_PERSISTENCE_UNAVAILABLE, e.code()); rollback();
    }
    @Test void corruptStoredSnapshotDoesNotLookLikeAConflictOrAbsence() throws Exception {
        var rs = row(); when(rs.getCharacterStream("CONFIGURATION_JSON")).thenAnswer(c -> new StringReader("{}")); queries.add(rs);
        var e = assertThrows(AcceptanceFailure.class, () -> service.find(original.identity()));
        assertEquals(AcceptanceFailure.Code.RISK_PERSISTENCE_UNAVAILABLE, e.code()); rollback();
    }
    @Test void repositoryRefusesUnprotectedWritesBeforeIssuingSql() throws Exception {
        var repo = new RiskAcceptanceRepository(new JdbcTemplate(source));
        var config = canonical.configuration(original.configuration());
        assertThrows(org.springframework.dao.DataRetrievalFailureException.class, () -> repo.insert(original,
                canonical.acceptance(original, original.configuration().versionId(), "9", config.hash()), config, AcceptanceFixtures.CREATED));
        verify(source, never()).getConnection(); verify(parent, never()).executeUpdate();
    }
    @Test void commitFailureCannotReturnAnAcceptedResultOrExposeDiagnostics() throws Exception {
        queries.add(empty()); queries.add(row());
        doThrow(new SQLRecoverableException("private commit diagnostic", "08006", 17002)).when(connection).commit();
        failure(AcceptanceFailure.Code.RISK_PERSISTENCE_UNAVAILABLE, original);
        verify(parent).executeUpdate(); verify(snapshot).executeUpdate(); verify(associate).executeUpdate();
        // Commit outcome can be ambiguous; the next call must lookup the durable identity, never blindly recreate it.
    }
    @ParameterizedTest @ValueSource(strings = {"CREATE", "SHORT_WRITE", "BIND", "FREE"})
    void temporaryClobFailuresRollBackAndKeepProductionErrorsSanitized(String phase) throws Exception {
        switch (phase) {
            case "CREATE" -> when(connection.createClob()).thenThrow(new SQLException("private credential/payload", "99999", 17002));
            case "SHORT_WRITE" -> when(parentClob.setString(eq(1L), anyString())).thenReturn(1);
            case "BIND" -> doThrow(new SQLException("private credential/payload", "99999", 17004)).when(parent).setClob(15, parentClob);
            case "FREE" -> doThrow(new SQLException("private credential/payload", "99999", 17002)).when(parentClob).free();
        }
        failure(AcceptanceFailure.Code.RISK_PERSISTENCE_UNAVAILABLE, original); rollback();
        if (!phase.equals("CREATE")) verify(parentClob).free();
        verify(parent).close(); verify(snapshot, never()).executeUpdate();
    }
    @Test void largeUnicodeClobUsesALocatorHeldThroughGeneratedKeyRetrievalAndFreedAfterFailure() throws Exception {
        var c = original.configuration();
        var large = new com.ofss.risk.engine.RiskConfiguration(c.versionId(), c.rowVersion(), "ಸಾಕ್ಷಿ 😀 \"\\\n".repeat(8192),
                c.currencyCode(), c.maxFinalScore(), c.rules(), c.bands(), c.approvalBoundaries());
        var request = AcceptanceFixtures.copy(original, large, original.paymentId(), null, original.cutoff(), original.currentAndRetrySubmissionIds(), null);
        var document = canonical.configuration(large); assertTrue(document.json().length() > 32767);
        when(snapshot.getGeneratedKeys()).thenThrow(new SQLRecoverableException("private diagnostic", "08006", 17002));
        failure(AcceptanceFailure.Code.RISK_PERSISTENCE_UNAVAILABLE, request); rollback();
        var order = inOrder(snapshotClob, snapshot, connection);
        order.verify(snapshotClob).setString(1L, document.json()); order.verify(snapshot).setClob(6, snapshotClob);
        order.verify(snapshot).executeUpdate(); order.verify(snapshot).getGeneratedKeys();
        order.verify(snapshotClob).free(); order.verify(connection).rollback();
        verify(parentClob).free(); verify(snapshot, never()).setCharacterStream(anyInt(), any(Reader.class), anyLong());
    }
    private SQLException collision() { return new SQLIntegrityConstraintViolationException("ORA-00001: unique constraint (D2_RISK.UK_RSK_SUBMISSION) violated", "23000", 1); }
}
