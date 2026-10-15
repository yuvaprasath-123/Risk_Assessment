package com.ofss.risk.persistence;

import com.ofss.risk.engine.*;
import com.ofss.risk.submission.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static com.ofss.risk.engine.RiskInput.*;
import static com.ofss.risk.submission.SubmissionAdapters.*;

/** Explicit contract-test data, not a genuine upstream integration or readiness-approved publication. */
final class OracleSubmissionFixtures {
    static final String PREFIX="d2-risk-submit-";
    static final Set<String> KEYS=Set.of("success","retries","conflict","dependency","blocked","concurrent","frozen-recovery","unavailable");
    static final Instant SUBMITTED=Instant.parse("2020-01-01T00:00:00.123456789Z");
    final String namespace;final AtomicInteger factCalls=new AtomicInteger(),configurationCalls=new AtomicInteger(),evidenceCalls=new AtomicInteger(),finalizationCalls=new AtomicInteger();
    final Set<String> failedEvidence=ConcurrentHashMap.newKeySet(),failedFinalization=ConcurrentHashMap.newKeySet();
    final AtomicBoolean forbidDependencies=new AtomicBoolean();final CyclicBarrier acceptanceBarrier=new CyclicBarrier(2);
    final Set<String> competingThreads=ConcurrentHashMap.newKeySet();
    OracleSubmissionFixtures(){this(PREFIX+UUID.randomUUID());}
    OracleSubmissionFixtures(String namespace){requireNamespace(namespace);this.namespace=namespace;}
    static void requireNamespace(String namespace){if(namespace==null || !namespace.matches("d2-risk-submit-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw new IllegalArgumentException("Exact canonical submission fixture namespace required");}
    SubmissionCommand command(String key){requireKey(key);return new SubmissionCommand(SubmissionCommand.VERSION,namespace,key,"17","contract-pass/"+key,"contract-v1",null);}
    VerifiedFacts facts(SubmissionCommand command,Instant cutoff,Deadline deadline){
        if(forbidDependencies.get())throw new AssertionError("Completed retry accessed a dependency");factCalls.incrementAndGet();
        requireKey(command.submissionKey());if(!namespace.equals(command.submissionSource()) || !command.equals(command(command.submissionKey())))throw SubmissionAdapters.unavailable();
        return verified(command.submissionKey());
    }
    VerifiedFacts verified(String key){requireKey(key);String id=namespace+"/"+key;
        var current=new Payment("contract-current-ಕನ್ನಡ😀",id,"123","CONTRACT-CURRENT-ಕನ್ನಡ😀","456","INR",new BigDecimal("40.01"),SUBMITTED,null,PaymentStatus.PENDING_APPROVAL,true);
        return new VerifiedFacts(new SubmissionIdentity(namespace,key),17,42,"contract-pass/"+key,"contract-v1",current,Set.of(id,id+"/retry"));
    }
    PublishedSnapshot resolve(String version,Deadline deadline){
        if(forbidDependencies.get())throw new AssertionError("Completed retry resolved configuration");configurationCalls.incrementAndGet();var p=published();
        if(version!=null && !version.equals(p.versionId()))throw SubmissionAdapters.unavailable();return p;
    }
    PublishedSnapshot published(){try{
        var p=SubmissionFixtures.published();String body=p.canonicalConfiguration().replace("CONTROLLED TEST FIXTURE","CONTRACT TEST "+namespace);
        String hash=CanonicalAcceptance.hash(body);return new PublishedSnapshot(p.versionId(),p.rowVersion(),p.publicationState(),p.hashFormat(),hash,body);
    }catch(RuntimeException e){throw new IllegalStateException("Invalid contract-test configuration");}}
    AcceptanceRequest expected(String key,Instant cutoff){return verified(key).acceptance(cutoff,new PublishedConfigurationMapping().map(published()));}
    Bundle retrieve(AcceptanceRequest accepted,Deadline deadline){
        if(forbidDependencies.get())throw new AssertionError("Frozen/completed retry fetched evidence");evidenceCalls.incrementAndGet();String key=accepted.identity().key();
        if(key.equals("blocked"))throw new SubmissionFailure(SubmissionFailure.Code.CUTOFF_NOT_REPRODUCIBLE);
        if(key.equals("dependency") && failedEvidence.add(key))throw new SubmissionFailure(SubmissionFailure.Code.DEPENDENCY_UNAVAILABLE);
        return bundle(accepted);
    }
    Bundle bundle(AcceptanceRequest accepted){
        String key=accepted.identity().key();requireKey(key);var cutoff=accepted.cutoff();String payload="ಕನ್ನಡ😀"+"x".repeat(512);var history=new ArrayList<Payment>();
        for(int i=0;i<9;i++)history.add(new Payment("contract-payment-"+i+payload,namespace+"/past/"+key+"/"+i,"past-"+i,"DIFFERENT-"+i,"456","INR",
            new BigDecimal(i<4?"40.01":"10.01"),cutoff.minusSeconds(7200+i).minusNanos(101),i<4?null:cutoff.minusSeconds(3600+i).minusNanos(102),i<4?PaymentStatus.APPROVED:PaymentStatus.COMPLETE,true));
        var input=new RiskInput(accepted.current(),cutoff,accepted.currentAndRetrySubmissionIds(),envelope(key,"payments",history,cutoff.minusSeconds(7776000),cutoff),
            envelope(key,"supplier",List.of(new Supplier("contract-supplier-"+payload,"456",cutoff.minusSeconds(100000),SupplierStatus.ACTIVE)),cutoff,cutoff),
            envelope(key,"transitions",List.of(new StatusTransition("contract-transition-"+payload,"456",SupplierStatus.INACTIVE,SupplierStatus.ACTIVE,cutoff.minusSeconds(100))),cutoff.minusSeconds(2592000),cutoff),
            envelope(key,"prior",List.of(new PriorAssessment("contract-prior-"+payload,namespace+"/prior/"+key,"456",cutoff.minusSeconds(200),Set.of(com.ofss.risk.contract.RuleCode.NEW_SUPPLIER))),cutoff.minusSeconds(7776000),cutoff));
        var sources=new EnumMap<EvidenceFreezeRequest.Kind,EvidenceFreezeRequest.Source>(EvidenceFreezeRequest.Kind.class);
        for(var kind:EvidenceFreezeRequest.Kind.values())if(kind!=EvidenceFreezeRequest.Kind.CURRENT_INPUT)sources.put(kind,new EvidenceFreezeRequest.Source("submission-contract-test",namespace+"/"+key,"contract-v1"));
        return new Bundle(input,sources);
    }
    private static <T> RiskInput.Evidence<T> envelope(String key,String section,List<T> records,Instant from,Instant cutoff){return new RiskInput.Evidence<>(Availability.COMPLETE,"contract/"+key+"/"+section,"456","INR",cutoff,from,records);}
    void acceptanceRace(AcceptanceRequest request){if(request.identity().key().equals("concurrent")&&competingThreads.add(Thread.currentThread().getName()))try{acceptanceBarrier.await(10,TimeUnit.SECONDS);}catch(Exception e){throw new IllegalStateException("Contract acceptance barrier failed");}}
    private static void requireKey(String key){if(!KEYS.contains(key))throw new IllegalArgumentException("Unknown contract fixture key");}
}
