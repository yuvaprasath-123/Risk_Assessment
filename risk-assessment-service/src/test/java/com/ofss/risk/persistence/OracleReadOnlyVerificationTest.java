package com.ofss.risk.persistence;

import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in metadata verification ONLY. No Spring context, business queries, NEXTVAL, DML or DDL. */
@EnabledIfEnvironmentVariable(named = "RISK_VERIFY_ORACLE", matches = "true")
class OracleReadOnlyVerificationTest {
    static final String SCOPE = "('RISK_ASSESSMENTS','RISK_CONFIGURATION_SNAPSHOTS','RISK_INPUT_EVIDENCE','RISK_ASSESSMENT_FACTORS')";
    static final String IDENTITY = "SELECT SYS_CONTEXT('USERENV','CON_NAME'), SYS_CONTEXT('USERENV','SESSION_USER'), SYS_CONTEXT('USERENV','CURRENT_SCHEMA') FROM DUAL";
    static final String TABLES = "SELECT TABLE_NAME, TABLESPACE_NAME FROM USER_TABLES WHERE TABLE_NAME IN " + SCOPE;
    static final String COLUMNS = "SELECT TABLE_NAME, COLUMN_NAME, DATA_TYPE, DATA_PRECISION, DATA_SCALE, CHAR_LENGTH, CHAR_USED, NULLABLE, IDENTITY_COLUMN FROM USER_TAB_COLS WHERE HIDDEN_COLUMN = 'NO' AND TABLE_NAME IN " + SCOPE;
    static final String CONSTRAINTS = "SELECT TABLE_NAME, CONSTRAINT_NAME, CONSTRAINT_TYPE, STATUS, VALIDATED, DEFERRABLE, DEFERRED, R_OWNER, R_CONSTRAINT_NAME, DELETE_RULE FROM USER_CONSTRAINTS WHERE TABLE_NAME IN " + SCOPE;
    static final String CONSTRAINT_COLUMNS = "SELECT CONSTRAINT_NAME, COLUMN_NAME, POSITION FROM USER_CONS_COLUMNS WHERE TABLE_NAME IN " + SCOPE + " ORDER BY CONSTRAINT_NAME, POSITION";
    static final String IDENTITIES = "SELECT I.TABLE_NAME, I.COLUMN_NAME, I.GENERATION_TYPE, S.MAX_VALUE, S.MIN_VALUE, S.INCREMENT_BY, S.CYCLE_FLAG FROM USER_TAB_IDENTITY_COLS I JOIN USER_SEQUENCES S ON S.SEQUENCE_NAME = I.SEQUENCE_NAME WHERE I.TABLE_NAME IN " + SCOPE;
    static final String INDEXES = "SELECT I.INDEX_NAME, I.TABLE_NAME, I.STATUS, C.COLUMN_NAME, C.COLUMN_POSITION, C.DESCEND FROM USER_INDEXES I JOIN USER_IND_COLUMNS C ON C.INDEX_NAME = I.INDEX_NAME AND C.TABLE_NAME = I.TABLE_NAME WHERE I.TABLE_NAME IN " + SCOPE + " ORDER BY I.INDEX_NAME, C.COLUMN_POSITION";
    // COLUMN_EXPRESSION and DATA_DEFAULT are LONG: read them directly, never compare/join/convert them in SQL.
    static final String INDEX_EXPRESSIONS = "SELECT INDEX_NAME, TABLE_NAME, COLUMN_POSITION, COLUMN_EXPRESSION FROM USER_IND_EXPRESSIONS WHERE TABLE_NAME IN " + SCOPE + " ORDER BY INDEX_NAME, COLUMN_POSITION";
    static final String HIDDEN_COLUMNS = "SELECT TABLE_NAME, COLUMN_NAME, HIDDEN_COLUMN, DATA_DEFAULT FROM USER_TAB_COLS WHERE HIDDEN_COLUMN = 'YES' AND TABLE_NAME IN " + SCOPE;
    private static final Pattern UTC_EXPRESSION = Pattern.compile(
            "\\s*(?:(?i:SYS)\\s*\\.\\s*)?(?i:SYS_EXTRACT_UTC)\\s*\\(\\s*(\"[A-Za-z][A-Za-z0-9_$#]*\"|[A-Za-z][A-Za-z0-9_$#]*)\\s*\\)\\s*");

