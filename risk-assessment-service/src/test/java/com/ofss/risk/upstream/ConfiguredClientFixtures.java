package com.ofss.risk.upstream;

import com.ofss.risk.facts.FactsProviderProtocol;
import com.ofss.risk.persistence.SubmissionIdentity;
import com.ofss.risk.submission.SubmissionCommand;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import tools.jackson.databind.json.JsonMapper;

/** Explicit synthetic provider protocol fixtures, not Developer 1 APIs. No database access. */
public final class ConfiguredClientFixtures {
    public static final JsonMapper JSON=JsonMapper.builder().build();
    public static final Set<String> KEYS=Set.of("seed","history","retry","conflict","incomplete","dependency","pinned","after-activation","explicit-pinned","concurrent");
    public static final String FACTS="configured-facts-provider",EVIDENCE="configured-evidence-provider";
    public static final Instant SUBMITTED=Instant.parse("2020-01-01T00:00:00.123456789Z");
    public final String namespace,supplier;
    private final AtomicReference<Instant> historyAnchor=new AtomicReference<>();
    public ConfiguredClientFixtures(String namespace){requireNamespace(namespace);this.namespace=namespace;this.supplier=Long.toString(800000000000000000L+Math.floorMod(UUID.fromString(namespace.substring(11)).getMostSignificantBits(),100000000000000000L));}
    public static void requireNamespace(String value){if(value==null||!value.matches("d2-clients-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw new IllegalArgumentException("Exact configured-client fixture namespace required");}
    public SubmissionCommand command(String key){if(!KEYS.contains(key))throw new IllegalArgumentException("Unknown configured-client key");return new SubmissionCommand(SubmissionCommand.VERSION,namespace,key,Long.toString(1000+KEYS.stream().sorted().toList().indexOf(key)),"configured-pass/"+key,"configured-v1",null);}
    public Map<String,Object> facts(SubmissionCommand command,Instant cutoff){
        if(!command.equals(command(command.submissionKey())))throw new IllegalArgumentException("Unknown authoritative fixture request");
        historyAnchor.compareAndSet(null,cutoff); // One immutable supplier population for the entire fixture, across all requesters/submissions.
        String invoiceId=Long.toString(Long.parseLong(command.paymentReference())+1000);
        return map("contractVersion",FactsProviderProtocol.VERSION,"submission",pair(command.identity()),"paymentId",command.paymentReference(),"requesterId","42",
            "validation",map("reference",command.validationReference(),"revision",command.validationRevision(),"outcome","PASS","validatedAt",SUBMITTED.plusSeconds(1).toString(),"immutableReference",false),"cutoff",cutoff.toString(),
            "payment",map("invoiceId",invoiceId,"supplierId",supplier,"amount","40.01","currencyCode","INR","submittedAt",SUBMITTED.toString(),"completedAt",null,"statusAsOfCutoff","PENDING_APPROVAL"),
            "invoice",map("invoiceId",invoiceId,"supplierId",supplier,"invoiceNumber","INV-ಕನ್ನಡ😀-"+command.submissionKey(),"invoiceAmount","40.01","currencyCode","INR"),
            "supplier",map("supplierId",supplier,"statusAsOfCutoff","ACTIVE","createdAt","2019-01-01T00:00:00Z"),
            "currentAndRetryIdentities",List.of(pair(command.identity()),pair(new SubmissionIdentity(namespace,"retry-of-"+command.submissionKey()))),
            "certification",map("code","AUTHORITATIVE_VALIDATION_PASS_V1","complete",true,"asOfCutoff",true,"immutable",true,"validationPaymentBindingVerified",true,"requesterOwnershipVerified",true,"eligibleForRiskAssessment",true,"retryIdentityScope","SAME_LOGICAL_SUBMISSION"),
            "provenance",map("serviceId",FACTS,"recordReference",namespace+"/facts/"+command.submissionKey(),"recordRevision","1"));
    }
    @SuppressWarnings("unchecked") public List<Map<String,Object>> evidencePages(Map<String,Object> wire,boolean incomplete){
        var request=object(wire,"request");var key=(String)object(request,"submission").get("key");command(key);
        if(!namespace.equals(object(request,"submission").get("source"))||!supplier.equals(request.get("supplierId")))throw new IllegalArgumentException("Unexpected evidence fixture scope");
        var cutoff=Instant.parse((String)request.get("cutoff"));var anchor=Objects.requireNonNull(historyAnchor.get(),"Original fixture source population unavailable");var coverage=object(request,"requiredCoverage");String replay=(String)wire.get("replayReference");
        var payments=new ArrayList<Object>();for(int i=0;i<6;i++)payments.add(map("recordReference",namespace+"/history/"+i,"submission",pair(new SubmissionIdentity(namespace,"historical-"+i)),"paymentId",Long.toString(100+i),"requesterId",i%2==0?"71":"72",
            "invoiceId",Long.toString(200+i),"invoiceNumber","OTHER-Ω-"+i,"supplierId",supplier,"currencyCode","INR","amount",i<2?"40.01":"10.01",
            "submittedAt",anchor.minusSeconds(7200+i).minusNanos(101).toString(),"completedAt",i<2?null:anchor.minusSeconds(3600+i).minusNanos(102).toString(),"statusAsOfCutoff",i<2?"APPROVED":"COMPLETE","validated",true));
        int pageSize=((Number)wire.get("pageSize")).intValue();int totalPages=(payments.size()+pageSize-1)/pageSize;var pages=new ArrayList<Map<String,Object>>();
        for(int index=0;index<totalPages;index++)pages.add(map("contractVersion",EvidenceProviderProtocol.VERSION,"request",request,"providerServiceId",EVIDENCE,"replayReference",replay,"snapshotRevision","1",
            "certification",map("immutable",true,"asOfCutoff",true,"supplierWideAcrossRequesters",true,"exclusionsApplied",true,"factsReceiptBindingVerified",true),
            "pagination",map("pageIndex",index,"pageSize",pageSize,"totalPages",totalPages,"totalPayments",payments.size(),"totalTransitions",1,"nextCursor",index+1<totalPages?"page-"+(index+1):null),
            "paymentHistory",section(replay,"paymentHistory",incomplete?"INCOMPLETE":"COMPLETE",coverage.get("paymentFrom"),payments.subList(index*pageSize,Math.min(payments.size(),(index+1)*pageSize))),
            "supplier",section(replay,"supplier","COMPLETE",cutoff.toString(),index==0?List.of(map("recordReference",namespace+"/supplier","supplierId",supplier,"createdAt","2019-01-01T00:00:00Z","statusAsOfCutoff","ACTIVE")):List.of()),
            "supplierTransitions",section(replay,"supplierTransitions","COMPLETE",coverage.get("transitionFrom"),index==0?List.of(map("recordReference",namespace+"/reactivation","supplierId",supplier,"previousStatus","INACTIVE","nextStatus","ACTIVE","changedAt",anchor.minusSeconds(100).toString())):List.of())));
        var population=new TreeMap<String,Object>();for(String name:List.of("paymentHistory","supplier","supplierTransitions")){var section=new TreeMap<String,Object>(object(pages.getFirst(),name));var rows=new ArrayList<Object>();for(var page:pages)rows.addAll((List<?>)object(page,name).get("records"));section.put("records",rows);population.put(name,section);}
        var digest=EvidenceProviderProtocol.digest(population);pages.forEach(page->page.put("populationHash",digest));return pages;
    }
    static Map<String,Object> section(String replay,String name,String availability,Object from,List<?> rows){return map("availability",availability,"sourceReference",replay+"-"+name,"sourceRevision","1","coveredFrom",from,"records",rows);}
    public static Map<String,Object> pair(SubmissionIdentity identity){return map("source",identity.source(),"key",identity.key());}
    public static Map<String,Object> map(Object...pairs){var result=new TreeMap<String,Object>();for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);return result;}
    @SuppressWarnings("unchecked") public static Map<String,Object> object(Map<?,?> parent,String key){return (Map<String,Object>)parent.get(key);}
    public static byte[] bytes(Object value){return JSON.writeValueAsBytes(value);}
}
