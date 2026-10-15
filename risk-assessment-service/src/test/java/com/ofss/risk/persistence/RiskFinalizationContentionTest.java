package com.ofss.risk.persistence;

import com.ofss.risk.engine.*;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.ReentrantLock;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Two real threads/Spring transactions with simulated row locking; not evidence of Oracle lock behavior. */
class RiskFinalizationContentionTest {
    @Test void twoFinalizersEvaluateUnlockedAndOnlyOneFactorSetFinalizes() throws Exception {
        var initial=FinalizationFixtures.snapshot(); var request=FinalizationFixtures.request(initial);
        var parent=new AtomicReference<>(initial); var saved=new AtomicReference<CompletedAssessment>();
        var loader=mock(FrozenAssessmentLoader.class); var work=mock(RiskWorkRepository.class); var repo=mock(RiskFinalizationRepository.class);
        var ds=mock(DataSource.class); when(ds.getConnection()).thenAnswer(c->{var connection=mock(Connection.class);
            when(connection.getAutoCommit()).thenReturn(true); when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED); return connection;});
        var lock=new ReentrantLock();
        when(loader.load(any())).thenAnswer(c->parent.get());
        when(work.lock(17)).thenAnswer(c->{
            lock.lock(); TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) { lock.unlock(); }
            }); return parent.get().work();
        });
        var inserts=new AtomicInteger(); doAnswer(c->{assertTrue(lock.isHeldByCurrentThread()); inserts.incrementAndGet(); return null;}).when(repo).insert(any(),any(),anyString(),any());
        doAnswer(c->{var s=(FrozenAssessmentLoader.Snapshot)c.getArgument(0); var result=(RiskResult)c.getArgument(1); var completed=FinalizationFixtures.completed(s);
            parent.set(completed); saved.set(new CompletedAssessment("17","2",s.evaluator(),s.accepted().acceptanceHash(),s.accepted().configurationHash(),s.header().hash(),c.getArgument(2),c.getArgument(3),result)); return null;
        }).when(repo).complete(any(),any(),any(),any());
        when(repo.read(any(),any())).thenAnswer(c->saved.get());
        var arrived=new CountDownLatch(2); var evaluations=new AtomicInteger();
        var registry=new PinnedRiskEvaluators(Map.of(PinnedRiskEvaluators.CURRENT,(c,i)->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive()); assertFalse(lock.isHeldByCurrentThread());
            evaluations.incrementAndGet(); arrived.countDown();
            try { if(!arrived.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("Other evaluator did not arrive"); }
            catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Interrupted"); }
            return new RiskScoringEngine().evaluate(c,i);
        }));
        var service=new RiskFinalizationService(loader,work,repo,registry,new JdbcTransactionManager(ds),Clock.fixed(FreezeFixtures.NOW,ZoneOffset.UTC));
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->service.finalizeAssessment(request)); var second=pool.submit(()->service.finalizeAssessment(request));
            assertEquals(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS));
            assertEquals(2,evaluations.get()); assertEquals(7,inserts.get()); verify(repo).complete(any(),any(),any(),any());
        } finally {pool.shutdownNow(); assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS),"Both workers must terminate");}
    }
}
