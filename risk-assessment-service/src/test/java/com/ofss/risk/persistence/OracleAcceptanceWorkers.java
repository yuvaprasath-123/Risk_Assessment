package com.ofss.risk.persistence;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Observe BOTH workers before reporting a failure. Interrupt only after a bounded timeout, never on first failure. */
final class OracleAcceptanceWorkers {
    static <T> List<T> pair(Callable<T> work, long timeoutMillis, Runnable terminationUncertain) {
        return pair(work, timeoutMillis, terminationUncertain, event -> {});
    }
    static <T> List<T> pair(Callable<T> work, long timeoutMillis, Runnable terminationUncertain, Consumer<String> observer) {
        var pool = Executors.newFixedThreadPool(2); var futures = List.of(pool.submit(work), pool.submit(work));
        var values = new ArrayList<T>(); boolean failed = false;
        try {
            for (int index = 0; index < futures.size(); index++) {
                var future = futures.get(index); String worker = "worker-" + (index + 1);
                try { values.add(future.get(timeoutMillis, TimeUnit.MILLISECONDS)); observer.accept(worker + ":COMPLETED"); }
                catch (ExecutionException failure) { failed = true; observer.accept(worker + ":FAILED"); } // Still observe the other worker.
                catch (TimeoutException failure) { failed = true; observer.accept(worker + ":TIMEOUT_CANCEL"); future.cancel(true); }
            }
            if (failed) throw new AssertionError("Concurrent acceptance worker failed; inspect safe test-only diagnostics");
            return values;
        } catch (InterruptedException interrupted) {
            terminationUncertain.run(); Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for concurrent workers; cleanup deferred");
        } finally {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS)) {
                    observer.accept("workers:TERMINATION_TIMEOUT_CANCEL");
                    for (var future : futures) future.cancel(true);
                    pool.shutdownNow();
                    if (!pool.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS)) {
                        terminationUncertain.run(); throw new AssertionError("Worker termination unverified; cleanup deferred");
                    }
                }
                observer.accept("workers:TERMINATED");
            } catch (InterruptedException interrupted) {
                pool.shutdownNow(); terminationUncertain.run(); Thread.currentThread().interrupt();
                throw new AssertionError("Worker termination interrupted; cleanup deferred");
            }
        }
    }
    private OracleAcceptanceWorkers() {}
}
