package com.ofss.risk.persistence;

import com.ofss.risk.upstream.ConfiguredClientFixtures;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

/** Offline guard regressions: no Oracle connection, receiver or DML. */
class ConfiguredClientFixtureSafetyTest {
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={"d2-clients-*","d2-clients-not-a-uuid","d2-clients-00000000-0000-0000-0000-000000000000/other","other-00000000-0000-0000-0000-000000000000"})
    void malformedNamespaceRefusesBeforeAnyDatabaseUse(String namespace){var jdbc=mock(JdbcTemplate.class);var tx=mock(JdbcTransactionManager.class);assertThrows(IllegalArgumentException.class,()->new OracleConfiguredClientRiskCleaner(jdbc,tx).clean(namespace,Map.of()));verifyNoInteractions(jdbc,tx);}
    Map<String,Object> pointer(long id,long revision){return Map.of("POINTER_ID",1,"VERSION_ID",id,"ROW_VERSION",revision,"ACTIVATED_BY_USER_ID",42,"ACTIVATED_AT","synthetic-instant");}
    @Test void foreignPointerUnexpectedRevisionAndMissingOwnedPointerRefuseCleanup(){assertThrows(IllegalStateException.class,()->OracleConfiguredClientConfiguration.guardPointer(null,List.of(pointer(99,0)),Set.of(7L)));
        assertThrows(IllegalStateException.class,()->OracleConfiguredClientConfiguration.guardPointer(null,List.of(pointer(7,2)),Set.of(7L)));
        assertThrows(IllegalStateException.class,()->OracleConfiguredClientConfiguration.guardPointer(pointer(7,0),List.of(pointer(7,1)),Set.of(7L)));
        assertThrows(IllegalStateException.class,()->OracleConfiguredClientConfiguration.guardPointer(pointer(7,0),List.of(),Set.of(7L)));
        assertDoesNotThrow(()->OracleConfiguredClientConfiguration.guardPointer(pointer(7,0),List.of(pointer(7,0)),Set.of(7L)));}
    @Test void existingPointerGuardRollsBackWithoutCreatingFixtures(){var jdbc=mock(JdbcTemplate.class);var tx=mock(JdbcTransactionManager.class);when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(jdbc.queryForObject("SELECT COUNT(*) FROM D2_CONFIGURATION.CURRENT_CONFIGURATION",Long.class)).thenReturn(1L);
        var helper=new OracleConfiguredClientConfiguration("d2-clients-00000000-0000-0000-0000-000000000000",jdbc,tx);assertThrows(AssertionError.class,helper::emptyPointerGuard);verify(tx).rollback(any());verify(tx,never()).commit(any());assertTrue(helper.ids.isEmpty());}
}
