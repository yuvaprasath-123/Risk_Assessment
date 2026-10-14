package com.ofss.risk.persistence;

import com.ofss.risk.engine.*;
import com.ofss.risk.contract.RuleCode;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static com.ofss.risk.engine.RiskInput.*;
import static com.ofss.risk.persistence.EvidenceFreezeRequest.*;

class CanonicalEvidenceTest {
    final CanonicalEvidence canonical=new CanonicalEvidence();
    EvidenceFreezeRequest request(RiskInput input) { return FreezeFixtures.request(FreezeFixtures.row().lease(),input); }
    CanonicalEvidence.Document input(RiskInput input) { return canonical.input(request(input),FreezeFixtures.stored(1)); }
    @Test void exactMoneyUnicodeNullsAndNanosecondsArePreservedWithoutFloatingPoint() {
        var base=FreezeFixtures.input(); var payment=new Payment("ಕನ್ನಡ 😀 \"\\\n","history","invoice","INV-हिन्दी","456","INR",new BigDecimal("9999999999999.990"),
            base.cutoff().minusSeconds(1).plusNanos(101),null,PaymentStatus.PENDING_APPROVAL,true);
        var changed=FreezeFixtures.input(FreezeFixtures.envelope("snap",List.of(payment),base.payments().coveredFrom()),base.supplier(),base.supplierTransitions(),base.previousAssessments());
        var doc=input(changed); var body=CanonicalAcceptance.read(doc.json());
        var actual=(Map<?,?>)((List<?>)((Map<?,?>)((Map<?,?>)body.get("input")).get("payments")).get("records")).getFirst();
        assertEquals("9999999999999.99",actual.get("amount")); assertEquals(payment.submittedAt().toString(),actual.get("submittedAt"));
        assertNull(actual.get("completedAt")); assertEquals(payment.evidenceId(),actual.get("evidenceId"));
        assertEquals(CanonicalAcceptance.hash(doc.json()),doc.hash()); assertEquals(64,doc.hash().length());
    }
    @Test void declaredSetsAndMapsAreCanonicalButRecordArrayOrderIsMeaningful() {
        var base=FreezeFixtures.input(); var first=new PriorAssessment("p1","s1","456",base.cutoff().minusSeconds(1),new LinkedHashSet<>(List.of(RuleCode.NEW_SUPPLIER,RuleCode.STRUCTURING)));
        var same=new PriorAssessment("p1","s1","456",first.assessedAt(),new LinkedHashSet<>(List.of(RuleCode.STRUCTURING,RuleCode.NEW_SUPPLIER)));
        var second=new PriorAssessment("p2","s2","456",first.assessedAt(),Set.of(RuleCode.UNUSUAL_AMOUNT));
        var a=FreezeFixtures.input(base.payments(),base.supplier(),base.supplierTransitions(),FreezeFixtures.envelope("prior",List.of(first,second),base.previousAssessments().coveredFrom()));
        var b=FreezeFixtures.input(base.payments(),base.supplier(),base.supplierTransitions(),FreezeFixtures.envelope("prior",List.of(same,second),base.previousAssessments().coveredFrom()));
        var reordered=FreezeFixtures.input(base.payments(),base.supplier(),base.supplierTransitions(),FreezeFixtures.envelope("prior",List.of(second,first),base.previousAssessments().coveredFrom()));
        assertEquals(input(a),input(b)); assertNotEquals(input(a).hash(),input(reordered).hash());
        var r=request(a); var reversed=new TreeMap<Kind,Source>(Comparator.reverseOrder()); reversed.putAll(r.sources());
        assertEquals(canonical.input(r,FreezeFixtures.stored(1)),canonical.input(new EvidenceFreezeRequest(r.lease(),r.acceptance(),r.input(),reversed),FreezeFixtures.stored(1)));
    }
    @Test void availabilityEmptyHistoryAndSourceProvenanceHaveDistinctHashes() {
        var base=FreezeFixtures.input(); var unavailable=Evidence.<Payment>unavailable("456","INR",base.cutoff());
        var different=FreezeFixtures.input(unavailable,base.supplier(),base.supplierTransitions(),base.previousAssessments());
        assertNotEquals(input(base).hash(),input(different).hash());
        var r=request(base); var sources=new EnumMap<>(r.sources()); sources.put(Kind.PAYMENT_HISTORY,new Source("synthetic-adapter","source-ಕನ್ನಡ😀","revision-2"));
        assertNotEquals(input(base).hash(),canonical.input(new EvidenceFreezeRequest(r.lease(),r.acceptance(),base,sources),FreezeFixtures.stored(1)).hash());
    }
    @ParameterizedTest @ValueSource(strings={"UNAVAILABLE","INCOMPLETE"}) void requiredPartialEvidenceFailsEvenWithSomeCompleteGroups(String availability) {
        var base=FreezeFixtures.input(); var partial=new Evidence<Payment>(Availability.valueOf(availability),null,"456","INR",base.cutoff(),null,List.of());
        var supplied=FreezeFixtures.input(partial,base.supplier(),base.supplierTransitions(),base.previousAssessments());
        assertEquals(FreezeFailure.Code.EVIDENCE_NOT_READY,assertThrows(FreezeFailure.class,()->EvidenceValidation.matches(request(supplied),FreezeFixtures.stored(1))).code());
    }
    @Test void exactCoverageBoundaryAllowedShortCoverageRejected() {
        var base=FreezeFixtures.input(); EvidenceValidation.matches(request(base),FreezeFixtures.stored(1));
        var shortCoverage=FreezeFixtures.envelope("payments",List.<Payment>of(),base.payments().coveredFrom().plusNanos(1));
        assertEquals(FreezeFailure.Code.EVIDENCE_NOT_READY,assertThrows(FreezeFailure.class,()->EvidenceValidation.matches(request(FreezeFixtures.input(shortCoverage,
            base.supplier(),base.supplierTransitions(),base.previousAssessments())),FreezeFixtures.stored(1))).code());
    }
    @ParameterizedTest @ValueSource(strings={"supplier","currency","cutoff","currentRetry","future","duplicate"})
    void scopeCurrentRetryFutureAndAmbiguousRecordsAreRejected(String defect) {
        var base=FreezeFixtures.input(); var p=base.current();
        var payment=new Payment("history",defect.equals("currentRetry")?p.submissionId():"older",p.invoiceId(),p.invoiceNumber(),
            defect.equals("supplier")?"999":"456",defect.equals("currency")?"USD":"INR",p.amount(),
            defect.equals("future")?base.cutoff().plusNanos(1):base.cutoff().minusSeconds(1),null,PaymentStatus.APPROVED,true);
        var records=new ArrayList<>(List.of(payment));
        if(defect.equals("duplicate")) records.add(new Payment(payment.evidenceId(),payment.submissionId(),payment.invoiceId(),"different",payment.supplierId(),
            payment.currencyCode(),payment.amount(),payment.submittedAt(),null,payment.statusAsOfCutoff(),true));
        var envelope=new Evidence<>(Availability.COMPLETE,"snap","456","INR",defect.equals("cutoff")?base.cutoff().plusNanos(1):base.cutoff(),base.payments().coveredFrom(),records);
        assertEquals(FreezeFailure.Code.EVIDENCE_MISMATCH,assertThrows(FreezeFailure.class,()->EvidenceValidation.matches(request(FreezeFixtures.input(envelope,
            base.supplier(),base.supplierTransitions(),base.previousAssessments())),FreezeFixtures.stored(1))).code());
    }
    @Test void sourceLimitsUseUnicodeCodepointsAndTypedConfigurationIsMandatory() {
        assertDoesNotThrow(()->new Source("😀".repeat(64),"😀".repeat(128),null));
        assertThrows(IllegalArgumentException.class,()->new Source("😀".repeat(65),null,null));
        var original=FreezeFixtures.acceptance(); var absent=AcceptanceFixtures.copy(original,null,original.paymentId(),original.validationRevision(),original.cutoff(),original.currentAndRetrySubmissionIds(),null);
        assertThrows(NullPointerException.class,()->FreezeFixtures.request(FreezeFixtures.row().lease(),absent,FreezeFixtures.input()));
    }
    @Test void enabledConfigWindowsDetermineRequiredCoverageInsteadOfFixedDemonstrationWindows() {
        var original=FreezeFixtures.acceptance(); var c=original.configuration();
        var rules=c.rules().stream().map(r->new RiskConfiguration.Rule(r.code(),r.enabled() && r.code()!=RuleCode.UNUSUAL_AMOUNT
            && r.code()!=RuleCode.NEAR_DUPLICATE_INVOICE,r.weight(),r.evaluationOrder(),r.parameterSchemaVersion(),r.parameters())).toList();
        var config=new RiskConfiguration(c.versionId(),c.rowVersion(),c.contentHash(),c.currencyCode(),c.maxFinalScore(),rules,c.bands(),c.approvalBoundaries());
        var accepted=AcceptanceFixtures.copy(original,config,original.paymentId(),original.validationRevision(),original.cutoff(),original.currentAndRetrySubmissionIds(),null);
        var base=FreezeFixtures.input(); var shorter=FreezeFixtures.input(FreezeFixtures.envelope("seven-day",List.<Payment>of(),base.cutoff().minusSeconds(604800)),
            base.supplier(),base.supplierTransitions(),base.previousAssessments());
        EvidenceValidation.matches(FreezeFixtures.request(FreezeFixtures.row().lease(),accepted,shorter),FreezeFixtures.stored(accepted,1));
        assertEquals(FreezeFailure.Code.EVIDENCE_NOT_READY,assertThrows(FreezeFailure.class,()->EvidenceValidation.matches(request(shorter),FreezeFixtures.stored(1))).code());
    }
    @Test void evidenceIdsAreScopedPerDependencyAndOriginalPriorFactorSetsRemainExact() {
        var base=FreezeFixtures.input(); var supplier=base.supplier().records().getFirst();
        var prior=new PriorAssessment(supplier.evidenceId(),"prior","456",base.cutoff().minusNanos(1),Set.of(RuleCode.NEW_SUPPLIER,RuleCode.STRUCTURING));
        var supplied=FreezeFixtures.input(base.payments(),base.supplier(),base.supplierTransitions(),FreezeFixtures.envelope("prior",List.of(prior),base.previousAssessments().coveredFrom()));
        EvidenceValidation.matches(request(supplied),FreezeFixtures.stored(1));
        assertTrue(input(supplied).json().contains("STRUCTURING")); assertTrue(input(supplied).json().contains("NEW_SUPPLIER"));
    }
}
