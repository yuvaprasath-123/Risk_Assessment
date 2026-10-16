package com.ofss.risk.persistence;

import java.time.*;
import java.util.*;
import java.math.BigDecimal;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.PlatformTransactionManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Offline fixture validation only; never reads credentials or opens Oracle connections. */
class OracleEvidenceTestSafetyTest {
    @ParameterizedTest @NullSource @ValueSource(strings={"D2_RISK","D2_RISK_FREEZE_","D2_RISK_FREEZE_1-1-1-1-1",
        "D2_RISK_WORK_12345678-1234-1234-1234-123456789abc","D2_RISK_FREEZE_12345678-1234-1234-1234-123456789ABC"})
    void malformedOrOtherMilestoneNamespaceCannotReachCleanupSql(String namespace) {
        var jdbc=mock(JdbcTemplate.class); var manager=mock(PlatformTransactionManager.class);
        var cleaner=new OracleEvidenceFixtureCleaner(jdbc,manager);
        assertThrows(IllegalArgumentException.class,()->cleaner.clean(namespace)); verifyNoInteractions(jdbc,manager);
    }
    @Test void fixturesUseLargeUnicodeRecordsForAllLocatorInsertsAndExactTimestampBoundaries() {
        var fixture=new OracleEvidenceFixtures(); OracleEvidenceFixtures.requireNamespace(fixture.namespace); assertTrue(fixture.namespace.length()<=64);
        var accepted=fixture.acceptance("roundtrip",Instant.parse("2026-10-14T10:00:00.123456890Z"));
        var request=fixture.request(FreezeFixtures.row().lease(),accepted);
        assertEquals(123456789,request.input().cutoff().getNano()); assertEquals("ORACLE_FREEZE_TEST:"+fixture.namespace,accepted.configuration().contentHash());
        assertTrue(accepted.current().invoiceNumber().length()>32767);
        assertTrue(request.input().supplier().records().getFirst().evidenceId().length()>32767);
        assertTrue(request.input().supplierTransitions().records().getFirst().evidenceId().length()>32767);
        assertTrue(request.input().previousAssessments().records().getFirst().evidenceId().length()>32767);
        assertEquals(new BigDecimal("1234567890123.45"),request.input().payments().records().getFirst().amount());
        assertEquals(OracleEvidenceFixtures.EVALUATOR,accepted.evaluatorVersion());
        assertThrows(IllegalArgumentException.class,()->fixture.acceptance("unrelated",Instant.now()));
    }
    @Test void cleanupAndExpiryControlAreExactScopedAndNeverResetSequencesOrAlterClock() {
        for(String sql:List.of(OracleEvidenceFixtureCleaner.DETACH,OracleEvidenceFixtureCleaner.DELETE_EVIDENCE,
            OracleEvidenceFixtureCleaner.DELETE_SNAPSHOT,OracleEvidenceFixtureCleaner.DELETE_PARENT,OracleEvidencePersistenceTest.EXPIRE,OracleEvidencePersistenceTest.EXPIRE_FOR_GUARD)) {
            assertTrue(sql.contains("SUBMISSION_SOURCE=?") && sql.contains("SUBMISSION_KEY=?") && sql.contains("EVALUATOR_VERSION=?") && sql.contains("VALIDATION_REFERENCE=?"));
            assertFalse(sql.contains("TRUNCATE") || sql.contains("DROP") || sql.contains("ALTER") || sql.contains("SEQUENCE"));
        }
        assertTrue(OracleEvidenceFixtureCleaner.DETACH.contains("FROZEN_INPUT_EVIDENCE_ID=NULL"));
        assertTrue(OracleEvidencePersistenceTest.EXPIRE.contains("LEASE_EXPIRES_AT=CREATED_AT"));
        assertFalse(OracleEvidencePersistenceTest.EXPIRE.contains("INPUT_HASH="));
        assertTrue(OracleEvidencePersistenceTest.EXPIRE_FOR_GUARD.contains("CREATED_AT+INTERVAL '1' SECOND"));
    }
    @SuppressWarnings("unchecked") @ParameterizedTest @ValueSource(strings={"key","evaluator","validation","completed","score","factor"})
    void unexpectedParentOrFinalizationDataRefusesCleanupBeforeMutation(String defect) {
        var jdbc=mock(JdbcTemplate.class); var manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
        String namespace=OracleEvidenceFixtures.PREFIX+"12345678-1234-1234-1234-123456789abc";
        var parent=new HashMap<String,Object>(); parent.put("ASSESSMENT_ID",BigDecimal.valueOf(17)); parent.put("CONFIGURATION_SNAPSHOT_ID",BigDecimal.valueOf(23));
        parent.put("SUBMISSION_KEY",defect.equals("key")?"unrelated":"roundtrip"); parent.put("EVALUATOR_VERSION",defect.equals("evaluator")?"unrelated":OracleEvidenceFixtures.EVALUATOR);
        parent.put("VALIDATION_REFERENCE",defect.equals("validation")?"unrelated":"synthetic-freeze-pass/roundtrip"); parent.put("WORK_STATE",defect.equals("completed")?"COMPLETED":"IN_PROGRESS");
        if(defect.equals("score")) parent.put("FINAL_SCORE",BigDecimal.ZERO);
        when(jdbc.query(eq(OracleEvidenceFixtureCleaner.LOCK),any(RowMapper.class),eq(namespace))).thenReturn(List.of(parent));
        when(jdbc.queryForObject(OracleWorkFixtureCleaner.FACTORS,Long.class,17L)).thenReturn(defect.equals("factor")?1L:0L);
        assertThrows(IllegalStateException.class,()->new OracleEvidenceFixtureCleaner(jdbc,manager).clean(namespace));
        verify(jdbc,never()).update(anyString(),any(Object[].class)); verify(manager).rollback(any()); verify(manager,never()).commit(any());
    }
}
