package com.ofss.risk.persistence;

import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FrozenAssessmentLoaderTest {
    final RiskAcceptanceRepository acceptance=mock(RiskAcceptanceRepository.class);
    final RiskWorkRepository work=mock(RiskWorkRepository.class);
    final RiskEvidenceRepository evidence=mock(RiskEvidenceRepository.class);
    final FrozenAssessmentLoader loader=new FrozenAssessmentLoader(acceptance,work,evidence);
    final FrozenAssessmentLoader.Snapshot s=FinalizationFixtures.snapshot();
    @BeforeEach void setup() {
        when(acceptance.find(s.accepted().identity())).thenReturn(Optional.of(s.accepted())); when(work.read(17)).thenReturn(s.work());
        when(evidence.header(17)).thenReturn(s.header()); when(evidence.children(17)).thenReturn(s.evidence());
    }
    @Test void reconstructsExactConfigurationInputUnicodeAndNanosecondsFromOnlyStoredRows() {
        var actual=loader.load(FinalizationFixtures.request(s)); assertEquals(new CanonicalAcceptance().configuration(s.config()),new CanonicalAcceptance().configuration(actual.config())); assertEquals(s.input(),actual.input());
        assertEquals(123456789,actual.input().cutoff().getNano()); assertTrue(actual.evidence().toString().contains("ಕನ್ನಡ😀"));
        assertEquals("1.0000000000000001",((com.ofss.risk.contract.RuleParameters.Amount)actual.config().rules().get(2).parameters()).meanMultiplier().toPlainString());
    }
    @ParameterizedTest @ValueSource(strings={"acceptance","configuration","input"}) void callerHashesArePreconditionsNotTrustedValues(String changed) {
        var r=FinalizationFixtures.request(s); String other="0".repeat(64);
        var request=new FinalizationRequest(r.identity(),r.lease(),changed.equals("acceptance")?other:r.acceptanceHash(),changed.equals("configuration")?other:r.configurationHash(),changed.equals("input")?other:r.inputHash());
        assertEquals(FinalizationFailure.Code.FINALIZATION_CONFLICT,assertThrows(FinalizationFailure.class,()->loader.load(request)).code());
    }
    @Test void missingFrozenInputCannotBeEvaluated() {
        when(evidence.header(17)).thenReturn(new RiskEvidenceRepository.Header(null,null,null));
        assertEquals(FinalizationFailure.Code.FROZEN_INPUT_REQUIRED,assertThrows(FinalizationFailure.class,()->loader.load(FinalizationFixtures.request(s))).code());
    }
    @ParameterizedTest @ValueSource(ints={0,1,2,3,4,5}) void changingAnyFrozenChildOrManifestFailsVerification(int index) {
        var rows=new ArrayList<>(s.evidence()); var old=rows.get(index); rows.set(index,old.withId(old.id()+100));
        when(evidence.children(17)).thenReturn(rows); assertThrows(RuntimeException.class,()->loader.load(FinalizationFixtures.request(s)));
    }
    @Test void incompleteManifestFailsRatherThanInventingEmptyHistory() {
        when(evidence.children(17)).thenReturn(s.evidence().subList(1,6));
        assertThrows(RuntimeException.class,()->loader.load(FinalizationFixtures.request(s)));
    }
    @Test void unknownConfigurationFormatIsNotInterpretedAsLatest() {
        assertThrows(RuntimeException.class,()->StoredConfigurationDecoder.read(s.accepted().configurationJson().replace("risk-engine-v1","risk-engine-v2")));
    }
    @Test void canonicalDecoderPreservesConfigEvenWithReorderedOriginalCollections() {
        assertEquals(new CanonicalAcceptance().configuration(s.config()),new CanonicalAcceptance().configuration(StoredConfigurationDecoder.read(s.accepted().configurationJson())));
    }
}
