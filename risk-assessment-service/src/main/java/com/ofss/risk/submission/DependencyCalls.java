package com.ofss.risk.submission;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Bounded, zero-queue, read-only dependency work. Never runs persistence/evaluation on a background thread. */
public final class DependencyCalls implements AutoCloseable {
    private final ThreadPoolExecutor pool=new ThreadPoolExecutor(0,2,30,TimeUnit.SECONDS,new SynchronousQueue<>(),r->{
        var t=new Thread(r,"risk-dependency-read");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final Duration budget;
    public DependencyCalls(Duration budget) {
        if(budget==null || budget.isNegative() || budget.isZero() || budget.compareTo(Duration.ofSeconds(30))>0)throw new IllegalArgumentException("Dependency budget must be positive and at most PT30S");
        this.budget=budget;
    }
    public SubmissionAdapters.Deadline deadline(){return new SubmissionAdapters.Deadline(System.nanoTime()+budget.toNanos());}
    public <T>T call(SubmissionAdapters.Deadline deadline,Supplier<T> operation) {
        if(TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Dependency calls require no transaction");
        Future<T> future=null;
        try {
            long remaining=deadline.remainingNanos();if(remaining==0)throw new TimeoutException();
            future=pool.submit(operation::get);return future.get(remaining,TimeUnit.NANOSECONDS);
        }catch(ExecutionException e){if(e.getCause() instanceof SubmissionFailure f)throw f;throw failure();}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw failure();}
        catch(TimeoutException|RejectedExecutionException e){throw failure();}
        finally {if(future!=null && !future.isDone())future.cancel(true);}
    }
    public boolean exhausted(SubmissionAdapters.Deadline d){return d.remainingNanos()==0;}
    private static SubmissionFailure failure(){return new SubmissionFailure(SubmissionFailure.Code.DEPENDENCY_UNAVAILABLE);}
    public boolean terminated(){return pool.isTerminated();}
    @Override public void close(){
        pool.shutdownNow();
        try {if(!pool.awaitTermination(5,TimeUnit.SECONDS))throw new IllegalStateException("Dependency workers have not terminated");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Dependency worker shutdown interrupted");}
    }
}
