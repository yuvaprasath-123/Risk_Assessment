package com.ofss.risk.facts;

import com.ofss.risk.persistence.*;
import com.ofss.risk.submission.*;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.ofss.risk.facts.FactsHttpFixtures.*;

class FactsProviderProtocolTest {
    final FactsProviderProtocol protocol=new FactsProviderProtocol();
    void rejected(Map<String,Object> root){var failure=assertThrows(SubmissionFailure.class,()->protocol.decode(bytes(root),command(),CUTOFF,PROVIDER));assertEquals(SubmissionFailure.Code.DEPENDENCY_UNAVAILABLE,failure.code());assertNull(failure.getCause());}
    @Test void exactMoneyNanosecondsNamespacedAliasesAndProvenanceSurviveExistingAcceptanceFreeze(){
        var facts=protocol.decode(bytes(response(command(),CUTOFF)),command(),CUTOFF,PROVIDER);
        assertEquals(9007199254740993L,facts.paymentId());assertEquals(Long.MAX_VALUE,facts.requesterId());assertEquals(new BigDecimal("9007199254740.91"),facts.current().amount());
        assertEquals(CUTOFF.minusSeconds(10),facts.current().submittedAt());assertEquals("INV-ಕನ್ನಡ-😀",facts.current().invoiceNumber());assertTrue(facts.current().validated());
        assertEquals(FactsProviderProtocol.alias(command().identity()),facts.current().submissionId());assertEquals(2,facts.currentAndRetrySubmissionIds().size());
        var provenance=facts.current().evidenceId().split(":");assertEquals(4,provenance.length);
        assertEquals(PROVIDER,new String(Base64.getUrlDecoder().decode(provenance[1]),StandardCharsets.UTF_8));
        assertEquals("validation/Ω?source=1",new String(Base64.getUrlDecoder().decode(provenance[2]),StandardCharsets.UTF_8));
        assertEquals("record-v17",new String(Base64.getUrlDecoder().decode(provenance[3]),StandardCharsets.UTF_8));
        var accepted=facts.acceptance(CUTOFF,SubmissionFixtures.request().configuration());var canonical=new CanonicalAcceptance();var config=canonical.configuration(accepted.configuration());
        var body=canonical.acceptance(accepted,accepted.configuration().versionId(),accepted.configuration().rowVersion(),config.hash());
        var stored=new AcceptedAssessment("1",accepted.identity(),"2",accepted.configuration().versionId(),accepted.configuration().rowVersion(),"0","ACCEPTED",CUTOFF,CUTOFF,body.hash(),config.hash(),body.json(),config.json());
        var restored=new SubmissionSnapshotReader(mock(RiskAcceptanceRepository.class),mock(RiskWorkRepository.class),mock(RiskEvidenceRepository.class),mock(org.springframework.transaction.PlatformTransactionManager.class)).restore(stored);
        assertEquals(accepted.identity(),restored.identity());assertEquals(accepted.paymentId(),restored.paymentId());assertEquals(accepted.requesterId(),restored.requesterId());
        assertEquals(accepted.validationReference(),restored.validationReference());assertEquals(accepted.validationRevision(),restored.validationRevision());
        assertEquals(accepted.cutoff(),restored.cutoff());assertEquals(accepted.current(),restored.current());
        assertEquals(body,canonical.acceptance(restored,restored.configuration().versionId(),restored.configuration().rowVersion(),config.hash()));
        assertEquals(facts.current().evidenceId(),restored.current().evidenceId());assertEquals(facts.currentAndRetrySubmissionIds(),restored.currentAndRetrySubmissionIds());
        assertThrows(UnsupportedOperationException.class,()->facts.currentAndRetrySubmissionIds().clear());
    }
    @ParameterizedTest @CsvSource({"contractVersion,future","paymentId,4","paymentId,9223372036854775808","paymentId,01","requesterId,0","submission.source,other","submission.key,other",
        "validation.reference,other","validation.revision,18","validation.outcome,FAIL","provenance.serviceId,other-provider","provenance.recordRevision,''",
        "payment.invoiceId,76","payment.supplierId,457","invoice.supplierId,457","supplier.supplierId,457","payment.currencyCode,USD","invoice.currencyCode,USD",
        "supplier.statusAsOfCutoff,INACTIVE","payment.statusAsOfCutoff,DRAFT","payment.statusAsOfCutoff,FAILED","certification.retryIdentityScope,GENUINE_RESUBMISSION","certification.code,HASH_ONLY",
        "payment.amount,1.001","payment.amount,1e3","payment.amount,10000000000000.00","payment.amount,0.00","invoice.invoiceAmount,-1.00"})
    void mismatchedReferencesUnsupportedSemanticsAndLossyNumbersFailClosed(String field,String value){var root=response(command(),CUTOFF);change(root,field,value);rejected(root);}
    @ParameterizedTest @ValueSource(strings={"complete","asOfCutoff","immutable","validationPaymentBindingVerified","requesterOwnershipVerified","eligibleForRiskAssessment"})
    void certificationIsMandatoryAndStrictBoolean(String flag){var root=response(command(),CUTOFF);object(root,"certification").put(flag,false);rejected(root);object(root,"certification").put(flag,"true");rejected(root);object(root,"certification").remove(flag);rejected(root);}
    @ParameterizedTest @ValueSource(strings={"2026-10-15T10:20:30","2026-10-15T10:20:30-00:00","2026-10-15T10:20:30.1234567891Z","0000-01-01T00:00:00Z","2026-10-15T10:20:30+19:00","2026-10-15T10:20:30Z[UTC]"})
    void ambiguousOrUnsupportedTimestampsAreNeverRoundedOrGivenAnAssumedZone(String value){var root=response(command(),CUTOFF);change(root,"payment.submittedAt",value);rejected(root);}
    @ParameterizedTest @ValueSource(strings={"validation.validatedAt","payment.submittedAt","supplier.createdAt","cutoff"})
    void cutoffAndCausalBoundariesAreEnforced(String field){var root=response(command(),CUTOFF);change(root,field,offset(CUTOFF.plusNanos(1)));rejected(root);}
    @Test void nullRevisionRequiresCertifiedImmutableReferenceAndNeverAdoptsANewRevision(){
        var c=command();var omitted=new SubmissionCommand(c.contractVersion(),c.submissionSource(),c.submissionKey(),c.paymentReference(),c.validationReference(),null,null);
        var root=response(omitted,CUTOFF);assertNull(protocol.decode(bytes(root),omitted,CUTOFF,PROVIDER).validationRevision());
        object(root,"validation").put("immutableReference",false);assertThrows(SubmissionFailure.class,()->protocol.decode(bytes(root),omitted,CUTOFF,PROVIDER));
        object(root,"validation").put("immutableReference",true);object(root,"validation").put("revision","invented");assertThrows(SubmissionFailure.class,()->protocol.decode(bytes(root),omitted,CUTOFF,PROVIDER));
    }
    @Test void duplicateMissingOrUncertifiedRetryIdentitiesCannotReduceTheHistoryPopulation(){
        var root=response(command(),CUTOFF);var pair=object(root,"submission");root.put("currentAndRetryIdentities",List.of(pair,pair));rejected(root);
        root.put("currentAndRetryIdentities",List.of(map("source","other","key","different")));rejected(root);
        root.put("currentAndRetryIdentities",Collections.nCopies(1001,pair));rejected(root);
    }
    @Test void missingSectionsUnknownFieldsNumericMoneyAndDuplicateJsonAreRejected(){
        for(var key:response(command(),CUTOFF).keySet()){var root=response(command(),CUTOFF);root.remove(key);rejected(root);}
        var root=response(command(),CUTOFF);root.put("hash","a".repeat(64));rejected(root);
        root=response(command(),CUTOFF);change(root,"payment.amount",new BigDecimal("1.00"));rejected(root);
        var json=new String(bytes(response(command(),CUTOFF)),StandardCharsets.UTF_8);
        var duplicate=json.replace("\"outcome\":\"PASS\"","\"outcome\":\"PASS\",\"outcome\":\"FAIL\"");
        assertThrows(SubmissionFailure.class,()->protocol.decode(duplicate.getBytes(StandardCharsets.UTF_8),command(),CUTOFF,PROVIDER));
        assertThrows(SubmissionFailure.class,()->protocol.decode((json+" {}").getBytes(StandardCharsets.UTF_8),command(),CUTOFF,PROVIDER));
    }
    @Test void requestContainsOnlyFixedVersionedReferencesAndUtcCutoff(){
        @SuppressWarnings("unchecked")var request=JSON.readValue(protocol.request(command(),CUTOFF),Map.class);
        assertEquals(Set.of("contractVersion","submission","paymentId","validationReference","validationRevision","cutoff"),request.keySet());
        assertEquals(CUTOFF.toString(),request.get("cutoff"));assertFalse(request.containsKey("configurationVersionId"));assertFalse(request.containsKey("callbackUrl"));
    }
}