    @Test void verifiesRiskOwnerContainerAndReviewedMetadataWithoutWrites() {
        String username = requiredEnvironment("RISK_DB_USERNAME");
        String password = requiredEnvironment("RISK_DB_PASSWORD");
        assertTrue("D2_RISK".equals(username), "Opt-in test requires the D2_RISK schema account");
        String url = System.getenv("RISK_DB_URL");
        if (url == null || url.isBlank()) url = "jdbc:oracle:thin:@//localhost:1521/FREEPDB1";
        try (Connection connection = DriverManager.getConnection(url, username, password)) {
            verifyReadOnlyConnection(connection);
        } catch (SQLException failure) {
            // Never attach the driver exception: diagnostics/URL/credential values must not appear in reports.
            throw new AssertionError("Oracle read-only metadata verification failed; D2_RISK JDBC verification remains pending");
        }
    }

    static void verifyReadOnlyConnection(Connection connection) throws SQLException {
        connection.setAutoCommit(false);
        connection.setReadOnly(true); // JDBC hint alone is not the Oracle enforcement boundary.
        try {
            try (var statement = connection.createStatement()) {
                statement.execute("SET TRANSACTION READ ONLY"); // Transaction control; no data/schema modification.
            }
            verifyMetadata(connection);
        } finally {
            connection.rollback(); // Close the read-only transaction; never commit or execute mutations.
        }
    }

