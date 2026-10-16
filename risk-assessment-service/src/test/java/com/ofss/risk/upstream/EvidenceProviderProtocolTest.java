package com.ofss.risk.upstream;

import com.ofss.risk.engine.*;
import com.ofss.risk.engine.RiskInput.*;
import com.ofss.risk.facts.FactsProviderProtocol;
import com.ofss.risk.persistence.*;
import com.ofss.risk.submission.*;
import java.math.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.ofss.risk.upstream.EvidenceHttpFixtures.*;

class EvidenceProviderProtocolTest {
    EvidenceProviderProtocol.Collector first(Map<String,Object> response){var c=protocol().collector();c.accept(bytes(response),10,100);return c;}
    void rejected(Map<String,Object> response){var e=assertThrows(SubmissionFailure.class,()->first(response));assertEquals(SubmissionFailure.Code.DEPENDENCY_UNAVAILABLE,e.code());assertNull(e.getCause());}
    @Test void exactPopulationBoundariesExclusionsSourcesAndOrdering(){
        var c=first(response(request(0),0));assertTrue(c.more());assertEquals(1,c.nextIndex());assertEquals("opaque-page-1",c.cursor());c.accept(bytes(response(request(1),1)),10,100);
        var b=c.finish(false);assertEquals(Availability.COMPLETE,b.payments().availability());assertEquals(3,b.payments().records().size());
        assertEquals(new BigDecimal("0.00"),b.payments().records().getFirst().amount());assertEquals(protocol().paymentFrom,b.payments().records().getFirst().completedAt());
        assertEquals(new BigDecimal("9999999999999.99"),b.payments().records().get(1).amount());assertEquals(accepted().cutoff().minusNanos(1),b.payments().records().getLast().submittedAt());
        assertEquals(accepted().cutoff(),b.transitions().records().getLast().changedAt());assertEquals(protocol().transitionFrom,b.transitions().records().getFirst().changedAt());
        assertEquals(2,b.retryIdentities().size());assertEquals(3,b.sources().size());assertFalse(b.sources().containsKey(EvidenceFreezeRequest.Kind.PRIOR_ASSESSMENTS));assertNull(b.expectedPriorPopulationHash());
        assertThrows(UnsupportedOperationException.class,()->b.payments().records().clear());
        var input=new RiskInput(accepted().current(),accepted().cutoff(),accepted().currentAndRetrySubmissionIds(),b.payments(),b.supplier(),b.transitions(),
            new Evidence<>(Availability.COMPLETE,"local-prior","456","INR",accepted().cutoff(),protocol().paymentFrom,List.of()));
        assertEquals(RiskResult.Status.COMPLETED,new RiskScoringEngine().evaluate(accepted().configuration(),input).status());
    }
    @Test void identicalReplayAndPageSizeIndependentReference(){
        var p=protocol();assertEquals(p.replay,new EvidenceProviderProtocol(accepted(),PROVIDER,7).replay);assertArrayEquals(p.request(0,null),protocol().request(0,null));
        var r=accepted();var other=new AcceptanceRequest(r.identity(),r.paymentId(),r.requesterId(),r.validationReference(),"different",r.current(),r.cutoff(),r.currentAndRetrySubmissionIds(),r.evaluatorVersion(),r.configuration(),null);
        assertNotEquals(p.replay,new EvidenceProviderProtocol(other,PROVIDER,2).replay);
    }
    @ParameterizedTest @ValueSource(strings={"contractVersion","request","providerServiceId","replayReference","snapshotRevision","populationHash","certification","pagination","paymentHistory","supplier","supplierTransitions"})
    void everySectionMandatory(String field){var r=response(request(0),0);r.remove(field);rejected(r);}
    @ParameterizedTest @ValueSource(strings={"immutable","asOfCutoff","supplierWideAcrossRequesters","exclusionsApplied","factsReceiptBindingVerified"})
    void certificationIsNotAHashOrCoercibleValue(String flag){var r=response(request(0),0);object(r,"certification").put(flag,false);rejected(r);object(r,"certification").put(flag,"true");rejected(r);}
    @ParameterizedTest @ValueSource(strings={"supplierId","cutoff","paymentId","validationReference","validationRevision","factsReceipt","excludedIdentities","configuration","acceptanceHash"})
    void echoedRequestCannotChange(String field){var r=response(request(0),0);object(r,"request").put(field,"changed");rejected(r);}
    @ParameterizedTest @ValueSource(strings={"provider","snapshot","receipt","revision","currency","supplier","identity","amount","number-money","timestamp","completion","outside","same-transition","prior-history"})
    void corruptPopulationsFailClosed(String scenario){var r=response(request(0),0);var row=rows(r,"paymentHistory").getFirst();
        switch(scenario){
            case "provider"->r.put("providerServiceId","impostor");case "snapshot"->r.put("replayReference","other");case "receipt"->object(r,"paymentHistory").put("sourceReference","other");case "revision"->object(r,"supplier").put("sourceRevision","2");
            case "currency"->row.put("currencyCode","USD");case "supplier"->row.put("supplierId","999");case "identity"->row.put("submission",map("source",accepted().identity().source(),"key",accepted().identity().key()));
            case "amount"->row.put("amount","1.001");case "number-money"->row.put("amount",new BigDecimal("1.00"));case "timestamp"->row.put("submittedAt","2026-10-14T00:00:00");case "completion"->row.put("statusAsOfCutoff","COMPLETE");
            case "outside"->row.put("submittedAt",offset(protocol().paymentFrom.minusNanos(1)));case "same-transition"->rows(r,"supplierTransitions").getFirst().put("nextStatus","ACTIVE");case "prior-history"->r.put("previousAssessments",List.of());
        }rejected(r);
    }
    @ParameterizedTest @ValueSource(strings={"pageIndex","pageSize","totalPages","totalPayments","totalTransitions","nextCursor","metadata","duplicate","cutoff","snapshot"})
    void pagesCannotBeMixedOrDuplicated(String field){var c=first(response(request(0),0));var second=response(request(1),1);
        switch(field){case "nextCursor"->object(second,"pagination").put(field,"opaque-page-1");case "metadata"->object(second,"paymentHistory").put("availability","INCOMPLETE");
            case "duplicate"->object(second,"paymentHistory").put("records",List.of(rows(response(request(0),0),"paymentHistory").getFirst()));
            case "cutoff"->object(second,"request").put("cutoff",accepted().cutoff().plusNanos(1).toString());case "snapshot"->second.put("snapshotRevision","2");
            default->object(second,"pagination").put(field,999);}
        assertThrows(SubmissionFailure.class,()->c.accept(bytes(second),10,100));
    }
    @Test void missingPagesAndCapacityNeverBecomeCompleteEmptyHistory(){
        var c=first(response(request(0),0));var partial=c.finish(true);assertEquals(Availability.INCOMPLETE,partial.payments().availability());assertTrue(partial.payments().records().isEmpty());
        for(int[] limits:new int[][]{{1,100},{10,2}}){c=protocol().collector();c.accept(bytes(response(request(0),0)),limits[0],limits[1]);assertTrue(c.atCapacity());assertFalse(c.more());assertEquals(Availability.INCOMPLETE,c.finish(false).payments().availability());}
    }
    @ParameterizedTest @ValueSource(strings={"COMPLETE","INCOMPLETE","UNAVAILABLE"})
    void emptyHistoryRetainsExplicitAvailability(String state){var r=response(request(0),0);object(r,"pagination").putAll(map("totalPages",1,"totalPayments",0,"totalTransitions",0,"nextCursor",null));
        for(String name:List.of("paymentHistory","supplierTransitions")){object(r,name).put("records",List.of());object(r,name).put("availability",state);if(state.equals("UNAVAILABLE"))object(r,name).put("coveredFrom",null);}
        rehash(List.of(r));var b=first(r).finish(false);assertTrue(b.payments().records().isEmpty());assertEquals(Availability.valueOf(state),b.payments().availability());
    }
    @Test void shortCoverageAndFalseCountsCannotClaimCompleteness(){var r=response(request(0),0);var second=response(request(1),1);
        object(r,"paymentHistory").put("coveredFrom",offset(protocol().paymentFrom.plusNanos(1)));object(second,"paymentHistory").put("coveredFrom",object(r,"paymentHistory").get("coveredFrom"));
        rehash(List.of(r,second));var c=first(r);c.accept(bytes(second),10,100);assertEquals(Availability.INCOMPLETE,c.finish(false).payments().availability());
        second=response(request(1),1);object(second,"paymentHistory").put("records",List.of());var good=first(response(request(0),0));var missing=second;assertThrows(SubmissionFailure.class,()->good.accept(bytes(missing),10,100));
    }
    @Test void unknownDuplicateTrailingAndAmbiguousOffsetsReject(){var r=response(request(0),0);r.put("hash","a".repeat(64));rejected(r);
        var c=protocol().collector();var bytes=bytes(response(request(0),0));var json=new String(bytes,java.nio.charset.StandardCharsets.UTF_8);
        assertThrows(SubmissionFailure.class,()->c.accept((json+" {}").getBytes(java.nio.charset.StandardCharsets.UTF_8),10,100));
        for(String t:List.of("2026-10-14T00:00:00-00:00","2026-10-14T00:00:00.1234567891Z")){r=response(request(0),0);rows(r,"paymentHistory").getFirst().put("submittedAt",t);rejected(r);}
    }
    @ParameterizedTest @ValueSource(strings={"pageIndex","pageSize","totalPages","totalPayments","totalTransitions"})
    void paginationCountersMustBeExactIntegers(String field){for(Object value:List.of("2",new BigDecimal("2.0"),new BigInteger("9223372036854775808"))){var r=response(request(0),0);object(r,"pagination").put(field,value);rejected(r);}}
    @ParameterizedTest @ValueSource(strings={"retry","cutoff","overflow-money","invoice-missing","unvalidated-type","duplicate-evidence"})
    void recordBoundariesAndRetryExclusionsAreStrict(String mode){var r=response(request(0),0);var row=rows(r,"paymentHistory").getFirst();switch(mode){
        case "retry"->row.put("submission",map("source","legacy.payment.v1","key","retry-42"));case "cutoff"->row.put("submittedAt",offset(accepted().cutoff()));
        case "overflow-money"->row.put("amount","10000000000000.00");case "invoice-missing"->row.remove("invoiceNumber");case "unvalidated-type"->row.put("validated","true");
        case "duplicate-evidence"->rows(r,"paymentHistory").get(1).put("recordReference",row.get("recordReference"));}rejected(r);}
    @ParameterizedTest @ValueSource(strings={"chain","same-instant","final-status","before-creation"})
    void completeSupplierTimelineCannotContradictItsSnapshot(String mode){var r=response(request(0),0);var second=response(request(1),1);var later=rows(second,"supplierTransitions").getFirst();
        switch(mode){case "chain"->{rows(r,"supplierTransitions").getFirst().put("previousStatus","INACTIVE");rows(r,"supplierTransitions").getFirst().put("nextStatus","ACTIVE");}
            case "same-instant"->later.put("changedAt",offset(protocol().transitionFrom));
            case "final-status"->{later.put("previousStatus","ACTIVE");later.put("nextStatus","INACTIVE");}
            case "before-creation"->rows(r,"supplier").getFirst().put("createdAt",offset(accepted().current().submittedAt()));}
        rehash(List.of(r,second));var c=first(r);c.accept(bytes(second),10,100);assertThrows(SubmissionFailure.class,()->c.finish(false));
    }
    @Test void disabledHistoryHasExplicitUnavailableEnvelopeAndLegacyAliasesFailClosed(){
        var r=accepted();var config=r.configuration();var disabled=new RiskConfiguration(config.versionId(),config.rowVersion(),config.contentHash(),config.currencyCode(),config.maxFinalScore(),
            config.rules().stream().map(rule->new RiskConfiguration.Rule(rule.code(),false,rule.weight(),rule.evaluationOrder(),rule.parameterSchemaVersion(),rule.parameters())).toList(),config.bands(),config.approvalBoundaries());
        var request=new AcceptanceRequest(r.identity(),r.paymentId(),r.requesterId(),r.validationReference(),r.validationRevision(),r.current(),r.cutoff(),r.currentAndRetrySubmissionIds(),r.evaluatorVersion(),disabled,null);
        var p=new EvidenceProviderProtocol(request,PROVIDER,2);assertNull(p.paymentFrom);assertNull(p.transitionFrom);
        var wire=JSON.readValue(p.request(0,null),Map.class);var response=response(wire,0);object(response,"pagination").putAll(map("totalPages",1,"totalPayments",0,"totalTransitions",0,"nextCursor",null));
        for(String name:List.of("paymentHistory","supplierTransitions")){object(response,name).putAll(map("availability","UNAVAILABLE","coveredFrom",null,"records",List.of()));}
        rehash(List.of(response));var c=p.collector();c.accept(bytes(response),10,100);assertEquals(Availability.UNAVAILABLE,c.finish(false).payments().availability());
        assertThrows(SubmissionFailure.class,()->new EvidenceProviderProtocol(SubmissionFixtures.request(),PROVIDER,2));
    }
    @Test void unchangedSnapshotLabelsCannotHideChangedPageContent(){var c=first(response(request(0),0));var second=response(request(1),1);rows(second,"paymentHistory").getFirst().put("amount","1.00");
        c.accept(bytes(second),10,100);assertThrows(SubmissionFailure.class,()->c.finish(false));}
    @Test void populationDigestMustMatchAcrossPages(){var c=first(response(request(0),0));var second=response(request(1),1);second.put("populationHash","a".repeat(64));assertThrows(SubmissionFailure.class,()->c.accept(bytes(second),10,100));}
    @Test void populationDigestCanonicalizationHasAnIndependentExactWireVector()throws Exception{
        var row=new LinkedHashMap<String,Object>();row.put("at","2026-10-14T05:30:00.123456789+05:30");row.put("amount","1.00");
        var population=map("b",null,"a",List.of(row));
        var canonical="{\"a\":[{\"amount\":\"1.00\",\"at\":\"2026-10-14T05:30:00.123456789+05:30\"}],\"b\":null}";
        var expected=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(expected,EvidenceProviderProtocol.digest(population));
        row.put("at","2026-10-14T00:00:00.123456789Z");assertNotEquals(expected,EvidenceProviderProtocol.digest(population));
        assertNotEquals(EvidenceProviderProtocol.digest(map("records",List.of("first","second"))),EvidenceProviderProtocol.digest(map("records",List.of("second","first"))));
    }
}
