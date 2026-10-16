package com.ofss.risk.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class SubmissionSnapshotReaderTest {
    SubmissionSnapshotReader reader=new SubmissionSnapshotReader(mock(RiskAcceptanceRepository.class),mock(RiskWorkRepository.class),mock(RiskEvidenceRepository.class),mock(PlatformTransactionManager.class));
    @Test void reconstructsExactAcceptedFactsConfigurationAndNanoseconds(){var expected=SubmissionFixtures.request();var actual=reader.restore(SubmissionFixtures.stored("ACCEPTED"));
        assertEquals(new CanonicalAcceptance().configuration(expected.configuration()),new CanonicalAcceptance().configuration(actual.configuration()));
        assertEquals(expected.current(),actual.current());assertEquals(expected.currentAndRetrySubmissionIds(),actual.currentAndRetrySubmissionIds());assertEquals(expected.cutoff(),actual.cutoff());
        assertEquals("9999999999999.99",actual.current().amount().toPlainString());assertEquals(123456789,actual.cutoff().getNano());}
    @Test void mutatedAcceptedJsonCannotBeUsedForRecovery(){var a=SubmissionFixtures.stored("ACCEPTED");var bad=new AcceptedAssessment(a.assessmentId(),a.identity(),a.configurationSnapshotId(),a.configurationVersionId(),a.configurationRowVersion(),a.rowVersion(),a.workState(),a.cutoff(),a.createdAt(),a.acceptanceHash(),a.configurationHash(),a.acceptanceJson().replace("9999999999999.99","1.00"),a.configurationJson());
        var failure=assertThrows(AcceptanceFailure.class,()->reader.restore(bad));assertEquals(AcceptanceFailure.Code.RISK_PERSISTENCE_UNAVAILABLE,failure.code());assertNull(failure.getCause());}
    @Test void mutatedConfigurationCannotBeUsedForRecovery(){var a=SubmissionFixtures.stored("ACCEPTED");var bad=new AcceptedAssessment(a.assessmentId(),a.identity(),a.configurationSnapshotId(),a.configurationVersionId(),a.configurationRowVersion(),a.rowVersion(),a.workState(),a.cutoff(),a.createdAt(),a.acceptanceHash(),a.configurationHash(),a.acceptanceJson(),a.configurationJson().replace("1.0000000000000001","1.0000000000000002"));
        assertThrows(AcceptanceFailure.class,()->reader.restore(bad));}
}
