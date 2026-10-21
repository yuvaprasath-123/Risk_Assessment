# Payment processing service

Runs on port 8090. Implements the blueprint's six processing APIs: execute, status, retry, attempts, idempotency status and prototype simulation-mode control.

Read [Developer 3 guide](../DEVELOPER3_README.md) for request examples, Oracle environment settings and the Insomnia collection.

Each attempt is stored. A failed result is retried only through /{paymentId}/retry. Successful payment IDs cannot execute again. Reusing an ID with a different supplier or amount returns 409. Concurrent retries are serialized with a database lock.

The coordinator must submit only approved payments and update Developer 1's lifecycle status. This processor simulates outcomes; it has no bank connection.

Old PPC_SIMULATED_PAYMENTS demo rows are preserved but are not migrated. New state uses PAYMENT_PROCESSING, PAYMENT_PROCESSING_ATTEMPTS and PAYMENT_IDEMPOTENCY.

Run mvn test for retry history, payload mismatches, simulation configuration and concurrent idempotency checks.
