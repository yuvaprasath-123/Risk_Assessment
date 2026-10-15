package com.ofss.risk.persistence;

import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class PriorCaptureSafetyTest {
    @ParameterizedTest @NullSource @ValueSource(strings={"","%","D2_RISK_PRIOR_%","D2_RISK_PRIOR_not-a-uuid","D2_RISK_PRIOR_01234567-89AB-CDEF-0123-456789ABCDEF"})
    void recoveryRejectsBroadOrNoncanonicalNamespacesBeforeOpeningAConnection(String namespace){var ds=mock(DataSource.class);assertThrows(IllegalArgumentException.class,()->new OraclePriorCaptureCleaner(new JdbcTemplate(ds),new JdbcTransactionManager(ds)).clean(namespace));verifyNoInteractions(ds);}
    @Test void liveTestAndRecoveryAreExplicitlyOptedIn(){
        assertEquals("RISK_VERIFY_ORACLE_PRIOR_CAPTURE_WRITES",OraclePriorCaptureTest.class.getAnnotation(EnabledIfEnvironmentVariable.class).named());
        var gates=OraclePriorCaptureCleanupTest.class.getAnnotationsByType(EnabledIfEnvironmentVariable.class);assertEquals(2,gates.length);
    }
    @Test void fixtureNamespacesSuppliersAndKeysAreBoundedAndSynthetic(){var first=new OraclePriorCaptureFixtures();var second=new OraclePriorCaptureFixtures();assertNotEquals(first.namespace,second.namespace);assertTrue(first.namespace.length()<=64);
        assertEquals(OraclePriorCaptureFixtures.CASES.size(),OraclePriorCaptureFixtures.CASES.stream().map(first::supplier).distinct().count());assertEquals(48,OraclePriorCaptureFixtures.KEYS.size());
    }
    @Test void codecRejectsMissingProofEvenWithMatchingEnvelopeHash(){var a=FreezeFixtures.acceptance();var r=new PriorAssessmentHistory.Request(a,Set.of(),null);var good=PriorCaptureFixtures.history(r,1);var missing=new PriorAssessmentHistory(good.evidence(),good.source());
        var row=PriorHistoryCaptureCodec.encode(r,missing,FreezeFixtures.NOW).withId(100);assertThrows(IllegalArgumentException.class,()->PriorHistoryCaptureCodec.decode(row,FreezeFixtures.stored(a,1)));
    }
    @Test void unknownPartialRowsNeverBecomeAcceptedCapture(){var a=FreezeFixtures.acceptance();var r=new PriorAssessmentHistory.Request(a,Set.of(),null);var row=PriorHistoryCaptureCodec.encode(r,PriorCaptureFixtures.history(r,0),FreezeFixtures.NOW).withId(100);
        assertTrue(PriorHistoryCaptureCodec.partial(List.of(row),FreezeFixtures.stored(a,1)).isPresent());assertThrows(IllegalArgumentException.class,()->PriorHistoryCaptureCodec.partial(List.of(row,row),FreezeFixtures.stored(a,1)));
    }
}
