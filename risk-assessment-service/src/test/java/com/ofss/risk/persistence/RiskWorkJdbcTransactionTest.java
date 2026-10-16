package com.ofss.risk.persistence;

import java.io.Reader;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real TransactionTemplate/JdbcTransactionManager/JdbcTemplate, mocked JDBC; NOT live Oracle. */
class RiskWorkJdbcTransactionTest {
    final DataSource ds=mock(DataSource.class); final Connection connection=mock(Connection.class);
    final PreparedStatement lock=mock(PreparedStatement.class), read=mock(PreparedStatement.class),
        clock=mock(PreparedStatement.class), update=mock(PreparedStatement.class), diagnostic=mock(PreparedStatement.class);
    final ArrayDeque<ResultSet> locks=new ArrayDeque<>(), reads=new ArrayDeque<>();
    RiskWorkRepository repository; RiskWorkService service;
    final Instant now=RiskWorkServiceTest.NOW;
    @BeforeEach void setup() throws Exception {
        when(ds.getConnection()).thenReturn(connection); when(connection.getAutoCommit()).thenReturn(true);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        when(connection.prepareStatement(RiskWorkRepository.LOCK)).thenReturn(lock);
        when(connection.prepareStatement(RiskWorkRepository.READ)).thenReturn(read);
        when(connection.prepareStatement(OracleLeaseClock.SQL)).thenReturn(clock);
        for(String sql:List.of(RiskWorkRepository.CLAIM_UPDATE,RiskWorkRepository.LIVE_UPDATE,RiskWorkRepository.TAKEOVER_UPDATE))
            when(connection.prepareStatement(sql)).thenReturn(update);
        when(connection.prepareStatement(RiskWorkRepository.DIAGNOSTIC)).thenReturn(diagnostic);
        when(lock.executeQuery()).thenAnswer(c -> locks.remove()); when(read.executeQuery()).thenAnswer(c -> reads.remove());
        when(clock.executeQuery()).thenAnswer(c -> {
            var rs=mock(ResultSet.class); when(rs.next()).thenReturn(true,false);
            when(rs.getObject("LEASE_NOW",OffsetDateTime.class)).thenReturn(now.atOffset(ZoneOffset.ofHoursMinutes(5,30))); return rs;
        });
        when(update.executeUpdate()).thenReturn(1); when(diagnostic.executeUpdate()).thenReturn(1);
        var jdbc=new JdbcTemplate(ds); jdbc.setExceptionTranslator(new SQLExceptionSubclassTranslator());
        repository=new RiskWorkRepository(jdbc);
        service=new RiskWorkService(repository,new JdbcTransactionManager(ds),new OracleLeaseClock(jdbc),Duration.ofSeconds(30),()->"new-token");
    }
    ResultSet row(RiskWorkRepository.Row row) throws Exception {
        var rs=mock(ResultSet.class); when(rs.next()).thenReturn(true,false);
        for(var e:Map.of("ASSESSMENT_ID",row.id(),"CONFIGURATION_SNAPSHOT_ID",row.snapshot(),"LEASE_EPOCH",row.epoch(),
            "ATTEMPT_COUNT",(long)row.attempt(),"ROW_VERSION",row.revision()).entrySet())
            when(rs.getObject(e.getKey())).thenReturn(BigDecimal.valueOf(e.getValue()));
        when(rs.getString("WORK_STATE")).thenReturn(row.state()); when(rs.getString("LEASE_TOKEN")).thenReturn(row.token());
        when(rs.getString("LAST_ERROR_CODE")).thenReturn(row.errorCode()); when(rs.getString("LAST_ERROR_MESSAGE")).thenReturn(row.errorMessage());
        when(rs.getObject("CREATED_AT",OffsetDateTime.class)).thenReturn(row.created().atOffset(ZoneOffset.UTC));
        when(rs.getObject("EVIDENCE_AS_OF",OffsetDateTime.class)).thenReturn(row.cutoff().atOffset(ZoneOffset.UTC));
        when(rs.getObject("LEASE_EXPIRES_AT",OffsetDateTime.class)).thenReturn(row.expiry()==null?null:row.expiry().atOffset(ZoneOffset.UTC));
        return rs;
    }
    RiskWorkRepository.Row claimed() {
        var before=RiskWorkServiceTest.idle();
        return new RiskWorkRepository.Row(17,23,"IN_PROGRESS","new-token",1,1,1,now.plusSeconds(30),before.created(),before.cutoff(),null,null);
    }
    void rollback() throws Exception { verify(connection).rollback(); verify(connection,never()).commit(); verify(ds).getConnection(); }
    void failure(WorkFailure.Code code,Runnable operation) {
        var e=assertThrows(WorkFailure.class,operation::run); assertEquals(code,e.code()); assertNull(e.getCause());
        assertFalse(e.getMessage().contains("ORA-")); assertFalse(e.getMessage().contains("private"));
    }
    @Test void claimLocksThenReadsDatabaseClockUpdatesReadsBackAndCommits() throws Exception {
        locks.add(row(RiskWorkServiceTest.idle())); reads.add(row(claimed()));
        assertEquals(claimed().lease(),service.claim("17","0"));
        var order=inOrder(lock,clock,update,read,connection);
        order.verify(lock).executeQuery(); order.verify(clock).executeQuery(); order.verify(update).executeUpdate();
        order.verify(read).executeQuery(); order.verify(connection).commit();
        verify(ds).getConnection(); verify(connection,never()).rollback();
        verify(update).setLong(3,1); verify(update).setInt(4,1); verify(update).setLong(5,1);
        verify(update).setObject(6,now.plusSeconds(30).atOffset(ZoneOffset.UTC),Types.TIMESTAMP_WITH_TIMEZONE);
        verify(update).setNull(14,Types.VARCHAR); verify(update).setNull(16,Types.TIMESTAMP_WITH_TIMEZONE);
    }
    @Test void competingClaimObservesCommittedFenceAndCannotWrite() throws Exception {
        locks.add(row(RiskWorkServiceTest.idle())); reads.add(row(claimed())); locks.add(row(claimed()));
        service.claim("17","0"); failure(WorkFailure.Code.WORK_CONFLICT,()->service.claim("17","0"));
        verify(update).executeUpdate(); verify(connection).commit(); verify(connection).rollback(); verify(ds,times(2)).getConnection();
    }
    @Test void expiredTakeoverBindsOldTokenEpochAttemptRevisionAndExpiry() throws Exception {
        var before=RiskWorkServiceTest.row("IN_PROGRESS",1,1,1,now);
        var after=new RiskWorkRepository.Row(17,23,"IN_PROGRESS","new-token",2,2,2,now.plusSeconds(30),before.created(),before.cutoff(),null,null);
        locks.add(row(before)); reads.add(row(after)); assertEquals(after.lease(),service.claim("17","1"));
        verify(connection).prepareStatement(RiskWorkRepository.TAKEOVER_UPDATE);
        verify(update).setString(14,"old-token"); verify(update).setString(15,"old-token");
        verify(update).setLong(11,1); verify(update).setInt(12,1); verify(update).setLong(13,1);
        verify(update).setObject(16,now.atOffset(ZoneOffset.UTC),Types.TIMESTAMP_WITH_TIMEZONE);
        verify(update).setObject(17,now.atOffset(ZoneOffset.UTC),Types.TIMESTAMP_WITH_TIMEZONE);
    }
    @Test void renewalUsesFreshSqlExpiryGuardAndKeepsOpaqueToken() throws Exception {
        var before=RiskWorkServiceTest.active();
        var after=new RiskWorkRepository.Row(17,23,before.state(),before.token(),1,1,2,now.plusSeconds(30),before.created(),before.cutoff(),null,null);
        locks.add(row(before)); reads.add(row(after)); assertEquals(after.lease(),service.renew(before.lease()));
        verify(connection).prepareStatement(RiskWorkRepository.LIVE_UPDATE); verify(update).setString(2,"old-token");
        verify(connection).commit();
    }
    @ParameterizedTest @EnumSource(WorkDiagnostic.class)
    void failureAppendsSanitizedImmutableDiagnosticThenFencesParent(WorkDiagnostic reason) throws Exception {
        var before=RiskWorkServiceTest.active();
        var after=new RiskWorkRepository.Row(17,23,reason.state(),null,1,1,2,null,before.created(),before.cutoff(),reason.name(),reason.message());
        locks.add(row(before)); reads.add(row(after)); assertEquals("2",service.fail(before.lease(),reason));
        var reader=org.mockito.ArgumentCaptor.forClass(Reader.class);
        verify(diagnostic).setCharacterStream(eq(4),reader.capture(),anyInt());
        var text=new java.io.StringWriter(); reader.getValue().transferTo(text); String json=text.toString();
        assertEquals(reason.name(),CanonicalAcceptance.read(json).get("code"));
        assertFalse(json.contains("old-token")); verify(diagnostic).setString(5,CanonicalAcceptance.hash(json));
        verify(diagnostic).setInt(2,1);
        verify(diagnostic).setObject(3,before.cutoff().atOffset(ZoneOffset.UTC),Types.TIMESTAMP_WITH_TIMEZONE);
        var order=inOrder(diagnostic,update,connection); order.verify(diagnostic).executeUpdate(); order.verify(update).executeUpdate(); order.verify(connection).commit();
        verify(update).setNull(2,Types.VARCHAR); verify(update).setNull(6,Types.TIMESTAMP_WITH_TIMEZONE);
    }
    @Test void failureExpiryDuringDiagnosticInsertionRollsBackChildAndParent() throws Exception {
        locks.add(row(RiskWorkServiceTest.active())); when(update.executeUpdate()).thenReturn(0);
        failure(WorkFailure.Code.LEASE_REJECTED,()->service.fail(RiskWorkServiceTest.active().lease(),WorkDiagnostic.TRANSIENT_FAILURE));
        verify(diagnostic).executeUpdate(); verify(update).executeUpdate(); verify(read,never()).executeQuery(); rollback();
    }
    @Test void diagnosticInsertFailureNeverUpdatesParent() throws Exception {
        locks.add(row(RiskWorkServiceTest.active()));
        when(diagnostic.executeUpdate()).thenThrow(new SQLException("private diagnostic payload","23000",1));
        failure(WorkFailure.Code.WORK_PERSISTENCE_UNAVAILABLE,()->service.fail(RiskWorkServiceTest.active().lease(),WorkDiagnostic.INTERVENTION_REQUIRED));
        verify(update,never()).executeUpdate(); rollback();
    }
    @Test void parentUpdateFailureRollsBackDiagnostic() throws Exception {
        locks.add(row(RiskWorkServiceTest.active())); when(update.executeUpdate()).thenThrow(new SQLException("private SQL","99999",20000));
        failure(WorkFailure.Code.WORK_PERSISTENCE_UNAVAILABLE,()->service.fail(RiskWorkServiceTest.active().lease(),WorkDiagnostic.TRANSIENT_FAILURE));
        verify(diagnostic).executeUpdate(); rollback();
    }
    @Test void readbackFailureAfterSuccessfulUpdateRollsBack() throws Exception {
        locks.add(row(RiskWorkServiceTest.idle())); doThrow(new SQLException("private readback")).when(read).executeQuery();
        failure(WorkFailure.Code.WORK_PERSISTENCE_UNAVAILABLE,()->service.claim("17","0")); verify(update).executeUpdate(); rollback();
    }
    @Test void wrongReadbackRollsBackDiagnosticAndParent() throws Exception {
        locks.add(row(RiskWorkServiceTest.active())); reads.add(row(RiskWorkServiceTest.active()));
        failure(WorkFailure.Code.WORK_PERSISTENCE_UNAVAILABLE,()->service.fail(RiskWorkServiceTest.active().lease(),WorkDiagnostic.TRANSIENT_FAILURE));
        verify(diagnostic).executeUpdate(); verify(update).executeUpdate(); rollback();
    }
    @Test void zeroAffectedClaimRollsBack() throws Exception {
        locks.add(row(RiskWorkServiceTest.idle())); when(update.executeUpdate()).thenReturn(0);
        failure(WorkFailure.Code.LEASE_REJECTED,()->service.claim("17","0")); rollback();
    }
    @Test void missingReadbackIsPersistenceFailureRatherThanMissingRequest() throws Exception {
        locks.add(row(RiskWorkServiceTest.idle())); var rs=mock(ResultSet.class); when(rs.next()).thenReturn(false); reads.add(rs);
        failure(WorkFailure.Code.WORK_PERSISTENCE_UNAVAILABLE,()->service.claim("17","0")); verify(update).executeUpdate(); rollback();
    }
    @Test void commitFailureRemainsSanitizedAndDoesNotReturnLease() throws Exception {
        locks.add(row(RiskWorkServiceTest.idle())); reads.add(row(claimed()));
        doThrow(new SQLException("private commit details")).when(connection).commit();
        failure(WorkFailure.Code.WORK_PERSISTENCE_UNAVAILABLE,()->service.claim("17","0")); verify(update).executeUpdate();
    }
    @Test void expiredRenewalIsRejectedByFinalDatabaseCheck() throws Exception {
        locks.add(row(RiskWorkServiceTest.active())); when(update.executeUpdate()).thenReturn(0);
        failure(WorkFailure.Code.LEASE_REJECTED,()->service.renew(RiskWorkServiceTest.active().lease()));
        verify(connection).prepareStatement(RiskWorkRepository.LIVE_UPDATE); rollback();
    }
    @Test void twoThreadedClaimsSerializeThroughRealTransactionManagerAndMockRowLock() throws Exception {
        // Models row-lock ownership until transaction completion. This is not Oracle lock certification.
        var shared=new java.util.concurrent.atomic.AtomicReference<>(RiskWorkServiceTest.idle());
        var rowLock=new java.util.concurrent.locks.ReentrantLock();
        var ready=new java.util.concurrent.CountDownLatch(2); var start=new java.util.concurrent.CountDownLatch(1);
        var commits=new java.util.concurrent.atomic.AtomicInteger(); var rollbacks=new java.util.concurrent.atomic.AtomicInteger();
        var concurrentDs=mock(DataSource.class);
        when(concurrentDs.getConnection()).thenAnswer(call -> {
            var conn=mock(Connection.class); var select=mock(PreparedStatement.class);
            var write=mock(PreparedStatement.class); var readback=mock(PreparedStatement.class);
            when(conn.getAutoCommit()).thenReturn(true); when(conn.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
            when(conn.prepareStatement(RiskWorkRepository.LOCK)).thenReturn(select);
            when(conn.prepareStatement(RiskWorkRepository.CLAIM_UPDATE)).thenReturn(write);
            when(conn.prepareStatement(RiskWorkRepository.READ)).thenReturn(readback);
            when(select.executeQuery()).thenAnswer(c -> { rowLock.lockInterruptibly(); return row(shared.get()); });
            when(write.executeUpdate()).thenAnswer(c -> { assertTrue(rowLock.isHeldByCurrentThread()); shared.set(claimed()); return 1; });
            when(readback.executeQuery()).thenAnswer(c -> row(shared.get()));
            doAnswer(c -> { commits.incrementAndGet(); rowLock.unlock(); return null; }).when(conn).commit();
            doAnswer(c -> { rollbacks.incrementAndGet(); if(rowLock.isHeldByCurrentThread()) rowLock.unlock(); return null; }).when(conn).rollback();
            return conn;
        });
        var concurrentService=new RiskWorkService(new RiskWorkRepository(new JdbcTemplate(concurrentDs)),
            new JdbcTransactionManager(concurrentDs),Clock.fixed(now,ZoneOffset.UTC),Duration.ofSeconds(30),()->"new-token");
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Object> operation=() -> {
                ready.countDown(); assertTrue(start.await(5,java.util.concurrent.TimeUnit.SECONDS));
                try { return concurrentService.claim("17","0"); } catch(WorkFailure e) { return e.code(); }
            };
            var first=executor.submit(operation); var second=executor.submit(operation);
            assertTrue(ready.await(5,java.util.concurrent.TimeUnit.SECONDS)); start.countDown();
            var outcomes=List.of(first.get(10,java.util.concurrent.TimeUnit.SECONDS),second.get(10,java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1,outcomes.stream().filter(WorkLease.class::isInstance).count());
            assertEquals(1,outcomes.stream().filter(o -> o==WorkFailure.Code.WORK_CONFLICT).count());
            assertEquals(1,commits.get()); assertEquals(1,rollbacks.get()); assertEquals(claimed(),shared.get());
        } finally {
            start.countDown(); executor.shutdown();
            if(!executor.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS)) executor.shutdownNow();
            assertTrue(executor.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS));
        }
    }
    @Test void missingParentIsDistinctAndDoesNotReadClock() throws Exception {
        var rs=mock(ResultSet.class); when(rs.next()).thenReturn(false); locks.add(rs);
        failure(WorkFailure.Code.WORK_NOT_FOUND,()->service.claim("17","0")); verify(clock,never()).executeQuery(); rollback();
    }
    @ParameterizedTest @ValueSource(strings={"LEASE_EPOCH","ATTEMPT_COUNT","ROW_VERSION","CONFIGURATION_SNAPSHOT_ID"})
    void invalidExactPersistedCountersCannotBeCoerced(String column) throws Exception {
        var rs=row(RiskWorkServiceTest.idle()); when(rs.getObject(column)).thenReturn(new BigDecimal("1.1")); locks.add(rs);
        failure(WorkFailure.Code.WORK_PERSISTENCE_UNAVAILABLE,()->service.claim("17","0")); verify(update,never()).executeUpdate(); rollback();
    }
    @Test void clockFailureRollsBackBeforeMutation() throws Exception {
        locks.add(row(RiskWorkServiceTest.idle())); when(clock.executeQuery()).thenThrow(new SQLException("private clock"));
        failure(WorkFailure.Code.WORK_PERSISTENCE_UNAVAILABLE,()->service.claim("17","0")); verify(update,never()).executeUpdate(); rollback();
    }
    @Test void repositoryAndClockRejectUnboundUse() {
        assertThrows(IllegalStateException.class,()->repository.lock(17));
        assertThrows(IllegalStateException.class,()->new OracleLeaseClock(new JdbcTemplate(ds)).instant());
        verifyNoInteractions(ds);
    }
    @Test void mutationsNeverSetFrozenFactsOrScores() {
        String set=RiskWorkRepository.UPDATE.substring(0,RiskWorkRepository.UPDATE.indexOf("WHERE"));
        for(String forbidden:List.of("ACCEPTANCE_","CONFIGURATION_","PAYMENT_","INPUT_","EVIDENCE_","SCORE","BAND","COMPLETED_AT","EVALUATED_AT"))
            assertFalse(set.contains(forbidden),forbidden);
        assertTrue(RiskWorkRepository.DIAGNOSTIC.contains("'ATTEMPT_DIAGNOSTIC'"));
        assertTrue(RiskWorkRepository.DIAGNOSTIC.contains("'RESULT'"));
    }
}
