package com.ofss.risk.configuration;

import com.ofss.risk.submission.*;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static com.ofss.risk.configuration.ConfigurationHttpFixtures.*;

class ConfigurationSnapshotResponseTest {
    PublishedSnapshotResponse decoder=new PublishedSnapshotResponse();
    void rejected(byte[] body){var e=assertThrows(SubmissionFailure.class,()->decoder.decode(body,null));assertEquals(SubmissionFailure.Code.DEPENDENCY_UNAVAILABLE,e.code());assertNull(e.getCause());}
    @Test void completeActiveDocumentHasExactIdentityAndVersionRevisionNotPointerRevision(){
        var root=content("9007199254740993");var snapshot=decoder.decode(bytes(envelope(root,true)),null);
        assertEquals("9",snapshot.rowVersion());assertEquals("9007199254740993",snapshot.versionId());assertEquals(hash(root),snapshot.contentHash());
        assertEquals(canonical(root),snapshot.canonicalConfiguration());var mapped=new PublishedConfigurationMapping().map(snapshot);
        assertEquals("9",mapped.rowVersion());assertEquals(100,mapped.maxFinalScore());assertEquals(new BigDecimal("123.00"),mapped.approvalBoundaries().get(2));
        assertTrue(snapshot.canonicalConfiguration().contains("999999999999"));assertTrue(snapshot.canonicalConfiguration().contains("परीक्षण"));
    }
    @Test void pinnedShapeAcceptedOnlyForExactRequestedVersion(){var body=response(false);assertEquals("9",decoder.decode(body,"9007199254740993").rowVersion());assertThrows(SubmissionFailure.class,()->decoder.decode(body,"8"));rejected(body);}
    @ParameterizedTest @ValueSource(strings={"riskRules","riskBands","approvalThresholds","approvalSlas"})
    void completeHashIncludesEveryChildSection(String section){var root=content("1");var body=envelope(root,true);rows(root,section).getFirst().put(section.equals("riskRules")?"displayName":section.equals("riskBands")?"approvalLevel":section.equals("approvalThresholds")?"minAmountInclusive":"durationSeconds",section.equals("riskRules")?"changed":new BigInteger("2"));rejected(bytes(body));}
    @Test void unknownHashFormatAndHashCorruptionRejected(){var b=envelope(content("1"),true);b.put("hashFormat","future-format");rejected(bytes(b));b.put("hashFormat","configuration-content-v1");b.put("contentHash","0".repeat(64));rejected(bytes(b));}
    @ParameterizedTest @ValueSource(strings={"ruleConfigId","displayName","parameterSchemaVersion","parameters"})
    void missingCompleteRuleFieldsRejectedEvenWithRecomputedHash(String field){var root=content("1");rows(root,"riskRules").getFirst().remove(field);rejected(bytes(envelope(root,true)));}
    @Test void exactIntegersNeverRoundOrTruncate(){
        for(Object n:List.of(new BigDecimal("35.0000000000000001"),new BigInteger("9007199254740993"),"35")){
            var root=content("1");rows(root,"riskRules").getFirst().put("weight",n);rejected(bytes(envelope(root,true)));
        }
    }
    @Test void mapperConsumesConfiguredWeightsAndBands(){var root=content("1");rows(root,"riskRules").getFirst().put("weight",new BigInteger("37"));
        var mapped=new PublishedConfigurationMapping().map(decoder.decode(bytes(envelope(root,true)),null));assertEquals(37,mapped.rules().getFirst().weight());assertEquals(3,mapped.bands().size());}
    @Test void decimalContractStringsRemainExactAtMoneyAndMultiplierLimits(){
        var root=content("1");var tiers=rows(root,"approvalThresholds");tiers.get(0).put("maxAmountExclusive","9007199254740.91");
        tiers.get(1).put("minAmountInclusive","9007199254740.91");tiers.get(1).put("maxAmountExclusive","9999999999999.99");tiers.get(2).put("minAmountInclusive","9999999999999.99");
        @SuppressWarnings("unchecked") var parameters=(Map<String,Object>)rows(root,"riskRules").stream().filter(r->r.get("ruleCode").equals("UNUSUAL_AMOUNT")).findFirst().orElseThrow().get("parameters");
        parameters.put("meanMultiplier","1.0000000000000001");
        var mapped=new PublishedConfigurationMapping().map(decoder.decode(bytes(envelope(root,true)),null));
        assertEquals(new BigDecimal("9007199254740.91"),mapped.approvalBoundaries().get(2));assertEquals(new BigDecimal("9999999999999.99"),mapped.approvalBoundaries().get(3));
        var amount=(com.ofss.risk.contract.RuleParameters.Amount)mapped.rules().stream().filter(r->r.code()==com.ofss.risk.contract.RuleCode.UNUSUAL_AMOUNT).findFirst().orElseThrow().parameters();
        assertEquals(new BigDecimal("1.0000000000000001"),amount.meanMultiplier());
    }
    @ParameterizedTest @ValueSource(strings={"versionId","versionRowVersion","publishedAt","publishedByUserId","pointerRowVersion","activatedAt","activatedByUserId","configuration","contentHash"})
    void missingEnvelopeFieldsRejected(String field){var body=envelope(content("1"),true);body.remove(field);rejected(bytes(body));}
    @Test void invalidIdsRevisionsMetadataAndMismatchedIdentityRejected(){
        for(String field:List.of("versionId","versionRowVersion","publishedByUserId","pointerRowVersion","activatedByUserId")){
            for(Object bad:List.of("-1","9223372036854775808","01",1)){
                var body=envelope(content("1"),true);body.put(field,bad);rejected(bytes(body));
            }
        }
        var body=envelope(content("1"),true);body.put("versionRowVersion","0");rejected(bytes(body));
        body=envelope(content("1"),true);body.put("versionId","2");rejected(bytes(body));
        body=envelope(content("1"),true);body.put("publishedAt","2026-10-14T00:00:00+00:00");rejected(bytes(body));
        body=envelope(content("1"),true);body.put("activatedAt","2026-10-13T00:00:00Z");rejected(bytes(body));
    }
    @Test void malformedUtf8DuplicateFieldsTrailingDataAndUnknownFieldsRejected(){
        rejected(new byte[]{(byte)0xc3,(byte)0x28});rejected("[]".getBytes(StandardCharsets.UTF_8));rejected("null".getBytes(StandardCharsets.UTF_8));
        String valid=new String(response(true),StandardCharsets.UTF_8);rejected((valid+" {}").getBytes(StandardCharsets.UTF_8));
        rejected(valid.replaceFirst("\\{","{\"versionId\":\"1\",").getBytes(StandardCharsets.UTF_8));
        var body=envelope(content("1"),true);body.put("rawEvidence",Map.of());rejected(bytes(body));
    }
    @Test void duplicateChildIdentityAndIncompleteSlaRejected(){
        var root=content("1");rows(root,"riskRules").get(1).put("ruleConfigId",rows(root,"riskRules").getFirst().get("ruleConfigId"));rejected(bytes(envelope(root,true)));
        root=content("1");rows(root,"approvalSlas").getFirst().remove("durationSeconds");rejected(bytes(envelope(root,true)));
    }
    @Test void deeplyNestedOrExponentExpansionCannotExhaustCanonicalization(){
        var root=content("1");var body=envelope(root,true);var parameters=rows(root,"riskRules").getFirst();Object nested=Map.of();
        for(int i=0;i<70;i++)nested=List.of(nested);parameters.put("parameters",Map.of("nested",nested));rejected(bytes(body));
        String valid=new String(response(true),StandardCharsets.UTF_8);rejected(valid.replace("\"weight\":35","\"weight\":1e2147483647").getBytes(StandardCharsets.UTF_8));
    }
}
