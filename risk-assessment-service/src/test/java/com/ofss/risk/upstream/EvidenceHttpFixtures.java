package com.ofss.risk.upstream;

import com.ofss.risk.engine.RiskInput.*;
import com.ofss.risk.facts.FactsProviderProtocol;
import com.ofss.risk.persistence.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Synthetic contract provider only; no production endpoint or seed data. */
final class EvidenceHttpFixtures {
    static final JsonMapper JSON=JsonMapper.builder().build();static final String PROVIDER="upstream-provider";
    static AcceptanceRequest accepted(){
        var original=SubmissionFixtures.request();var cutoff=original.cutoff();var identity=new SubmissionIdentity("developer1.payment.v1","current-ಕನ್ನಡ😀");
        var current=new Payment("facts-v1:"+enc("facts-provider")+":"+enc("immutable/facts-Ω")+":"+enc("revision-7"),FactsProviderProtocol.alias(identity),"123","INV-ಕನ್ನಡ😀","456","INR",new BigDecimal("40.00"),cutoff.minusSeconds(1),null,PaymentStatus.PENDING_APPROVAL,true);
        return new AcceptanceRequest(identity,original.paymentId(),original.requesterId(),"validation-42","3",current,cutoff,
            Set.of(current.submissionId(),FactsProviderProtocol.alias(new SubmissionIdentity("legacy.payment.v1","retry-42"))),original.evaluatorVersion(),original.configuration(),null);
    }
    static String enc(String v){return Base64.getUrlEncoder().withoutPadding().encodeToString(v.getBytes(StandardCharsets.UTF_8));}
    static EvidenceProviderProtocol protocol(){return new EvidenceProviderProtocol(accepted(),PROVIDER,2);}
    static Map<String,Object> request(int index){return JSON.readValue(protocol().request(index,index==0?null:"opaque-page-1"),Map.class);}
    static Map<String,Object> response(Map<String,Object> request,int index){
        var first=rawResponse(request,0);var second=rawResponse(request,1);var hash=populationHash(List.of(first,second));var result=index==0?first:second;result.put("populationHash",hash);return result;
    }
    static String populationHash(List<Map<String,Object>> pages){var population=new TreeMap<String,Object>();
        for(String name:List.of("paymentHistory","supplier","supplierTransitions")){var section=new TreeMap<String,Object>(object(pages.getFirst(),name));var records=new ArrayList<Object>();for(var page:pages)records.addAll(rows(page,name));section.put("records",records);population.put(name,section);}
        return EvidenceProviderProtocol.digest(population);
    }
    static void rehash(List<Map<String,Object>> pages){var hash=populationHash(pages);pages.forEach(p->p.put("populationHash",hash));}
    @SuppressWarnings("unchecked") static Map<String,Object> rawResponse(Map<String,Object> request,int index){
        var p=protocol();var cutoff=accepted().cutoff();var replay=(String)request.get("replayReference");
        var ph=index==0?List.of(payment("older",p.paymentFrom,"APPROVED",null,"9999999999999.99"),payment("latest",cutoff.minusNanos(1),"PENDING_APPROVAL",null,"40.00"))
            :List.of(payment("baseline",p.paymentFrom.minusSeconds(100),"COMPLETE",p.paymentFrom,"0.00"));
        var transitions=List.of(map("recordReference","transition-"+index,"supplierId","456","previousStatus",index==0?"ACTIVE":"INACTIVE","nextStatus",index==0?"INACTIVE":"ACTIVE", "changedAt",offset(index==0?p.transitionFrom:cutoff)));
        return map("contractVersion",EvidenceProviderProtocol.VERSION,"request",request.get("request"),"providerServiceId",PROVIDER,"replayReference",replay,"snapshotRevision","1",
            "certification",map("immutable",true,"asOfCutoff",true,"supplierWideAcrossRequesters",true,"exclusionsApplied",true,"factsReceiptBindingVerified",true),
            "pagination",map("pageIndex",index,"pageSize",2,"totalPages",2,"totalPayments",3,"totalTransitions",2,"nextCursor",index==0?"opaque-page-1":null),
            "paymentHistory",section(replay,"paymentHistory",p.paymentFrom,ph),
            "supplier",section(replay,"supplier",cutoff,index==0?List.of(map("recordReference","supplier-ಕನ್ನಡ😀","supplierId","456","createdAt",offset(p.paymentFrom.minusSeconds(1000)),"statusAsOfCutoff","ACTIVE")):List.of()),
            "supplierTransitions",section(replay,"supplierTransitions",p.transitionFrom,transitions));
    }
    static Map<String,Object> payment(String key,Instant submitted,String status,Instant completed,String amount){return map("recordReference","payment-"+key,"submission",map("source","developer1.payment.v1","key",key),"paymentId","22","requesterId",key.equals("older")?"77":"88",
        "invoiceId","124","invoiceNumber","INV-Ω-ಕನ್ನಡ😀-"+key,"supplierId","456","currencyCode","INR","amount",amount,"submittedAt",offset(submitted),"completedAt",completed==null?null:offset(completed),"statusAsOfCutoff",status,"validated",true);}
    static Map<String,Object> section(String replay,String name,Instant from,List<?> records){return map("availability","COMPLETE","sourceReference",replay+"-"+name,"sourceRevision","1","coveredFrom",offset(from),"records",records);}
    static String offset(Instant at){return at.atOffset(ZoneOffset.ofHoursMinutes(-3,-30)).toString();}
    static Map<String,Object> map(Object...values){var map=new TreeMap<String,Object>();for(int i=0;i<values.length;i+=2)map.put((String)values[i],values[i+1]);return map;}
    @SuppressWarnings("unchecked") static Map<String,Object> object(Map<?,?> value,String key){return (Map<String,Object>)value.get(key);}
    @SuppressWarnings("unchecked") static List<Map<String,Object>> rows(Map<?,?> value,String section){return (List<Map<String,Object>>)object(value,section).get("records");}
    static byte[] bytes(Object value){return JSON.writeValueAsBytes(value);}
}
