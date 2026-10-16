package com.ofss.risk.persistence;

import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Offline safety and Oracle-representation tests. Never opens a database or reads credentials. */
class OracleWorkTestSafetyTest {
    final String namespace=OracleWorkFixtures.PREFIX+"12345678-1234-1234-1234-123456789abc";
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
    final TransactionStatus status=new SimpleTransactionStatus();
    OracleWorkFixtureCleaner cleaner;
    @BeforeEach void setup() {
        when(manager.getTransaction(any())).thenReturn(status); cleaner=new OracleWorkFixtureCleaner(jdbc,manager);
    }
    OracleWorkFixtureCleaner.Row valid() {
        return new OracleWorkFixtureCleaner.Row(17,"outcomes","BLOCKED",OracleWorkFixtures.EVALUATOR,"synthetic-work-pass/outcomes",23,true);
    }
    @SuppressWarnings("unchecked") void rows(List<OracleWorkFixtureCleaner.Row> rows) {
        when(jdbc.query(eq(OracleWorkFixtureCleaner.LOCK),any(RowMapper.class),eq(namespace))).thenReturn(rows);
        when(jdbc.queryForObject(OracleWorkFixtureCleaner.FACTORS,Long.class,17L)).thenReturn(0L);
        when(jdbc.query(eq(OracleWorkFixtureCleaner.SNAPSHOT),any(RowMapper.class),eq(17L),eq(23L))).thenReturn(List.of(true));
        when(jdbc.query(eq(OracleWorkFixtureCleaner.EVIDENCE),any(RowMapper.class),eq(17L))).thenReturn(List.of(true,true));
    }
    @ParameterizedTest @NullSource @ValueSource(strings={"D2_RISK","D2_RISK_WORK_","D2_RISK_TEST_12345678-1234-1234-1234-123456789abc",
        "D2_RISK_WORK_1-1-1-1-1","D2_RISK_WORK_12345678-1234-1234-1234-123456789ABC","D2_RISK_WORK_12345678-1234-1234-1234-123456789abc extra"})
    void invalidNamespaceNeverReachesSqlOrTransaction(String namespace) {
        assertThrows(IllegalArgumentException.class,()->cleaner.clean(namespace)); verifyNoInteractions(jdbc,manager);
    }
    @Test void cleanerDeletesOnlyVerifiedNamespaceInOneTransaction() {
        rows(List.of(valid())); when(jdbc.update(anyString(),any(Object[].class))).thenReturn(1);
        Object[] marker={17L,namespace,"outcomes",OracleWorkFixtures.EVALUATOR,"synthetic-work-pass/outcomes"};
        Object[] evidence={17L,17L,namespace,"outcomes",OracleWorkFixtures.EVALUATOR,"synthetic-work-pass/outcomes"};
        Object[] snapshot={17L,23L,17L,namespace,"outcomes",OracleWorkFixtures.EVALUATOR,"synthetic-work-pass/outcomes"};
        when(jdbc.update(OracleWorkFixtureCleaner.DELETE_EVIDENCE,evidence)).thenReturn(2);
        cleaner.clean(namespace);
        var order=inOrder(jdbc,manager);
        order.verify(jdbc).update(OracleWorkFixtureCleaner.DETACH,marker);
        order.verify(jdbc).update(OracleWorkFixtureCleaner.DELETE_EVIDENCE,evidence);
        order.verify(jdbc).update(OracleWorkFixtureCleaner.DELETE_SNAPSHOT,snapshot);
        order.verify(jdbc).update(OracleWorkFixtureCleaner.DELETE_PARENT,marker);
        order.verify(manager).commit(status); verify(manager,never()).rollback(any());
        for(String sql:List.of(OracleWorkFixtureCleaner.DETACH,OracleWorkFixtureCleaner.DELETE_EVIDENCE,
            OracleWorkFixtureCleaner.DELETE_SNAPSHOT,OracleWorkFixtureCleaner.DELETE_PARENT)) {
            assertTrue(sql.contains("SUBMISSION_SOURCE=?")); assertTrue(sql.contains("SUBMISSION_KEY=?"));
            assertTrue(sql.contains("EVALUATOR_VERSION=?")); assertTrue(sql.contains("VALIDATION_REFERENCE=?"));
        }
    }
    @ParameterizedTest @ValueSource(strings={"KEY","STATE","EVALUATOR","VALIDATION","FROZEN","FACTORS","SNAPSHOT","DUPLICATE_KEY"})
    void unexpectedWorkflowOrIdentityRefusesAllMutations(String defect) {
        var row=new OracleWorkFixtureCleaner.Row(17,defect.equals("KEY")?"unrelated":"outcomes",defect.equals("STATE")?"COMPLETED":"BLOCKED",
            defect.equals("EVALUATOR")?"unrelated":OracleWorkFixtures.EVALUATOR,
            defect.equals("VALIDATION")?"unrelated":"synthetic-work-pass/outcomes",23,!defect.equals("FROZEN"));
        rows(defect.equals("DUPLICATE_KEY")?List.of(row,row):List.of(row));
        if(defect.equals("FACTORS")) when(jdbc.queryForObject(OracleWorkFixtureCleaner.FACTORS,Long.class,17L)).thenReturn(1L);
        if(defect.equals("SNAPSHOT")) {
            @SuppressWarnings("unchecked") var ignored=jdbc.query(eq(OracleWorkFixtureCleaner.SNAPSHOT),any(RowMapper.class),eq(17L),eq(23L));
            when(ignored).thenReturn(List.of());
        }
        assertThrows(IllegalStateException.class,()->cleaner.clean(namespace));
        verify(jdbc,never()).update(anyString(),any(Object[].class)); verify(manager).rollback(status);
    }
    @Test void failedCleanupRollsBackAllDeletesAndDetachment() {
        rows(List.of(valid())); when(jdbc.update(anyString(),any(Object[].class))).thenReturn(1);
        assertThrows(IllegalStateException.class,()->cleaner.clean(namespace)); // Expected two diagnostics; only one deletion.
        verify(jdbc).update(eq(OracleWorkFixtureCleaner.DETACH),any(Object[].class));
        verify(manager).rollback(status); verify(manager,never()).commit(any());
    }
    @SuppressWarnings("unchecked") @ParameterizedTest @ValueSource(strings={"CLASS","KEY","KIND","SOURCE","FORMAT","HASH_FORMAT","AVAILABILITY","CODE","HASH","ATTEMPT","EPOCH"})
    void unexpectedEvidenceIsRefusedBeforeDeletion(String defect) throws Exception {
        rows(List.of(valid())); var rs=mock(ResultSet.class);
        for(var entry:Map.of("RECORD_CLASS","ATTEMPT_DIAGNOSTIC","EVIDENCE_KEY","RESULT","EVIDENCE_KIND","ATTEMPT_RESULT",
            "SOURCE_SERVICE","risk-assessment-service","EVIDENCE_FORMAT_VERSION","risk-work-diagnostic-v1","HASH_FORMAT_VERSION","risk-canonical-v1").entrySet())
            when(rs.getString(entry.getKey())).thenReturn(entry.getValue());
        String json="{\"attempt\":1,\"code\":\"TRANSIENT_FAILURE\",\"epoch\":\"1\",\"state\":\"RETRYABLE_FAILURE\"}";
        if(defect.equals("CODE")) json=json.replace("TRANSIENT_FAILURE","UNRECOGNIZED");
        if(defect.equals("EPOCH")) json=json.replace("\"epoch\":\"1\"","\"epoch\":\"0\"");
        when(rs.getString("EVIDENCE_JSON")).thenReturn(json); when(rs.getString("CONTENT_HASH")).thenReturn(defect.equals("HASH")?"unknown":CanonicalAcceptance.hash(json));
        when(rs.getObject("ATTEMPT_NO")).thenReturn(BigDecimal.valueOf(defect.equals("ATTEMPT")?2:1));
        switch(defect) {
            case "CLASS" -> when(rs.getString("RECORD_CLASS")).thenReturn("INPUT");
            case "KEY" -> when(rs.getString("EVIDENCE_KEY")).thenReturn("OTHER");
            case "KIND" -> when(rs.getString("EVIDENCE_KIND")).thenReturn("CURRENT_INPUT");
            case "SOURCE" -> when(rs.getString("SOURCE_SERVICE")).thenReturn("external-service");
            case "FORMAT" -> when(rs.getString("EVIDENCE_FORMAT_VERSION")).thenReturn("another-format");
            case "HASH_FORMAT" -> when(rs.getString("HASH_FORMAT_VERSION")).thenReturn("another-format");
            case "AVAILABILITY" -> when(rs.getObject("AVAILABILITY")).thenReturn("COMPLETE");
        }
        when(jdbc.query(eq(OracleWorkFixtureCleaner.EVIDENCE),any(RowMapper.class),eq(17L))).thenAnswer(call -> {
            RowMapper<?> mapper=call.getArgument(1); return List.of(mapper.mapRow(rs,0));
        });
        assertThrows(RuntimeException.class,()->cleaner.clean(namespace)); verify(jdbc,never()).update(anyString(),any(Object[].class)); verify(manager).rollback(status);
    }
    @Test void workFixturesHaveIndependentMarkersAndCanonicalSnapshot() {
        var fixture=new OracleWorkFixtures(); OracleWorkFixtures.requireNamespace(fixture.namespace);
        var request=fixture.request("takeover",RiskWorkServiceTest.NOW);
        assertTrue(fixture.namespace.length()<=64); assertEquals(OracleWorkFixtures.EVALUATOR,request.evaluatorVersion());
        assertEquals("synthetic-work-pass/takeover",request.validationReference());
        var body=CanonicalAcceptance.read(new CanonicalAcceptance().configuration(request.configuration()).json());
        assertEquals("ORACLE_WORK_TEST:"+fixture.namespace,((Map<?,?>)body.get("configuration")).get("contentHash"));
        assertThrows(IllegalArgumentException.class,()->fixture.request("unrelated",RiskWorkServiceTest.NOW));
    }
    @Test void fingerprintHandlesOracleTimezoneTypeAndClobWithoutJdbcObjectIdentity() throws Exception {
        var rs=mock(ResultSet.class); var meta=mock(ResultSetMetaData.class); when(rs.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(3);
        for(int i=1;i<=3;i++) when(meta.getColumnLabel(i)).thenReturn(List.of("CREATED_AT","EVIDENCE_JSON","ATTEMPT_NO").get(i-1));
        when(meta.getColumnTypeName(1)).thenReturn("TIMESTAMP WITH TIME ZONE"); when(meta.getColumnType(1)).thenReturn(-101);
        when(meta.getColumnTypeName(2)).thenReturn("CLOB"); when(meta.getColumnTypeName(3)).thenReturn("NUMBER");
        when(rs.getObject(1,OffsetDateTime.class)).thenReturn(RiskWorkServiceTest.NOW.atOffset(ZoneOffset.ofHoursMinutes(5,30)));
        when(rs.getString(2)).thenReturn("{\"code\":\"TRANSIENT_FAILURE\"}"); when(rs.getBigDecimal(3)).thenReturn(BigDecimal.ONE);
        var values=OracleWorkPersistenceTest.values(rs);
        assertEquals(RiskWorkServiceTest.NOW,values.get("CREATED_AT")); assertEquals(BigDecimal.ONE,values.get("ATTEMPT_NO"));
        assertEquals("{\"code\":\"TRANSIENT_FAILURE\"}",values.get("EVIDENCE_JSON")); verify(rs,never()).getObject(anyInt());
    }
    @Test void expiryControlIsFixtureScopedAndNeverChangesClockOrFrozenData() {
        String sql=OracleWorkPersistenceTest.EXPIRE;
        assertTrue(sql.contains("SUBMISSION_SOURCE=?") && sql.contains("SUBMISSION_KEY=?") && sql.contains("EVALUATOR_VERSION=?"));
        for(String field:List.of("LEASE_TOKEN=?","LEASE_EPOCH=?","ATTEMPT_COUNT=?","ROW_VERSION=?","LEASE_EXPIRES_AT=?")) assertTrue(sql.contains(field));
        String set=sql.substring(0,sql.indexOf("WHERE"));
        assertTrue(set.contains("LEASE_EXPIRES_AT=CREATED_AT")); assertFalse(set.contains("CONFIGURATION_"));
        assertFalse(set.contains("INPUT_")); assertFalse(set.contains("SYSTIMESTAMP")); assertFalse(sql.contains("ALTER"));
    }
}