    static void verifyMetadata(Connection connection) throws SQLException {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(IDENTITY)) {
            assertTrue(rows.next(), "Missing connection identity");
            assertTrue("FREEPDB1".equals(rows.getString(1)), "Unexpected Oracle container");
            assertTrue("D2_RISK".equals(rows.getString(2)), "Unexpected session account");
            assertTrue("D2_RISK".equals(rows.getString(3)), "Unexpected current schema");
            assertFalse(rows.next(), "Unexpected identity result count");
        }
        var expected = expectedColumns();
        Set<String> tables = new TreeSet<>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(TABLES)) {
            while (rows.next()) {
                tables.add(rows.getString("TABLE_NAME"));
                assertEquals("USERS", rows.getString("TABLESPACE_NAME"), "Reviewed tablespace");
            }
        }
        assertEquals(expected.keySet(), tables, "Required four risk tables");

        Map<String, Map<String, Column>> deployed = new TreeMap<>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(COLUMNS)) {
            while (rows.next()) {
                String type = rows.getString("DATA_TYPE");
                if (type.startsWith("TIMESTAMP") && type.endsWith("WITH TIME ZONE")) type = "TIMESTAMP WITH TIME ZONE";
                var column = new Column(type, integer(rows, "DATA_PRECISION"), integer(rows, "DATA_SCALE"),
                        type.equals("VARCHAR2") || type.equals("CHAR") ? rows.getInt("CHAR_LENGTH") : null,
                        // CHAR(64) has unspecified length semantics in the reviewed script. Both BYTE and CHAR
                        // are equivalent for ASCII hex hashes; VARCHAR2 explicitly requires CHAR semantics.
                        type.equals("VARCHAR2") ? rows.getString("CHAR_USED") : null,
                        rows.getString("NULLABLE"), rows.getString("IDENTITY_COLUMN"));
                deployed.computeIfAbsent(rows.getString("TABLE_NAME"), k -> new TreeMap<>()).put(rows.getString("COLUMN_NAME"), column);
            }
        }
        assertEquals(expected, deployed, "Reviewed column names/types/precision/CHAR semantics/nullability/identity flags");

        Map<String, Constraint> constraints = new TreeMap<>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(CONSTRAINTS)) {
            while (rows.next()) {
                String name = rows.getString("CONSTRAINT_NAME");
                if (!expectedConstraints().containsKey(name)) continue; // Oracle-generated NOT NULL checks have system names.
                assertEquals("ENABLED", rows.getString("STATUS"), name + " status");
                assertEquals("VALIDATED", rows.getString("VALIDATED"), name + " validation");
                assertEquals("NOT DEFERRABLE", rows.getString("DEFERRABLE"), name + " deferrability");
                assertEquals("IMMEDIATE", rows.getString("DEFERRED"), name + " enforcement");
                if ("R".equals(rows.getString("CONSTRAINT_TYPE"))) {
                    assertEquals("D2_RISK", rows.getString("R_OWNER"), name + " target owner");
                    assertEquals("NO ACTION", rows.getString("DELETE_RULE"), name + " delete rule");
                }
                constraints.put(name, new Constraint(rows.getString("TABLE_NAME"), rows.getString("CONSTRAINT_TYPE"), rows.getString("R_CONSTRAINT_NAME")));
            }
        }
        assertEquals(expectedConstraints(), constraints, "All 49 reviewed named constraints and FK target identities");
        Map<String, List<String>> columns = new TreeMap<>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(CONSTRAINT_COLUMNS)) {
            while (rows.next()) if (expectedKeyColumns().containsKey(rows.getString("CONSTRAINT_NAME")))
                columns.computeIfAbsent(rows.getString("CONSTRAINT_NAME"), k -> new ArrayList<>()).add(rows.getString("COLUMN_NAME"));
        }
        assertEquals(expectedKeyColumns(), columns, "Ordered primary/unique/foreign-key columns, including snapshot association");

        Set<String> identities = new TreeSet<>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(IDENTITIES)) {
            while (rows.next()) {
                identities.add(rows.getString("TABLE_NAME") + "." + rows.getString("COLUMN_NAME"));
                assertEquals("ALWAYS", rows.getString("GENERATION_TYPE"), "Reviewed identity generation");
                assertEquals(0, new BigDecimal("9223372036854775807").compareTo(rows.getBigDecimal("MAX_VALUE")), "Java Long identity maximum");
                assertEquals(0, BigDecimal.ONE.compareTo(rows.getBigDecimal("MIN_VALUE")), "Positive identity minimum");
                assertEquals(0, BigDecimal.ONE.compareTo(rows.getBigDecimal("INCREMENT_BY")), "Identity increment");
                assertEquals("N", rows.getString("CYCLE_FLAG"), "Identity must not cycle");
            }
        }
        assertEquals(Set.of("RISK_ASSESSMENTS.ASSESSMENT_ID", "RISK_CONFIGURATION_SNAPSHOTS.SNAPSHOT_ID",
                "RISK_INPUT_EVIDENCE.EVIDENCE_ID", "RISK_ASSESSMENT_FACTORS.FACTOR_ID"), identities, "Four identity columns/sequences (no NEXTVAL)");

        verifyIndexes(connection);
    }

    static void verifyIndexes(Connection connection) throws SQLException {
        var expected = expectedIndexes();
        Map<IndexPosition, String> expressions = new HashMap<>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(INDEX_EXPRESSIONS)) {
            while (rows.next()) {
                String index = rows.getString("INDEX_NAME"), table = rows.getString("TABLE_NAME");
                if (!expected.containsKey(index)) continue;
                int position = rows.getInt("COLUMN_POSITION");
                assertTrue(position >= 1 && position <= expected.get(index).size(), index + " expression position");
                assertTrue(expected.get(index).get(position - 1).startsWith(table + "."), index + " expression table");
                String expression = rows.getString("COLUMN_EXPRESSION");
                assertNotNull(expression, index + " expression must be available");
                assertNull(expressions.put(new IndexPosition(index, table, position), expression), index + " duplicate expression position");
            }
        }
        Map<String, HiddenColumn> hidden = new HashMap<>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(HIDDEN_COLUMNS)) {
            while (rows.next()) {
                String name = rows.getString("TABLE_NAME") + "." + rows.getString("COLUMN_NAME");
                var column = new HiddenColumn(rows.getString("HIDDEN_COLUMN"), rows.getString("DATA_DEFAULT"));
                assertNull(hidden.put(name, column), "Duplicate hidden-column metadata");
            }
        }
        Map<String, List<String>> indexes = new TreeMap<>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(INDEXES)) {
            while (rows.next()) if (expected.containsKey(rows.getString("INDEX_NAME"))) {
                String index = rows.getString("INDEX_NAME"), table = rows.getString("TABLE_NAME"), column = rows.getString("COLUMN_NAME");
                int position = rows.getInt("COLUMN_POSITION");
                var ordered = indexes.computeIfAbsent(index, k -> new ArrayList<>());
                assertEquals(ordered.size() + 1, position, index + " contiguous ordered position");
                assertTrue(position <= expected.get(index).size(), index + " unexpected extra position");
                assertEquals("VALID", rows.getString("STATUS"), "Required index status");
                assertEquals("ASC", rows.getString("DESCEND"), index + " reviewed ascending order");
                String target = expected.get(index).get(position - 1);
                String expression = expressions.remove(new IndexPosition(index, table, position));
                if (column != null && column.matches("SYS_NC[0-9]+\\$")) {
                    assertTrue(target.startsWith(table + "."), index + " hidden-column table");
                    String sourceColumn = target.substring(table.length() + 1);
                    var source = expectedColumns().get(table).get(sourceColumn);
                    assertEquals("TIMESTAMP WITH TIME ZONE", source.type(), index + " permitted timestamp source type");
                    assertEquals(sourceColumn, utcSource(expression), index + " exact indexed UTC timestamp expression");
                    var h = hidden.get(table + "." + column);
                    assertNotNull(h, index + " hidden-column metadata required");
                    assertEquals("YES", h.hidden(), index + " generated hidden column");
                    assertEquals(sourceColumn, utcSource(h.expression()), index + " hidden column must define the same UTC expression");
                    ordered.add(target); // Resolve only after both metadata expressions prove the exact source.
                } else {
                    assertNull(expression, index + " unexpected expression on a direct column");
                    ordered.add(table + "." + column);
                }
            }
        }
        assertTrue(expressions.isEmpty(), "Unmatched expression positions in required indexes");
        assertEquals(expected, indexes, "Six reviewed indexes with exact ordered columns/UTC expressions");
    }

    private static String utcSource(String expression) {
        assertNotNull(expression, "Required UTC timestamp expression is missing");
        var match = UTC_EXPRESSION.matcher(expression);
        assertTrue(match.matches(), "Unexpected timestamp index expression");
        String source = match.group(1);
        // Quoted identifiers are case-sensitive; unquoted Oracle identifiers normalize to uppercase.
        return source.startsWith("\"") ? source.substring(1, source.length() - 1) : source.toUpperCase(Locale.ROOT);
    }
    record IndexPosition(String index, String table, int position) {}
    record HiddenColumn(String hidden, String expression) {}

    record Column(String type, Integer precision, Integer scale, Integer charLength, String charUsed, String nullable, String identity) {}
    record Constraint(String table, String type, String referencedConstraint) {}
    private static Integer integer(ResultSet rows, String column) throws SQLException {
        int n = rows.getInt(column); return rows.wasNull() ? null : n;
    }
    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        assertTrue(value != null && !value.isBlank(), "Supply " + name + " externally for this opt-in test");
        return value;
    }
    static Map<String, Map<String, Column>> expectedColumns() {
        var result = new TreeMap<String, Map<String, Column>>();
        result.put("RISK_ASSESSMENTS", columns("""
                ASSESSMENT_ID N19 N ID
                SUBMISSION_SOURCE V64 N
                SUBMISSION_KEY V128 N
                PAYMENT_ID N19 N
                SUPPLIER_ID N19 N
                INVOICE_ID N19 N
                REQUESTER_ID N19 N
                VALIDATION_REFERENCE V128 N
                VALIDATION_REVISION V128 Y
                PAYMENT_AMOUNT MONEY N
                CURRENCY_CODE V3 N
                CONFIGURATION_VERSION_ID N19 N
                CONFIGURATION_ROW_VERSION N19 N
                CONFIGURATION_SNAPSHOT_ID N19 Y
                EVALUATOR_VERSION V64 N
                HASH_FORMAT_VERSION V32 N
                ACCEPTANCE_JSON CLOB N
                ACCEPTANCE_HASH H64 N
                CONFIGURATION_HASH H64 N
                INPUT_HASH H64 Y
                FROZEN_INPUT_EVIDENCE_ID N19 Y
                INPUT_FROZEN_AT TIME Y
                EVIDENCE_AS_OF TIME N
                WORK_STATE V24 N
                TOTAL_RAW_SCORE N19 Y
                FINAL_SCORE N10 Y
                RISK_BAND V8 Y
                FINAL_FACTOR_COUNT N1 Y
                ATTEMPT_COUNT N10 N
                LEASE_EPOCH N19 N
                LEASE_TOKEN V64 Y
                LEASE_EXPIRES_AT TIME Y
                CREATED_AT TIME N
                EVALUATED_AT TIME Y
                COMPLETED_AT TIME Y
                LAST_ERROR_CODE V64 Y
                LAST_ERROR_MESSAGE V1000 Y
                CORRELATION_ID V128 Y
                ROW_VERSION N19 N
                """));
        result.put("RISK_CONFIGURATION_SNAPSHOTS", columns("""
                SNAPSHOT_ID N19 N ID
                ASSESSMENT_ID N19 N
                SOURCE_VERSION_ID N19 N
                SOURCE_ROW_VERSION N19 N
                SOURCE_VERSION_NUMBER N10 Y
                SNAPSHOT_FORMAT_VERSION V32 N
                HASH_FORMAT_VERSION V32 N
                CONFIGURATION_JSON CLOB N
                CONTENT_HASH H64 N
                CAPTURED_AT TIME N
                """));
        result.put("RISK_INPUT_EVIDENCE", columns("""
                EVIDENCE_ID N19 N ID
                ASSESSMENT_ID N19 N
                RECORD_CLASS V24 N
                ATTEMPT_NO N10 N
                EVIDENCE_KEY V128 N
                EVIDENCE_KIND V24 N
                SOURCE_SERVICE V64 N
                SOURCE_RECORD_REFERENCE V128 Y
                SOURCE_SNAPSHOT_REFERENCE V128 Y
                SOURCE_REVISION V128 Y
                EVIDENCE_FORMAT_VERSION V32 N
                HASH_FORMAT_VERSION V32 N
                AS_OF_AT TIME N
                WINDOW_START_AT TIME Y
                WINDOW_END_AT TIME Y
                RECORD_COUNT N10 Y
                AVAILABILITY V16 Y
                EVIDENCE_JSON CLOB N
                CONTENT_HASH H64 N
                CAPTURED_AT TIME N
                """));
        result.put("RISK_ASSESSMENT_FACTORS", columns("""
                FACTOR_ID N19 N ID
                ASSESSMENT_ID N19 N
                RULE_CODE V64 N
                RULE_CONFIG_SOURCE_ID N19 Y
                FACTOR_ORDER N5 N
                EVALUATION_OUTCOME V24 N
                CONFIGURED_WEIGHT N10 N
                SCORE_CONTRIBUTION N10 N
                PRIMARY_EVIDENCE_ID N19 Y
                EXPLANATION V2000 N
                RESULT_FORMAT_VERSION V32 N
                DETAILS_JSON CLOB N
                EVALUATED_AT TIME N
                """));
        return result;
    }
    private static Map<String, Column> columns(String spec) {
        var result = new TreeMap<String, Column>();
        spec.lines().filter(line -> !line.isBlank()).forEach(line -> {
            var p = line.strip().split("\\s+"); String t = p[1];
            String type = t.startsWith("N") || t.equals("MONEY") ? "NUMBER" : t.startsWith("V") ? "VARCHAR2" : t.startsWith("H") ? "CHAR" : t.equals("TIME") ? "TIMESTAMP WITH TIME ZONE" : "CLOB";
            Integer precision = t.startsWith("N") ? Integer.valueOf(t.substring(1)) : t.equals("MONEY") ? 15 : null;
            Integer scale = type.equals("NUMBER") ? Integer.valueOf(t.equals("MONEY") ? 2 : 0) : t.equals("TIME") ? Integer.valueOf(9) : null;
            Integer length = t.startsWith("V") || t.startsWith("H") ? Integer.valueOf(t.substring(1)) : null;
            result.put(p[0], new Column(type, precision, scale, length, t.startsWith("V") ? "C" : null, p[2], p.length == 4 ? "YES" : "NO"));
        });
        return result;
    }
    static Map<String, Constraint> expectedConstraints() {
        var result = new TreeMap<String, Constraint>();
        checks(result, "RISK_ASSESSMENTS", "CK_RSK_ASM_IDS CK_RSK_ASM_TEXT CK_RSK_ASM_MONEY CK_RSK_ASM_ACCEPT_JSON CK_RSK_ASM_HASHES CK_RSK_ASM_STATE CK_RSK_ASM_COUNTERS CK_RSK_ASM_LEASE CK_RSK_ASM_FREEZE CK_RSK_ASM_CUTOFF CK_RSK_ASM_RESULT CK_RSK_ASM_ERRORS");
        checks(result, "RISK_CONFIGURATION_SNAPSHOTS", "CK_RSK_SNAP_IDS CK_RSK_SNAP_TEXT CK_RSK_SNAP_JSON CK_RSK_SNAP_HASH");
        checks(result, "RISK_INPUT_EVIDENCE", "CK_RSK_EVD_ID CK_RSK_EVD_TEXT CK_RSK_EVD_CLASS CK_RSK_EVD_AVAIL CK_RSK_EVD_COUNT CK_RSK_EVD_WINDOW CK_RSK_EVD_CAPTURE CK_RSK_EVD_JSON CK_RSK_EVD_HASH");
        checks(result, "RISK_ASSESSMENT_FACTORS", "CK_RSK_FAC_IDS CK_RSK_FAC_CODE CK_RSK_FAC_ORDER CK_RSK_FAC_TEXT CK_RSK_FAC_SCORES CK_RSK_FAC_OUTCOME CK_RSK_FAC_JSON");
        result.put("PK_RSK_ASM", new Constraint("RISK_ASSESSMENTS", "P", null));
        result.put("UK_RSK_SUBMISSION", new Constraint("RISK_ASSESSMENTS", "U", null));
        result.put("FK_RSK_ASM_SNAP", new Constraint("RISK_ASSESSMENTS", "R", "UK_RSK_SNAP_PARENT_ID"));
        result.put("FK_RSK_ASM_INPUT", new Constraint("RISK_ASSESSMENTS", "R", "UK_RSK_EVD_PARENT_ID"));
        result.put("PK_RSK_SNAP", new Constraint("RISK_CONFIGURATION_SNAPSHOTS", "P", null));
        result.put("UK_RSK_SNAP_ASM", new Constraint("RISK_CONFIGURATION_SNAPSHOTS", "U", null));
        result.put("UK_RSK_SNAP_PARENT_ID", new Constraint("RISK_CONFIGURATION_SNAPSHOTS", "U", null));
        result.put("FK_RSK_SNAP_ASM", new Constraint("RISK_CONFIGURATION_SNAPSHOTS", "R", "PK_RSK_ASM"));
        result.put("PK_RSK_EVD", new Constraint("RISK_INPUT_EVIDENCE", "P", null));
        result.put("UK_RSK_EVD_KEY", new Constraint("RISK_INPUT_EVIDENCE", "U", null));
        result.put("UK_RSK_EVD_PARENT_ID", new Constraint("RISK_INPUT_EVIDENCE", "U", null));
        result.put("FK_RSK_EVD_ASM", new Constraint("RISK_INPUT_EVIDENCE", "R", "PK_RSK_ASM"));
        result.put("PK_RSK_FAC", new Constraint("RISK_ASSESSMENT_FACTORS", "P", null));
        result.put("UK_RSK_FAC_CODE", new Constraint("RISK_ASSESSMENT_FACTORS", "U", null));
        result.put("UK_RSK_FAC_ORDER", new Constraint("RISK_ASSESSMENT_FACTORS", "U", null));
        result.put("FK_RSK_FAC_ASM", new Constraint("RISK_ASSESSMENT_FACTORS", "R", "PK_RSK_ASM"));
        result.put("FK_RSK_FAC_EVD", new Constraint("RISK_ASSESSMENT_FACTORS", "R", "UK_RSK_EVD_PARENT_ID"));
        return result;
    }
    private static void checks(Map<String, Constraint> map, String table, String names) {
        for (String name : names.split(" ")) map.put(name, new Constraint(table, "C", null));
    }
    static Map<String, List<String>> expectedKeyColumns() {
        var result = new TreeMap<String, List<String>>();
        result.put("PK_RSK_ASM", List.of("ASSESSMENT_ID"));
        result.put("UK_RSK_SUBMISSION", List.of("SUBMISSION_SOURCE", "SUBMISSION_KEY"));
        result.put("FK_RSK_ASM_SNAP", List.of("ASSESSMENT_ID", "CONFIGURATION_SNAPSHOT_ID"));
        result.put("FK_RSK_ASM_INPUT", List.of("ASSESSMENT_ID", "FROZEN_INPUT_EVIDENCE_ID"));
        result.put("PK_RSK_SNAP", List.of("SNAPSHOT_ID"));
        result.put("UK_RSK_SNAP_ASM", List.of("ASSESSMENT_ID"));
        result.put("UK_RSK_SNAP_PARENT_ID", List.of("ASSESSMENT_ID", "SNAPSHOT_ID"));
        result.put("FK_RSK_SNAP_ASM", List.of("ASSESSMENT_ID"));
        result.put("PK_RSK_EVD", List.of("EVIDENCE_ID"));
        result.put("UK_RSK_EVD_KEY", List.of("ASSESSMENT_ID", "RECORD_CLASS", "ATTEMPT_NO", "EVIDENCE_KEY"));
        result.put("UK_RSK_EVD_PARENT_ID", List.of("ASSESSMENT_ID", "EVIDENCE_ID"));
        result.put("FK_RSK_EVD_ASM", List.of("ASSESSMENT_ID"));
        result.put("PK_RSK_FAC", List.of("FACTOR_ID"));
        result.put("UK_RSK_FAC_CODE", List.of("ASSESSMENT_ID", "RULE_CODE"));
        result.put("UK_RSK_FAC_ORDER", List.of("ASSESSMENT_ID", "FACTOR_ORDER"));
        result.put("FK_RSK_FAC_ASM", List.of("ASSESSMENT_ID"));
        result.put("FK_RSK_FAC_EVD", List.of("ASSESSMENT_ID", "PRIMARY_EVIDENCE_ID"));
        return result;
    }
    static Map<String, List<String>> expectedIndexes() {
        var result = new TreeMap<String, List<String>>();
        result.put("IX_RSK_SUPPLIER_HISTORY", List.of("RISK_ASSESSMENTS.SUPPLIER_ID", "RISK_ASSESSMENTS.WORK_STATE", "RISK_ASSESSMENTS.COMPLETED_AT"));
        result.put("IX_RSK_RECOVERY", List.of("RISK_ASSESSMENTS.WORK_STATE", "RISK_ASSESSMENTS.LEASE_EXPIRES_AT"));
        result.put("IX_RSK_PAYMENT", List.of("RISK_ASSESSMENTS.PAYMENT_ID"));
        result.put("IX_RSK_ASM_SNAP", List.of("RISK_ASSESSMENTS.ASSESSMENT_ID", "RISK_ASSESSMENTS.CONFIGURATION_SNAPSHOT_ID"));
        result.put("IX_RSK_ASM_INPUT", List.of("RISK_ASSESSMENTS.ASSESSMENT_ID", "RISK_ASSESSMENTS.FROZEN_INPUT_EVIDENCE_ID"));
        result.put("IX_RSK_FAC_EVD", List.of("RISK_ASSESSMENT_FACTORS.ASSESSMENT_ID", "RISK_ASSESSMENT_FACTORS.PRIMARY_EVIDENCE_ID"));
        return result;
    }
}
