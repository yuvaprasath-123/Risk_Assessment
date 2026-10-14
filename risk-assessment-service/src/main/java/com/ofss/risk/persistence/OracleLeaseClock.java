package com.ofss.risk.persistence;

import java.time.*;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;

/** Injectable clock; production authority is Oracle, on the currently bound transaction connection. */
public final class OracleLeaseClock extends Clock {
    static final String SQL = "SELECT SYSTIMESTAMP AS LEASE_NOW FROM DUAL";
    private final JdbcTemplate jdbc;
    public OracleLeaseClock(JdbcTemplate jdbc) { this.jdbc = Objects.requireNonNull(jdbc); }
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) {
        if (!ZoneOffset.UTC.equals(zone)) throw new IllegalArgumentException("Lease clock uses UTC");
        return this;
    }
    @Override public Instant instant() {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                || !org.springframework.transaction.support.TransactionSynchronizationManager.hasResource(jdbc.getDataSource()))
            throw new IllegalStateException("Lease clock requires its bound transaction");
        return jdbc.queryForObject(SQL, (rs, n) -> {
            var value = rs.getObject("LEASE_NOW", OffsetDateTime.class);
            if (value == null) throw new org.springframework.dao.DataRetrievalFailureException("Missing clock value");
            Instant now = value.toInstant(); PersistenceValues.timestamp(now); return now;
        }, new Object[0]);
    }
}
