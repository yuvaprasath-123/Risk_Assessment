package com.ofss.risk.persistence;

import java.time.*;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Service decisions with mocked repository/transaction manager; not Oracle concurrency proof. */
class RiskWorkServiceTest {
    static final Instant NOW=Instant.parse("2026-10-14T10:00:00.123456789Z");
    final RiskWorkRepository repo=mock(RiskWorkRepository.class);
    final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
    RiskWorkService service;
    static RiskWorkRepository.Row row(String state,long epoch,int attempt,long revision,Instant expiry) {
        boolean failed=Set.of("BLOCKED","RETRYABLE_FAILURE").contains(state);
        return new RiskWorkRepository.Row(17,23,state,state.equals("IN_PROGRESS")?"old-token":null,
            epoch,attempt,revision,expiry,NOW.minusSeconds(100),NOW.minusSeconds(200),
            failed?"TRANSIENT_FAILURE":null,failed?WorkDiagnostic.TRANSIENT_FAILURE.message():null);
    }
    static RiskWorkRepository.Row idle() { return row("ACCEPTED",0,0,0,null); }
    static RiskWorkRepository.Row active() { return row("IN_PROGRESS",1,1,1,NOW.plusSeconds(10)); }
    @BeforeEach void setup() {
        when(manager.getTransaction(any())).thenAnswer(c -> new SimpleTransactionStatus());
        service=new RiskWorkService(repo,manager,Clock.fixed(NOW,ZoneOffset.UTC),Duration.ofSeconds(30),()->"new-token");
        doAnswer(c -> { when(repo.read(17)).thenReturn(c.getArgument(1)); return null; }).when(repo).update(any(),any(),anyBoolean());
    }
    WorkFailure failure(WorkFailure.Code code,Runnable operation) {
        var e=assertThrows(WorkFailure.class,operation::run); assertEquals(code,e.code()); assertNull(e.getCause()); return e;
    }
    @ParameterizedTest @ValueSource(strings={"ACCEPTED","RETRYABLE_FAILURE"})
    void claimsEligibleIdleStates(String state) {
        var before=row(state,3,4,5,null); when(repo.lock(17)).thenReturn(before);
        var lease=service.claim("17","5");
        assertEquals(4,lease.epoch()); assertEquals(5,lease.attempt()); assertEquals("6",lease.rowVersion());
        assertEquals("new-token",lease.token()); assertEquals(NOW.plusSeconds(30),lease.expiresAt());
        var captured=org.mockito.ArgumentCaptor.forClass(RiskWorkRepository.Row.class);
        verify(repo).update(eq(before),captured.capture(),eq(false));
        assertNull(captured.getValue().errorCode()); assertNull(captured.getValue().errorMessage());
        verify(repo,never()).diagnostic(any(),any(),any()); verify(manager).commit(any());
    }
    @ParameterizedTest @ValueSource(longs={-1,0,1})
    void takeoverBoundary(long expiryDelta) {
        var before=row("IN_PROGRESS",3,4,5,NOW.plusNanos(expiryDelta)); when(repo.lock(17)).thenReturn(before);
        if(expiryDelta>0) {
            failure(WorkFailure.Code.WORK_CONFLICT,()->service.claim("17","5")); verify(repo,never()).update(any(),any(),anyBoolean());
        } else { var lease=service.claim("17","5"); assertEquals(4,lease.epoch()); assertEquals(5,lease.attempt()); }
    }
    @ParameterizedTest @ValueSource(strings={"BLOCKED","COMPLETED"})
    void refusesAutomaticBlockedOrCompletedClaims(String state) {
        when(repo.lock(17)).thenReturn(row(state,3,4,5,null));
        failure(WorkFailure.Code.WORK_CONFLICT,()->service.claim("17","5"));
        verify(repo,never()).update(any(),any(),anyBoolean()); verify(manager).rollback(any());
    }
    @Test void staleClaimDoesNotModifyAnything() {
        when(repo.lock(17)).thenReturn(active()); failure(WorkFailure.Code.WORK_CONFLICT,()->service.claim("17","0"));
        verify(repo,never()).update(any(),any(),anyBoolean());
    }
    @Test void renewKeepsIdentityCountersAndReturnsNewFence() {
        var before=active(); when(repo.lock(17)).thenReturn(before);
        var lease=service.renew(before.lease());
        assertEquals(before.token(),lease.token()); assertEquals(before.epoch(),lease.epoch());
        assertEquals(before.attempt(),lease.attempt()); assertEquals("2",lease.rowVersion());
        assertEquals(NOW.plusSeconds(30),lease.expiresAt());
        verify(repo,never()).diagnostic(any(),any(),any());
    }
    @Test void backwardClockNeverShortensLease() {
        var before=row("IN_PROGRESS",1,1,1,NOW.plusSeconds(60)); when(repo.lock(17)).thenReturn(before);
        assertEquals(before.expiry(),service.renew(before.lease()).expiresAt());
    }
    @ParameterizedTest @ValueSource(longs={-1,0})
    void cannotRenewOrFailAtOrAfterExpiry(long expiryDelta) {
        var before=row("IN_PROGRESS",1,1,1,NOW.plusNanos(expiryDelta)); when(repo.lock(17)).thenReturn(before);
        failure(WorkFailure.Code.LEASE_REJECTED,()->service.renew(before.lease()));
        failure(WorkFailure.Code.LEASE_REJECTED,()->service.fail(before.lease(),WorkDiagnostic.TRANSIENT_FAILURE));
        verify(repo,never()).update(any(),any(),anyBoolean()); verify(repo,never()).diagnostic(any(),any(),any());
    }
    static Stream<WorkLease> staleFences() {
        var row=active(); var lease=row.lease();
        return Stream.of(new WorkLease("17","wrong",1,1,"1",row.expiry()),
            new WorkLease("17",lease.token(),2,1,"1",row.expiry()),
            new WorkLease("17",lease.token(),1,2,"1",row.expiry()),
            new WorkLease("17",lease.token(),1,1,"2",row.expiry()),
            new WorkLease("17",lease.token(),1,1,"1",row.expiry().plusNanos(1)));
    }
    @ParameterizedTest @MethodSource("staleFences") void rejectsEveryStaleFenceField(WorkLease stale) {
        when(repo.lock(17)).thenReturn(active());
        failure(WorkFailure.Code.LEASE_REJECTED,()->service.renew(stale));
        failure(WorkFailure.Code.LEASE_REJECTED,()->service.fail(stale,WorkDiagnostic.INTERVENTION_REQUIRED));
        verify(repo,never()).update(any(),any(),anyBoolean()); verify(repo,never()).diagnostic(any(),any(),any());
    }
    @ParameterizedTest @ValueSource(strings={"ACCEPTED","RETRYABLE_FAILURE","BLOCKED","COMPLETED"})
    void inactiveStatesRejectAllWorkerMutations(String state) {
        when(repo.lock(17)).thenReturn(row(state,1,1,1,null));
        failure(WorkFailure.Code.LEASE_REJECTED,()->service.renew(active().lease()));
        failure(WorkFailure.Code.LEASE_REJECTED,()->service.fail(active().lease(),WorkDiagnostic.TRANSIENT_FAILURE));
        verify(repo,never()).update(any(),any(),anyBoolean());
    }
    @ParameterizedTest @EnumSource(WorkDiagnostic.class) void terminalDiagnosticAndClearLeaseAtomic(WorkDiagnostic diagnostic) {
        var before=active(); when(repo.lock(17)).thenReturn(before);
        assertEquals("2",service.fail(before.lease(),diagnostic));
        var saved=org.mockito.ArgumentCaptor.forClass(RiskWorkRepository.Row.class);
        var order=inOrder(repo); order.verify(repo).lock(17); order.verify(repo).diagnostic(before,diagnostic,NOW);
        order.verify(repo).update(eq(before),saved.capture(),eq(true)); order.verify(repo).read(17);
        assertEquals(diagnostic.state(),saved.getValue().state()); assertNull(saved.getValue().token()); assertNull(saved.getValue().expiry());
        assertEquals(diagnostic.name(),saved.getValue().errorCode()); assertEquals(diagnostic.message(),saved.getValue().errorMessage());
        assertEquals(before.snapshot(),saved.getValue().snapshot()); assertEquals(before.cutoff(),saved.getValue().cutoff());
    }
    static Stream<RiskWorkRepository.Row> exhausted() {
        return Stream.of(row("ACCEPTED",Long.MAX_VALUE,1,1,null), row("ACCEPTED",1,Integer.MAX_VALUE,1,null),
            row("ACCEPTED",1,1,Long.MAX_VALUE,null));
    }
    @ParameterizedTest @MethodSource("exhausted") void claimExhaustionNeverWraps(RiskWorkRepository.Row row) {
        when(repo.lock(17)).thenReturn(row);
        failure(WorkFailure.Code.WORK_COUNTER_EXHAUSTED,()->service.claim("17",Long.toString(row.revision())));
        verify(repo,never()).update(any(),any(),anyBoolean());
    }
    @Test void renewalAndFailureExhaustionNeverAppendDiagnostic() {
        var row=row("IN_PROGRESS",1,1,Long.MAX_VALUE,NOW.plusSeconds(10)); when(repo.lock(17)).thenReturn(row);
        failure(WorkFailure.Code.WORK_COUNTER_EXHAUSTED,()->service.renew(row.lease()));
        failure(WorkFailure.Code.WORK_COUNTER_EXHAUSTED,()->service.fail(row.lease(),WorkDiagnostic.TRANSIENT_FAILURE));
        verify(repo,never()).diagnostic(any(),any(),any()); verify(repo,never()).update(any(),any(),anyBoolean());
    }
    @Test void timestampOverflowDoesNotUpdate() {
        service=new RiskWorkService(repo,manager,Clock.fixed(NOW,ZoneOffset.UTC),Duration.ofSeconds(Long.MAX_VALUE));
        when(repo.lock(17)).thenReturn(idle());
        failure(WorkFailure.Code.WORK_COUNTER_EXHAUSTED,()->service.claim("17","0")); verify(repo,never()).update(any(),any(),anyBoolean());
    }
    @Test void clockBeforeAcceptanceFailsClosed() {
        service=new RiskWorkService(repo,manager,Clock.fixed(NOW.minusSeconds(101),ZoneOffset.UTC),Duration.ofSeconds(30));
        when(repo.lock(17)).thenReturn(idle());
        failure(WorkFailure.Code.WORK_PERSISTENCE_UNAVAILABLE,()->service.claim("17","0")); verify(repo,never()).update(any(),any(),anyBoolean());
    }
    @ParameterizedTest @ValueSource(strings={"PT0S","PT-1S"}) void positiveDurationRequired(String value) {
        assertThrows(IllegalArgumentException.class,()->new RiskWorkService(repo,manager,Clock.systemUTC(),Duration.parse(value)));
    }
    @Test void refusesTokenReuseAndDoesNotLogTokens() {
        when(repo.lock(17)).thenReturn(row("IN_PROGRESS",1,1,1,NOW));
        service=new RiskWorkService(repo,manager,Clock.fixed(NOW,ZoneOffset.UTC),Duration.ofSeconds(30),()->"old-token");
        failure(WorkFailure.Code.WORK_PERSISTENCE_UNAVAILABLE,()->service.claim("17","1"));
        assertFalse(active().lease().toString().contains("old-token"));
    }
    @Test void repositoryAndCommitFailuresAreSanitized() {
        when(repo.lock(17)).thenThrow(new IllegalStateException("private SQL payload"));
        var e=failure(WorkFailure.Code.WORK_PERSISTENCE_UNAVAILABLE,()->service.claim("17","0"));
        assertFalse(e.getMessage().contains("private")); verify(manager).rollback(any());
    }
    @Test void failedReadbackRollsBackInsteadOfReturningLease() {
        when(repo.lock(17)).thenReturn(idle()); when(repo.read(17)).thenReturn(idle());
        doNothing().when(repo).update(any(),any(),anyBoolean());
        failure(WorkFailure.Code.WORK_PERSISTENCE_UNAVAILABLE,()->service.claim("17","0"));
        verify(manager).rollback(any()); verify(manager,never()).commit(any());
    }
}
