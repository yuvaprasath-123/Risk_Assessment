package com.ofss.risk.persistence;

import com.ofss.risk.submission.*;
import java.sql.Connection;
import java.time.*;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real Spring manager over mock JDBC: proves the orchestration boundary suspends caller transactions. */
class SubmissionTransactionBoundaryTest {
    @Test void lookupClaimAndFinalizationAreInvokedOutsideAmbientTransaction()throws Exception {
        var ds=mock(DataSource.class);var connection=mock(Connection.class);when(ds.getConnection()).thenReturn(connection);when(connection.getAutoCommit()).thenReturn(true);
        var manager=new JdbcTransactionManager(ds);var acceptance=mock(RiskAcceptanceService.class);var work=mock(RiskWorkService.class);
        var freeze=mock(RiskEvidenceService.class);var finalization=mock(RiskFinalizationService.class);var snapshots=mock(SubmissionSnapshotReader.class);var reads=mock(AssessmentReadService.class);
        var command=SubmissionFixtures.command();var stored=SubmissionFixtures.stored("ACCEPTED");
        when(acceptance.find(command.identity())).thenAnswer(i->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());assertFalse(TransactionSynchronizationManager.hasResource(ds));return Optional.of(stored);});
        when(snapshots.restore(stored)).thenReturn(SubmissionFixtures.request());when(snapshots.frozenHash(command.identity())).thenReturn("a".repeat(64));
        when(work.claim("1","0")).thenAnswer(i->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());return new WorkLease("1","opaque",1,1,"1",stored.createdAt().plusSeconds(60));});
        when(finalization.finalizeAssessment(any())).thenAnswer(i->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());return null;});
        when(reads.get("1")).thenReturn(SubmissionFixtures.view("COMPLETED"));
        try(var calls=new DependencyCalls(Duration.ofSeconds(1))){
            var service=new RiskSubmissionService(new SubmissionSources("{\"payment-service\":[\"developer1\"]}"),(c,t,d)->{throw new AssertionError();},(a,d)->{throw new AssertionError();},(v,d)->{throw new AssertionError();},
                acceptance,work,freeze,finalization,snapshots,reads,calls,Clock.systemUTC(),manager);
            new TransactionTemplate(manager).execute(tx->{assertTrue(TransactionSynchronizationManager.isActualTransactionActive());service.submit("payment-service",command);
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());assertTrue(TransactionSynchronizationManager.hasResource(ds));return null;});
            verify(finalization).finalizeAssessment(any());verify(connection).commit();
        }
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());verify(connection).close();
    }
}
