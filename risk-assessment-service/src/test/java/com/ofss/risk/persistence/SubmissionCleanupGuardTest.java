package com.ofss.risk.persistence;

import java.sql.Connection;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.support.JdbcTransactionManager;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real transaction manager/mock JDBC: refusals roll back before any mutation. */
class SubmissionCleanupGuardTest {
    JdbcTemplate jdbc=mock(JdbcTemplate.class);DataSource source=mock(DataSource.class);Connection connection=mock(Connection.class);
    JdbcTransactionManager manager=new JdbcTransactionManager(source);OracleSubmissionFixtures fixture=new OracleSubmissionFixtures();
    @BeforeEach void setup()throws Exception{when(source.getConnection()).thenReturn(connection);when(connection.getAutoCommit()).thenReturn(true);}
    @SuppressWarnings("unchecked") @ParameterizedTest @ValueSource(strings={"unknown-key","foreign-validation","foreign-source"})
    void unexpectedFixtureIdentityRefusesAllDeletion(String mismatch)throws Exception{
        var parent=new HashMap<String,Object>();parent.put("SUBMISSION_SOURCE",mismatch.equals("foreign-source")?"unrelated":fixture.namespace);
        parent.put("SUBMISSION_KEY",mismatch.equals("unknown-key")?"unreviewed":"success");parent.put("VALIDATION_REFERENCE",mismatch.equals("foreign-validation")?"real-record":"contract-pass/success");
        parent.put("VALIDATION_REVISION","contract-v1");parent.put("EVALUATOR_VERSION",PinnedRiskEvaluators.CURRENT);
        when(jdbc.query(eq(OracleSubmissionFixtureCleaner.LOCK),any(RowMapper.class),eq(fixture.namespace))).thenReturn(List.of(parent));
        var error=assertThrows(IllegalStateException.class,()->new OracleSubmissionFixtureCleaner(jdbc,manager).clean(fixture.namespace));assertNull(error.getCause());
        verify(connection).rollback();verify(connection,never()).commit();verify(jdbc,never()).update(anyString(),any(Object[].class));
        verify(jdbc,times(1)).query(eq(OracleSubmissionFixtureCleaner.LOCK),any(RowMapper.class),eq(fixture.namespace));
    }
    @SuppressWarnings("unchecked") @Test void alreadyCleanNamespaceIsSafeNoOp()throws Exception{
        when(jdbc.query(eq(OracleSubmissionFixtureCleaner.LOCK),any(RowMapper.class),eq(fixture.namespace))).thenReturn(List.of());
        new OracleSubmissionFixtureCleaner(jdbc,manager).clean(fixture.namespace);verify(connection).commit();verify(connection,never()).rollback();verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
}
