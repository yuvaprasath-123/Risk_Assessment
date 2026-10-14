package com.ofss.risk.persistence;

import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Offline tests of the opt-in verifier itself. These do not verify Oracle. */
class RiskOracleMetadataVerificationContractTest {
    final Connection connection = mock(Connection.class);
    final Statement statement = mock(Statement.class);
    final Map<String, List<Map<String, Object>>> metadata = new HashMap<>();

    @BeforeEach void metadata() throws Exception {
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenAnswer(call -> {
            var rows = metadata.get(call.getArgument(0));
            assertNotNull(rows, "Verifier must issue only the declared metadata SELECT statements");
            return result(rows);
        });
        metadata.put(OracleReadOnlyVerificationTest.IDENTITY, new ArrayList<>(List.of(map("1", "FREEPDB1", "2", "D2_RISK", "3", "D2_RISK"))));
        var tables = new ArrayList<Map<String, Object>>();
        var columns = new ArrayList<Map<String, Object>>();
        var identities = new ArrayList<Map<String, Object>>();
        OracleReadOnlyVerificationTest.expectedColumns().forEach((table, cols) -> {
            tables.add(map("TABLE_NAME", table, "TABLESPACE_NAME", "USERS"));
            cols.forEach((name, c) -> {
                columns.add(map("TABLE_NAME", table, "COLUMN_NAME", name, "DATA_TYPE", c.type().equals("TIMESTAMP WITH TIME ZONE") ? "TIMESTAMP(9) WITH TIME ZONE" : c.type(),
                        "DATA_PRECISION", c.precision(), "DATA_SCALE", c.scale(), "CHAR_LENGTH", c.charLength(), "CHAR_USED", c.type().equals("CHAR") ? "B" : c.charUsed(), "NULLABLE", c.nullable(), "IDENTITY_COLUMN", c.identity()));
                if (c.identity().equals("YES")) identities.add(map("TABLE_NAME", table, "COLUMN_NAME", name, "GENERATION_TYPE", "ALWAYS",
                        "MAX_VALUE", new BigDecimal("9223372036854775807"), "MIN_VALUE", BigDecimal.ONE, "INCREMENT_BY", BigDecimal.ONE, "CYCLE_FLAG", "N"));
            });
        });
        metadata.put(OracleReadOnlyVerificationTest.TABLES, tables);
        metadata.put(OracleReadOnlyVerificationTest.COLUMNS, columns);
        metadata.put(OracleReadOnlyVerificationTest.IDENTITIES, identities);
        var constraints = new ArrayList<Map<String, Object>>();
        OracleReadOnlyVerificationTest.expectedConstraints().forEach((name, c) -> constraints.add(map("CONSTRAINT_NAME", name,
                "TABLE_NAME", c.table(), "CONSTRAINT_TYPE", c.type(), "STATUS", "ENABLED", "VALIDATED", "VALIDATED",
                "DEFERRABLE", "NOT DEFERRABLE", "DEFERRED", "IMMEDIATE", "R_OWNER", c.type().equals("R") ? "D2_RISK" : null,
                "R_CONSTRAINT_NAME", c.referencedConstraint(), "DELETE_RULE", c.type().equals("R") ? "NO ACTION" : null)));
        metadata.put(OracleReadOnlyVerificationTest.CONSTRAINTS, constraints);
        var keyColumns = new ArrayList<Map<String, Object>>();
        OracleReadOnlyVerificationTest.expectedKeyColumns().forEach((name, cols) -> {
            for (int i = 0; i < cols.size(); i++) keyColumns.add(map("CONSTRAINT_NAME", name, "COLUMN_NAME", cols.get(i), "POSITION", i + 1));
        });
        metadata.put(OracleReadOnlyVerificationTest.CONSTRAINT_COLUMNS, keyColumns);
        var indexes = new ArrayList<Map<String, Object>>();
        OracleReadOnlyVerificationTest.expectedIndexes().forEach((name, cols) -> {
            for (int i = 0; i < cols.size(); i++) {
                var p = cols.get(i).split("\\.");
                indexes.add(map("INDEX_NAME", name, "TABLE_NAME", p[0], "COLUMN_NAME", p[1], "COLUMN_POSITION", i + 1, "STATUS", "VALID", "DESCEND", "ASC"));
            }
        });
        metadata.put(OracleReadOnlyVerificationTest.INDEXES, indexes);
        metadata.put(OracleReadOnlyVerificationTest.INDEX_EXPRESSIONS, new ArrayList<>());
        metadata.put(OracleReadOnlyVerificationTest.HIDDEN_COLUMNS, new ArrayList<>());
    }
    @Test void successRequiresReadOnlyTransactionAndOnlyMetadataSelectsThenRollback() throws Exception {
        OracleReadOnlyVerificationTest.verifyReadOnlyConnection(connection);
        var order = inOrder(connection, statement);
        order.verify(connection).setAutoCommit(false); order.verify(connection).setReadOnly(true);
        order.verify(statement).execute("SET TRANSACTION READ ONLY");
        order.verify(statement).executeQuery(OracleReadOnlyVerificationTest.IDENTITY);
        order.verify(statement).executeQuery(OracleReadOnlyVerificationTest.TABLES);
        order.verify(statement).executeQuery(OracleReadOnlyVerificationTest.COLUMNS);
        order.verify(statement).executeQuery(OracleReadOnlyVerificationTest.CONSTRAINTS);
        order.verify(statement).executeQuery(OracleReadOnlyVerificationTest.CONSTRAINT_COLUMNS);
        order.verify(statement).executeQuery(OracleReadOnlyVerificationTest.IDENTITIES);
        order.verify(statement).executeQuery(OracleReadOnlyVerificationTest.INDEX_EXPRESSIONS);
        order.verify(statement).executeQuery(OracleReadOnlyVerificationTest.HIDDEN_COLUMNS);
        order.verify(statement).executeQuery(OracleReadOnlyVerificationTest.INDEXES);
        order.verify(connection).rollback();
        verify(connection, never()).commit(); verify(statement, never()).executeUpdate(anyString());
        assertEquals(49, OracleReadOnlyVerificationTest.expectedConstraints().size());
        assertEquals(82, OracleReadOnlyVerificationTest.expectedColumns().values().stream().mapToInt(Map::size).sum());
    }
    @Test void wrongIdentityStopsBeforeDictionaryReadsAndStillRollsBack() throws Exception {
        metadata.get(OracleReadOnlyVerificationTest.IDENTITY).getFirst().put("2", "WRONG_OWNER");
        assertThrows(AssertionError.class, () -> OracleReadOnlyVerificationTest.verifyReadOnlyConnection(connection));
        verify(statement, never()).executeQuery(OracleReadOnlyVerificationTest.TABLES);
        verify(connection).rollback(); verify(connection, never()).commit();
    }
    @Test void disabledSubmissionConstraintIsRejected() throws Exception {
        metadata.get(OracleReadOnlyVerificationTest.CONSTRAINTS).stream().filter(c -> c.get("CONSTRAINT_NAME").equals("UK_RSK_SUBMISSION"))
                .forEach(c -> c.put("STATUS", "DISABLED"));
        assertThrows(AssertionError.class, () -> OracleReadOnlyVerificationTest.verifyReadOnlyConnection(connection));
        verify(connection).rollback(); verify(connection, never()).commit();
    }
    @Test void wrongTimestampPrecisionOrReversedCompositeKeyIsRejected() throws Exception {
        metadata.get(OracleReadOnlyVerificationTest.COLUMNS).stream().filter(c -> c.get("COLUMN_NAME").equals("EVIDENCE_AS_OF"))
                .forEach(c -> c.put("DATA_SCALE", 6));
        assertThrows(AssertionError.class, () -> OracleReadOnlyVerificationTest.verifyReadOnlyConnection(connection));
        metadata.get(OracleReadOnlyVerificationTest.COLUMNS).stream().filter(c -> c.get("COLUMN_NAME").equals("EVIDENCE_AS_OF"))
                .forEach(c -> c.put("DATA_SCALE", 9));
        var keyRows = metadata.get(OracleReadOnlyVerificationTest.CONSTRAINT_COLUMNS);
        Collections.reverse(keyRows);
        assertThrows(AssertionError.class, () -> OracleReadOnlyVerificationTest.verifyReadOnlyConnection(connection));
        verify(connection, times(2)).rollback(); verify(connection, never()).commit();
    }
    private void timezoneIndexes() {
        timezoneIndex("IX_RSK_SUPPLIER_HISTORY", 3, "SYS_NC00040$", "SYS_EXTRACT_UTC(\"COMPLETED_AT\")");
        timezoneIndex("IX_RSK_RECOVERY", 2, "SYS_NC00041$", "SYS_EXTRACT_UTC(\"LEASE_EXPIRES_AT\")");
    }
    private void timezoneIndex(String index, int position, String hiddenName, String expression) {
        metadata.get(OracleReadOnlyVerificationTest.INDEXES).stream()
                .filter(r -> index.equals(r.get("INDEX_NAME")) && Integer.valueOf(position).equals(r.get("COLUMN_POSITION")))
                .forEach(r -> r.put("COLUMN_NAME", hiddenName));
        metadata.get(OracleReadOnlyVerificationTest.INDEX_EXPRESSIONS).add(map("INDEX_NAME", index, "TABLE_NAME", "RISK_ASSESSMENTS",
                "COLUMN_POSITION", position, "COLUMN_EXPRESSION", expression));
        metadata.get(OracleReadOnlyVerificationTest.HIDDEN_COLUMNS).add(map("TABLE_NAME", "RISK_ASSESSMENTS", "COLUMN_NAME", hiddenName,
                "HIDDEN_COLUMN", "YES", "DATA_DEFAULT", expression));
    }
    @Test void validUtcExpressionsResolveBothHiddenColumnsAtTheirExactPositions() throws Exception {
        timezoneIndexes();
        OracleReadOnlyVerificationTest.verifyReadOnlyConnection(connection);
        verify(connection).rollback(); verify(connection, never()).commit();
    }
    @Test void generatedNamesMayVaryButBothExactExpressionsMustStillMatch() throws Exception {
        timezoneIndex("IX_RSK_SUPPLIER_HISTORY", 3, "SYS_NC99999$", " sys_extract_utc ( \"COMPLETED_AT\" ) ");
        timezoneIndex("IX_RSK_RECOVERY", 2, "SYS_NC12345$", "SYS.SYS_EXTRACT_UTC(LEASE_EXPIRES_AT)");
        OracleReadOnlyVerificationTest.verifyIndexes(connection);
    }
    @ParameterizedTest @ValueSource(strings = {"SYS_EXTRACT_UTC(\"LEASE_EXPIRES_AT\")", "SYS_EXTRACT_UTC(\"CREATED_AT\")",
            "SYS_EXTRACT_UTC(\"completed_at\")", "SYS_EXTRACT_UTC(\"COMPLETED_AT_EXTRA\")"})
    void anotherTimestampColumnCannotStandInForCompletedAt(String expression) throws Exception {
        timezoneIndexes();
        metadata.get(OracleReadOnlyVerificationTest.INDEX_EXPRESSIONS).getFirst().put("COLUMN_EXPRESSION", expression);
        assertThrows(AssertionError.class, () -> OracleReadOnlyVerificationTest.verifyIndexes(connection));
    }
    @ParameterizedTest @ValueSource(strings = {"CAST(\"COMPLETED_AT\" AS DATE)", "TRUNC(SYS_EXTRACT_UTC(\"COMPLETED_AT\"))",
            "SYS_EXTRACT_UTC(\"COMPLETED_AT\") + 1", "NVL(SYS_EXTRACT_UTC(\"COMPLETED_AT\"), SYSTIMESTAMP)",
            "OTHER.SYS_EXTRACT_UTC(\"COMPLETED_AT\")", "SYS_EXTRACT_UTC(\"COMPLETED_AT\", \"CREATED_AT\")", "", "/* comment */ SYS_EXTRACT_UTC(\"COMPLETED_AT\")"})
    void unexpectedExpressionIsRejectedEvenWithARecognizedHiddenName(String expression) throws Exception {
        timezoneIndexes();
        metadata.get(OracleReadOnlyVerificationTest.INDEX_EXPRESSIONS).getFirst().put("COLUMN_EXPRESSION", expression);
        assertThrows(AssertionError.class, () -> OracleReadOnlyVerificationTest.verifyIndexes(connection));
    }
    @ParameterizedTest @ValueSource(strings = {"MISSING_EXPRESSION", "MISSING_HIDDEN", "WRONG_DEFAULT", "NOT_HIDDEN", "WRONG_POSITION", "WRONG_TABLE", "DESCENDING", "DUPLICATE_POSITION"})
    void hiddenColumnResolutionRequiresCompleteConsistentMetadata(String scenario) throws Exception {
        timezoneIndexes();
        switch (scenario) {
            case "MISSING_EXPRESSION" -> metadata.get(OracleReadOnlyVerificationTest.INDEX_EXPRESSIONS).clear();
            case "MISSING_HIDDEN" -> metadata.get(OracleReadOnlyVerificationTest.HIDDEN_COLUMNS).clear();
            case "WRONG_DEFAULT" -> metadata.get(OracleReadOnlyVerificationTest.HIDDEN_COLUMNS).getFirst().put("DATA_DEFAULT", "SYS_EXTRACT_UTC(\"CREATED_AT\")");
            case "NOT_HIDDEN" -> metadata.get(OracleReadOnlyVerificationTest.HIDDEN_COLUMNS).getFirst().put("HIDDEN_COLUMN", "NO");
            case "WRONG_POSITION" -> metadata.get(OracleReadOnlyVerificationTest.INDEX_EXPRESSIONS).getFirst().put("COLUMN_POSITION", 2);
            case "WRONG_TABLE" -> metadata.get(OracleReadOnlyVerificationTest.INDEX_EXPRESSIONS).getFirst().put("TABLE_NAME", "RISK_INPUT_EVIDENCE");
            case "DESCENDING" -> metadata.get(OracleReadOnlyVerificationTest.INDEXES).getFirst().put("DESCEND", "DESC");
            case "DUPLICATE_POSITION" -> metadata.get(OracleReadOnlyVerificationTest.INDEX_EXPRESSIONS).add(new HashMap<>(metadata.get(OracleReadOnlyVerificationTest.INDEX_EXPRESSIONS).getFirst()));
        }
        assertThrows(AssertionError.class, () -> OracleReadOnlyVerificationTest.verifyIndexes(connection));
    }
    @Test void correctExpressionsDoNotExcuseWrongOrderedDirectColumns() throws Exception {
        timezoneIndexes();
        metadata.get(OracleReadOnlyVerificationTest.INDEXES).stream().filter(r -> r.get("INDEX_NAME").equals("IX_RSK_SUPPLIER_HISTORY")
                && Integer.valueOf(1).equals(r.get("COLUMN_POSITION"))).forEach(r -> r.put("COLUMN_NAME", "PAYMENT_ID"));
        assertThrows(AssertionError.class, () -> OracleReadOnlyVerificationTest.verifyIndexes(connection));
    }
    @Test void anExpressionOnAnOtherwiseDirectColumnIsNotIgnored() throws Exception {
        metadata.get(OracleReadOnlyVerificationTest.INDEX_EXPRESSIONS).add(map("INDEX_NAME", "IX_RSK_PAYMENT", "TABLE_NAME", "RISK_ASSESSMENTS",
                "COLUMN_POSITION", 1, "COLUMN_EXPRESSION", "ABS(\"PAYMENT_ID\")"));
        assertThrows(AssertionError.class, () -> OracleReadOnlyVerificationTest.verifyIndexes(connection));
    }
    private static Map<String, Object> map(Object... pairs) {
        var result = new HashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }
    private static ResultSet result(List<Map<String, Object>> rows) throws Exception {
        var rs = mock(ResultSet.class); var index = new AtomicInteger(-1); var wasNull = new AtomicBoolean();
        when(rs.next()).thenAnswer(c -> index.incrementAndGet() < rows.size());
        when(rs.getString(anyString())).thenAnswer(c -> (String) rows.get(index.get()).get(c.getArgument(0)));
        when(rs.getString(anyInt())).thenAnswer(c -> (String) rows.get(index.get()).get(c.getArgument(0).toString()));
        when(rs.getInt(anyString())).thenAnswer(c -> {
            Object n = rows.get(index.get()).get(c.getArgument(0)); wasNull.set(n == null); return n == null ? 0 : ((Number) n).intValue();
        });
        when(rs.wasNull()).thenAnswer(c -> wasNull.get());
        when(rs.getBigDecimal(anyString())).thenAnswer(c -> rows.get(index.get()).get(c.getArgument(0)));
        return rs;
    }
}
