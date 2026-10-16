package com.ofss.risk.persistence;

import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.jdbc.core.*;

/** TEST ONLY. Capture before production sanitization; retain only allowlisted metadata, never messages/SQL/values. */
final class OracleAcceptanceDiagnostics {
    record Event(String phase, String exceptionType, Integer vendorCode, String sqlState, String constraint) {}
    private final Queue<Event> events = new ConcurrentLinkedQueue<>();
    private static final Pattern CONSTRAINT = Pattern.compile("\\(\\s*\"?D2_RISK\"?\\s*\\.\\s*\"?([A-Z][A-Z0-9_]*)\"?\\s*\\)");
    void record(String phase, Throwable failure) {
        var visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        var pending = new ArrayDeque<Throwable>(); pending.add(failure);
        while (!pending.isEmpty()) {
            var current = pending.removeFirst(); if (!visited.add(current)) continue;
            if (current instanceof SQLException sql) {
                String constraint = "UNRECOGNIZED";
                var identity = CONSTRAINT.matcher(sql.getMessage() == null ? "" : sql.getMessage());
                if (identity.find() && OracleReadOnlyVerificationTest.expectedConstraints().containsKey(identity.group(1))) constraint = identity.group(1);
                String state = sql.getSQLState();
                events.add(new Event(phase, current.getClass().getSimpleName(), sql.getErrorCode(),
                        state != null && state.matches("[A-Za-z0-9]{5}") ? state : "UNKNOWN", constraint));
                if (sql.getNextException() != null) pending.addLast(sql.getNextException());
            } else if (current == failure) events.add(new Event(phase, current.getClass().getSimpleName(), null, "NONE", "NONE"));
            if (current.getCause() != null) pending.addLast(current.getCause());
            for (var suppressed : current.getSuppressed()) pending.addLast(suppressed);
        }
    }
    List<Event> events() { return List.copyOf(events); }
    String summary() { return events().toString(); }
    void print() { if (!events.isEmpty()) System.out.println("Safe test-only Oracle diagnostics: " + summary()); }
    JdbcTemplate jdbc(DataSource source) { return new DiagnosticJdbc(source, this); }
    private static String operation(String sql) {
        if (RiskAcceptanceRepository.INSERT.equals(sql)) return "PARENT_INSERT";
        if (RiskAcceptanceRepository.SNAPSHOT.equals(sql)) return "SNAPSHOT_INSERT";
        return "OTHER_JDBC";
    }
    private <T> T proxy(Class<T> api, T delegate, String phase) {
        return api.cast(Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[]{api}, (p, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                case "toString" -> "Test-only guarded JDBC object";
                case "hashCode" -> System.identityHashCode(p);
                case "equals" -> p == args[0];
                default -> null;
            };
            String next = phase;
            if (api == Connection.class && method.getName().equals("prepareStatement")) next = operation((String) args[0]);
            String call = next + "." + method.getName();
            try {
                Object result = method.invoke(delegate, args);
                if (api == Connection.class && method.getName().equals("prepareStatement"))
                    return proxy(PreparedStatement.class, (PreparedStatement) result, next);
                if (api == PreparedStatement.class && method.getName().equals("getGeneratedKeys") && result != null)
                    return proxy(ResultSet.class, (ResultSet) result, next + ".GENERATED_KEYS");
                return result;
            } catch (InvocationTargetException target) { record(call, target.getCause()); throw target.getCause(); }
        }));
    }
    static class DiagnosticJdbc extends JdbcTemplate {
        final OracleAcceptanceDiagnostics diagnostics;
        DiagnosticJdbc(DataSource source, OracleAcceptanceDiagnostics diagnostics) { super(source); this.diagnostics = diagnostics; setQueryTimeout(15); }
        @Override public <T> T execute(PreparedStatementCreator creator, PreparedStatementCallback<T> action) {
            try { return super.execute((PreparedStatementCreator) connection -> creator.createPreparedStatement(diagnostics.proxy(Connection.class, connection, "PREPARE_BIND")), action); }
            catch (RuntimeException failure) { diagnostics.record("IDENTITY_EXECUTE_OR_BIND", failure); throw failure; }
        }
    }
}
