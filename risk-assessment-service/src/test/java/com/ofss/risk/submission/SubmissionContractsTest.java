package com.ofss.risk.submission;

import com.ofss.risk.persistence.SubmissionFixtures;
import com.ofss.risk.contract.RuleParameters;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class SubmissionContractsTest {
    static final String JSON="{\"contractVersion\":\"risk-submission-v1\",\"submissionSource\":\"payment.v1\",\"submissionKey\":\"opaque-1\",\"paymentReference\":\"9007199254740993\",\"validationReference\":\"verified-pass\"}";
    @Test void referenceOnlyContractPreservesLongIdsAndOptionalChoices(){var c=SubmissionCommand.decode(JSON);assertEquals("9007199254740993",c.paymentReference());assertNull(c.configurationVersionId());assertNull(c.validationRevision());}
    @ParameterizedTest @ValueSource(strings={"{}","[]","null","{\"x\":1}","true","","{\"contractVersion\":\"risk-submission-v1\",\"contractVersion\":\"risk-submission-v1\"}"})
    void malformedContractIsRejected(String body){assertEquals(SubmissionFailure.Code.INVALID_SUBMISSION,assertThrows(SubmissionFailure.class,()->SubmissionCommand.decode(body)).code());}
    @ParameterizedTest @ValueSource(strings={"0","-1","1.0","9223372036854775808","01","1e3"})
    void strictIdsRejectOverflowAndCoercion(String id){assertThrows(SubmissionFailure.class,()->SubmissionCommand.decode(JSON.replace("9007199254740993",id)));}
    @Test void numericReferenceAndExtraScoreCannotBeSmuggled(){assertThrows(SubmissionFailure.class,()->SubmissionCommand.decode(JSON.replace("\"9007199254740993\"","9007199254740993")));
        assertThrows(SubmissionFailure.class,()->SubmissionCommand.decode(JSON.substring(0,JSON.length()-1)+",\"score\":0}"));assertThrows(SubmissionFailure.class,()->SubmissionCommand.decode(JSON+"{}"));}
    @ParameterizedTest @ValueSource(strings={"{}","{\"service\":[]}","{\"service\":[\"x\",\"x\"]}","{\"first\":[\"x\"],\"second\":[\"x\"]}","{\"service\":[\"x\"],\"service\":[\"y\"]}","{\"service\":[1]}"})
    void namespaceOwnershipFailsClosed(String json){var ex=assertThrows(IllegalStateException.class,()->new SubmissionSources(json));assertNull(ex.getCause());}
    @Test void namespaceOwnerOnly(){var sources=new SubmissionSources("{\"payment-service\":[\"payment.v1\"]}");sources.require("payment-service","payment.v1");assertThrows(SubmissionFailure.class,()->sources.require("approval-service","payment.v1"));assertThrows(SubmissionFailure.class,()->sources.require(null,"unknown"));}
    @Test void publishedMappingPreservesExactParametersBandsAndBoundaries()throws Exception{var p=SubmissionFixtures.published();var c=new PublishedConfigurationMapping().verified(p);
        assertEquals(7,c.rules().size());assertEquals(100,c.maxFinalScore());assertEquals(p.contentHash(),c.contentHash());assertEquals("123.00",c.approvalBoundaries().get(2).toPlainString());
        assertEquals(new java.math.BigDecimal("1.0000000000000001"),((RuleParameters.Amount)c.rules().get(2).parameters()).meanMultiplier());}
    @ParameterizedTest @ValueSource(strings={"state","hash","version","format","json"})
    void corruptedOrDraftPublishedSnapshotFailsClosed(String field){var p=SubmissionFixtures.published();var wrong=new SubmissionAdapters.PublishedSnapshot(field.equals("version")?"2":p.versionId(),p.rowVersion(),field.equals("state")?"DRAFT":p.publicationState(),field.equals("format")?"v2":p.hashFormat(),field.equals("hash")?"0".repeat(64):p.contentHash(),field.equals("json")?p.canonicalConfiguration()+" ":p.canonicalConfiguration());
        assertEquals(SubmissionFailure.Code.DEPENDENCY_UNAVAILABLE,assertThrows(SubmissionFailure.class,()->new PublishedConfigurationMapping().map(wrong)).code());}
    static SubmissionAdapters.PublishedSnapshot changed(String body)throws Exception{var p=SubmissionFixtures.published();String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));return new SubmissionAdapters.PublishedSnapshot(p.versionId(),p.rowVersion(),p.publicationState(),p.hashFormat(),hash,body);}
    @Test void verifiedMapperConsumesConfiguredValuesRatherThanDemoConstants()throws Exception{
        String body=SubmissionFixtures.published().canonicalConfiguration().replace("\"weight\":35","\"weight\":37").replace("1.0000000000000001","2.25").replace("\"maxFinalScore\":100","\"maxFinalScore\":99");
        var config=new PublishedConfigurationMapping().map(changed(body));assertEquals(37,config.rules().get(0).weight());assertEquals(99,config.maxFinalScore());assertEquals(new java.math.BigDecimal("2.25"),((RuleParameters.Amount)config.rules().get(2).parameters()).meanMultiplier());
    }
    @Test void sumMapsToExactPositiveAggregateWithoutInventingCap()throws Exception{
        String body=SubmissionFixtures.published().canonicalConfiguration().replace("\"scorePolicyCode\":\"CAP_AT_MAX\"","\"scorePolicyCode\":\"SUM\"").replace("\"maxFinalScore\":100","\"maxFinalScore\":null").replace("\"maxScoreInclusive\":100","\"maxScoreInclusive\":null");
        assertEquals(135,new PublishedConfigurationMapping().map(changed(body)).maxFinalScore());
        assertThrows(SubmissionFailure.class,()->new PublishedConfigurationMapping().map(changed(body.replace("\"enabled\":true","\"enabled\":false"))));
    }
    @Test void validHashCannotMakeMissingSectionsOrGappedThresholdsValid()throws Exception{
        var body=SubmissionFixtures.published().canonicalConfiguration();assertThrows(SubmissionFailure.class,()->new PublishedConfigurationMapping().map(changed(body.replace("\"minAmountInclusive\":\"123.00\"","\"minAmountInclusive\":\"123.01\""))));
        assertThrows(SubmissionFailure.class,()->new PublishedConfigurationMapping().map(changed(body.replace("\"approvalSlas\"","\"unrecognizedSection\""))));
    }
    @Test void demoFactsAndEvidenceAreDeterministicAndCannotSupplyConfiguration(){
        var r=SubmissionFixtures.request();var c=new SubmissionCommand(SubmissionCommand.VERSION,"demo.payment","x","1","DEMO-PASS-x",null,null);
        var d=new SubmissionAdapters.Deadline(System.nanoTime()+1000000000);var a=DemoSubmissionAdapters.facts(c,r.cutoff(),d);
        assertEquals(a,DemoSubmissionAdapters.facts(c,r.cutoff(),d));var accepted=a.acceptance(r.cutoff(),r.configuration());
        assertEquals(DemoSubmissionAdapters.evidence(accepted,d),DemoSubmissionAdapters.evidence(accepted,d));
        assertThrows(SubmissionFailure.class,()->DemoSubmissionAdapters.facts(SubmissionFixtures.command(),r.cutoff(),d));
    }
    @ParameterizedTest @ValueSource(strings={"PT0S","PT-1S","PT31S"})
    void dependencyBudgetMustBeExplicitAndBounded(String duration){assertThrows(IllegalArgumentException.class,()->new DependencyCalls(Duration.parse(duration)));}
    @Test void timedOutDependencyCannotHoldCallerOrExecutePersistence()throws Exception{
        var interrupted=new CountDownLatch(1);try(var calls=new DependencyCalls(Duration.ofMillis(50))){
            assertEquals(SubmissionFailure.Code.DEPENDENCY_UNAVAILABLE,assertThrows(SubmissionFailure.class,()->calls.call(calls.deadline(),()->{
                try{new CountDownLatch(1).await();}catch(InterruptedException e){interrupted.countDown();Thread.currentThread().interrupt();}return "late";
            })).code());assertTrue(interrupted.await(2,TimeUnit.SECONDS));
        }
    }
    @Test void closeWaitsForCancelledDependencyWorkerAndReportsTermination()throws Exception{
        var calls=new DependencyCalls(Duration.ofMillis(50));var interrupted=new CountDownLatch(1);var release=new CountDownLatch(1);
        var closers=Executors.newSingleThreadExecutor();
        try {
            assertThrows(SubmissionFailure.class,()->calls.call(calls.deadline(),()->{
                try{new CountDownLatch(1).await();}catch(InterruptedException e){interrupted.countDown();}
                boolean done=false;while(!done){try{release.await();done=true;}catch(InterruptedException ignored){}}
                return "finished";
            }));assertTrue(interrupted.await(2,TimeUnit.SECONDS));
            var close=closers.submit(calls::close);assertFalse(calls.terminated());release.countDown();close.get(2,TimeUnit.SECONDS);assertTrue(calls.terminated());
        }finally{release.countDown();closers.shutdownNow();assertTrue(closers.awaitTermination(2,TimeUnit.SECONDS));calls.close();}
    }
}
