package com.ofss.risk.upstream;

import com.ofss.risk.engine.RiskInput;
import com.ofss.risk.engine.RiskInput.*;
import com.ofss.risk.facts.FactsProviderProtocol;
import com.ofss.risk.persistence.*;
import com.ofss.risk.submission.*;
import com.ofss.risk.submission.SubmissionAdapters.UpstreamBundle;
import java.math.*;
import java.nio.*;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import tools.jackson.core.*;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** D2 integration protocol, not a pre-existing Developer 1 API. No upstream prior-risk section exists. */
public final class EvidenceProviderProtocol {
    public static final String VERSION="risk-upstream-evidence-v1",PATH="/internal/v1/risk-evidence/resolve",PERMISSION="RISK_EVIDENCE_READ";
    private static final JsonMapper JSON=JsonMapper.builder(JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder()
        .maxNestingDepth(20).maxNumberLength(64).maxStringLength(16384).build()).build())
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,DeserializationFeature.USE_BIG_INTEGER_FOR_INTS).build();
    final AcceptanceRequest accepted;final Map<String,Object> base;final Set<SubmissionIdentity> exclusions;
    final String replay;final String provider;final Instant paymentFrom,transitionFrom;final int pageSize;
    public EvidenceProviderProtocol(AcceptanceRequest accepted,String provider,int pageSize){
        try {
            this.accepted=Objects.requireNonNull(accepted);this.provider=provider;this.pageSize=pageSize;
            Objects.requireNonNull(accepted.configuration());
            if(!FactsProviderProtocol.alias(accepted.identity()).equals(accepted.current().submissionId()))throw invalid();
            var pairs=new HashSet<SubmissionIdentity>();for(var alias:accepted.currentAndRetrySubmissionIds())pairs.add(identityAlias(alias));
            exclusions=Set.copyOf(pairs);if(!exclusions.contains(accepted.identity()))throw invalid();
            Instant payments=null,transitions=null;
            for(var rule:accepted.configuration().rules())if(rule.enabled()){
                var start=accepted.cutoff().minusSeconds(rule.parameters().windowSeconds());timestamp(start);
                switch(rule.code()){
                    case STRUCTURING,UNUSUAL_FREQUENCY,UNUSUAL_AMOUNT,NEAR_DUPLICATE_INVOICE -> payments=earlier(payments,start);
                    case RECENTLY_REACTIVATED_SUPPLIER -> transitions=earlier(transitions,start);
                    default -> { }
                }
            }
            paymentFrom=payments;transitionFrom=transitions;
            var canonical=new CanonicalAcceptance();var config=canonical.configuration(accepted.configuration());
            base=map("contractVersion",VERSION,"submission",pair(accepted.identity()),"paymentId",Long.toString(accepted.paymentId()),
                "requesterId",Long.toString(accepted.requesterId()),"validationReference",accepted.validationReference(),"validationRevision",accepted.validationRevision(),
                "factsReceipt",factsReceipt(accepted.current().evidenceId()),"supplierId",accepted.current().supplierId(),"currencyCode","INR","cutoff",accepted.cutoff().toString(),
                "configuration",map("versionId",accepted.configuration().versionId(),"rowVersion",accepted.configuration().rowVersion(),"configurationHash",config.hash()),
                "acceptanceHash",canonical.acceptance(accepted,accepted.configuration().versionId(),accepted.configuration().rowVersion(),config.hash()).hash(),
                "requiredCoverage",map("paymentFrom",payments==null?null:payments.toString(),"transitionFrom",transitions==null?null:transitions.toString(),
                    "supplierSnapshotAt",accepted.cutoff().toString(),"paymentPopulation","SUBMITTED_OR_COMPLETED_V1","transitionEnd","INCLUSIVE"),
                "excludedIdentities",exclusions.stream().sorted(Comparator.comparing(SubmissionIdentity::source).thenComparing(SubmissionIdentity::key)).map(EvidenceProviderProtocol::pair).toList());
            replay="ue1-"+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(base)));
        }catch(Exception exception){throw failure();}
    }
    public byte[] request(int pageIndex,String cursor){return JSON.writeValueAsBytes(map("request",base,"replayReference",replay,"pageIndex",pageIndex,"pageSize",pageSize,"cursor",cursor));}
    public Collector collector(){return new Collector();}
    public final class Collector {
        int pages=-1,paymentsTotal,transitionsTotal,index;String nextCursor,populationHash;
        final List<Payment> payments=new ArrayList<>();final List<StatusTransition> transitions=new ArrayList<>();final List<Supplier> suppliers=new ArrayList<>();
        final Set<String> paymentIds=new HashSet<>(),paymentEvidence=new HashSet<>(),transitionIds=new HashSet<>(),cursors=new HashSet<>();
        final Map<String,Map<?,?>> metadata=new TreeMap<>();
        final Map<String,List<Object>> rawRecords=new TreeMap<>();
        boolean capacity;
        public void accept(byte[] bytes,int maxPages,int maxRecords){
            try {
                String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
                var root=JSON.readValue(text,Map.class);keys(root,"contractVersion","request","providerServiceId","replayReference","snapshotRevision","populationHash","certification","pagination","paymentHistory","supplier","supplierTransitions");
                equal(VERSION,root.get("contractVersion"));equal(base,root.get("request"));equal(provider,root.get("providerServiceId"));equal(replay,root.get("replayReference"));equal("1",root.get("snapshotRevision"));
                var digest=text(root.get("populationHash"),64);if(!digest.matches("[0-9a-f]{64}"))throw invalid();if(index==0)populationHash=digest;else equal(populationHash,digest);
                var cert=object(root.get("certification"));keys(cert,"immutable","asOfCutoff","supplierWideAcrossRequesters","exclusionsApplied","factsReceiptBindingVerified");
                for(var value:cert.values())equal(Boolean.TRUE,value);
                var pagination=object(root.get("pagination"));keys(pagination,"pageIndex","pageSize","totalPages","totalPayments","totalTransitions","nextCursor");
                equal(index,integer(pagination.get("pageIndex"),0,999999));equal(pageSize,integer(pagination.get("pageSize"),1,1000));
                int count=integer(pagination.get("totalPages"),1,1000000),p=integer(pagination.get("totalPayments"),0,Integer.MAX_VALUE),t=integer(pagination.get("totalTransitions"),0,Integer.MAX_VALUE);
                if(index==0){pages=count;paymentsTotal=p;transitionsTotal=t;}else if(pages!=count||paymentsTotal!=p||transitionsTotal!=t)throw invalid();
                if(index>=pages)throw invalid();
                var next=pagination.get("nextCursor")==null?null:text(pagination.get("nextCursor"),256);
                if((index+1<pages)!=(next!=null)||next!=null&&!cursors.add(next))throw invalid();
                var ph=section(root,"paymentHistory",paymentFrom);var ss=section(root,"supplier",accepted.cutoff());var st=section(root,"supplierTransitions",transitionFrom);
                if(ph.size()>pageSize||st.size()>pageSize||ss.size()>(index==0?1:0))throw invalid();
                if((long)p+t+1>maxRecords||pages>maxPages){capacity=true;nextCursor=null;index++;return;}
                for(var item:ph){var record=payment(object(item));if(!paymentIds.add(record.submissionId())||!paymentEvidence.add(record.evidenceId()))throw invalid();payments.add(record);}
                for(var item:st){var record=transition(object(item));if(!transitionIds.add(record.evidenceId()))throw invalid();transitions.add(record);}
                for(var item:ss)suppliers.add(supplier(object(item)));
                rawRecords.computeIfAbsent("paymentHistory",ignored->new ArrayList<>()).addAll(ph);
                rawRecords.computeIfAbsent("supplier",ignored->new ArrayList<>()).addAll(ss);
                rawRecords.computeIfAbsent("supplierTransitions",ignored->new ArrayList<>()).addAll(st);
                if((long)payments.size()+transitions.size()+suppliers.size()>maxRecords||payments.size()>paymentsTotal||transitions.size()>transitionsTotal)throw invalid();
                nextCursor=next;index++;
                if(next==null&&(payments.size()!=paymentsTotal||transitions.size()!=transitionsTotal||complete("supplier")&&suppliers.size()!=1))throw invalid();
            }catch(Exception exception){throw failure();}
        }
        private List<?> section(Map<?,?> root,String name,Instant required){
            var section=object(root.get(name));keys(section,"availability","sourceReference","sourceRevision","coveredFrom","records");
            Availability availability=Availability.valueOf(text(section.get("availability"),16));
            equal(source(name),section.get("sourceReference"));equal("1",section.get("sourceRevision"));
            var from=section.get("coveredFrom")==null?null:time(section.get("coveredFrom"));if(from!=null&&from.isAfter(accepted.cutoff()))throw invalid();
            if(availability==Availability.COMPLETE&&from==null)throw invalid();
            if(required==null&&availability==Availability.COMPLETE)throw invalid(); // Disabled dependencies may remain explicitly unavailable.
            if(!(section.get("records") instanceof List<?> records))throw invalid();
            if(availability==Availability.UNAVAILABLE&&!records.isEmpty())throw invalid();
            var info=new TreeMap<Object,Object>(section);info.remove("records");
            if(index==0)metadata.put(name,info);else equal(metadata.get(name),info);
            return records;
        }
        public boolean more(){return !capacity&&nextCursor!=null;}
        public boolean atCapacity(){return capacity;}
        public int nextIndex(){return index;}
        public String cursor(){return nextCursor;}
        public UpstreamBundle finish(boolean interrupted){
            if(index==0)throw failure();boolean partial=interrupted||capacity||index!=pages;
            if(!partial){verifyPopulationHash();verifySupplierTimeline();}
            return new UpstreamBundle(envelope("paymentHistory",partial,payments,paymentFrom),envelope("supplier",partial,suppliers,accepted.cutoff()),
                envelope("supplierTransitions",partial,transitions,transitionFrom),Map.of(EvidenceFreezeRequest.Kind.PAYMENT_HISTORY,new EvidenceFreezeRequest.Source(provider,source("paymentHistory"),"1"),
                    EvidenceFreezeRequest.Kind.SUPPLIER,new EvidenceFreezeRequest.Source(provider,source("supplier"),"1"),
                    EvidenceFreezeRequest.Kind.SUPPLIER_TRANSITIONS,new EvidenceFreezeRequest.Source(provider,source("supplierTransitions"),"1")),exclusions);
        }
        private void verifyPopulationHash(){
            var population=new TreeMap<String,Object>();
            for(var name:List.of("paymentHistory","supplier","supplierTransitions")){
                var section=new TreeMap<String,Object>();metadata.get(name).forEach((key,value)->section.put((String)key,value));section.put("records",rawRecords.get(name));population.put(name,section);
            }
            if(!Objects.equals(populationHash,digest(population)))throw failure();
        }
        private void verifySupplierTimeline(){
            if(!complete("supplier")||suppliers.size()!=1)return;
            var supplier=suppliers.getFirst();
            if(payments.stream().anyMatch(p->p.submittedAt().isBefore(supplier.createdAt()))||transitions.stream().anyMatch(t->t.changedAt().isBefore(supplier.createdAt())))throw failure();
            if(!complete("supplierTransitions")||transitions.isEmpty())return;
            var ordered=transitions.stream().sorted(Comparator.comparing(StatusTransition::changedAt)).toList();
            for(int i=1;i<ordered.size();i++)if(ordered.get(i-1).changedAt().equals(ordered.get(i).changedAt())||ordered.get(i-1).next()!=ordered.get(i).previous())throw failure();
            if(ordered.getLast().next()!=supplier.status())throw failure();
        }
        private boolean complete(String name){return "COMPLETE".equals(metadata.get(name).get("availability"));}
        private <T> Evidence<T> envelope(String name,boolean partial,List<T> records,Instant required){
            var info=metadata.get(name);var state=Availability.valueOf((String)info.get("availability"));var from=info.get("coveredFrom")==null?null:time(info.get("coveredFrom"));
            if(state==Availability.COMPLETE&&(partial||required!=null&&from.isAfter(required)))state=Availability.INCOMPLETE;
            // Never publish a misleading partial population: unavailable/incomplete retains its status; retry fetches the same immutable receipt.
            return new Evidence<>(state,source(name),accepted.current().supplierId(),"INR",accepted.cutoff(),from,partial?List.of():ordered(records));
        }
    }
    private String source(String name){return replay+"-"+name;}
    private Payment payment(Map<?,?> record){
        keys(record,"recordReference","submission","paymentId","requesterId","invoiceId","invoiceNumber","supplierId","currencyCode","amount","submittedAt","completedAt","statusAsOfCutoff","validated");
        var identity=identity(object(record.get("submission")));if(exclusions.contains(identity))throw invalid();id(record.get("paymentId"));id(record.get("requesterId"));
        equal(accepted.current().supplierId(),id(record.get("supplierId")));equal("INR",record.get("currencyCode"));
        var submitted=time(record.get("submittedAt"));var completed=record.get("completedAt")==null?null:time(record.get("completedAt"));
        var status=PaymentStatus.valueOf(text(record.get("statusAsOfCutoff"),32));
        if(!(record.get("validated") instanceof Boolean validated)||submitted.isAfter(accepted.cutoff())||completed!=null&&(completed.isAfter(accepted.cutoff())||completed.isBefore(submitted))
            ||(status==PaymentStatus.COMPLETE)!=(completed!=null)||paymentFrom==null
            ||!(within(submitted,paymentFrom,accepted.cutoff(),false)||status==PaymentStatus.COMPLETE&&within(completed,paymentFrom,accepted.cutoff(),false)))throw invalid();
        return new Payment(text(record.get("recordReference"),128),FactsProviderProtocol.alias(identity),id(record.get("invoiceId")),text(record.get("invoiceNumber"),120),
            accepted.current().supplierId(),"INR",money(record.get("amount")),submitted,completed,status,validated);
    }
    private Supplier supplier(Map<?,?> record){
        keys(record,"recordReference","supplierId","createdAt","statusAsOfCutoff");equal(accepted.current().supplierId(),id(record.get("supplierId")));
        var created=time(record.get("createdAt"));if(created.isAfter(accepted.current().submittedAt()))throw invalid();
        // Facts v1 certified ACTIVE at this exact cutoff; the independently returned supplier must agree.
        equal("ACTIVE",record.get("statusAsOfCutoff"));return new Supplier(text(record.get("recordReference"),128),accepted.current().supplierId(),created,SupplierStatus.ACTIVE);
    }
    private StatusTransition transition(Map<?,?> record){
        keys(record,"recordReference","supplierId","previousStatus","nextStatus","changedAt");equal(accepted.current().supplierId(),id(record.get("supplierId")));
        var previous=SupplierStatus.valueOf(text(record.get("previousStatus"),16));var next=SupplierStatus.valueOf(text(record.get("nextStatus"),16));var at=time(record.get("changedAt"));
        if(previous==next||transitionFrom==null||!within(at,transitionFrom,accepted.cutoff(),true))throw invalid();
        return new StatusTransition(text(record.get("recordReference"),128),accepted.current().supplierId(),previous,next,at);
    }
    private static <T> List<T> ordered(List<T> records){return records.stream().sorted(Comparator.comparing((T v)->{
        if(v instanceof Payment p)return p.submittedAt();if(v instanceof StatusTransition t)return t.changedAt();return ((Supplier)v).createdAt();
    }).thenComparing(v->{if(v instanceof Payment p)return p.submissionId();if(v instanceof StatusTransition t)return t.evidenceId();return ((Supplier)v).evidenceId();})).toList();}
    private static Map<String,Object> factsReceipt(String reference){
        var parts=reference.split(":",-1);if(parts.length!=4||!"facts-v1".equals(parts[0]))throw invalid();
        return map("serviceId",decoded(parts[1]),"recordReference",decoded(parts[2]),"recordRevision",decoded(parts[3]));
    }
    private static SubmissionIdentity identityAlias(String value){var parts=value.split(":",-1);if(parts.length!=3||!"submission-v1".equals(parts[0]))throw invalid();var pair=new SubmissionIdentity(decoded(parts[1]),decoded(parts[2]));identity(pair(pair));equal(value,FactsProviderProtocol.alias(pair));return pair;}
    private static String decoded(String value){try{var s=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(Base64.getUrlDecoder().decode(value))).toString();text(s,128);equal(value,Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8)));return s;}catch(Exception e){throw invalid();}}
    private static Map<String,Object> pair(SubmissionIdentity i){return map("source",i.source(),"key",i.key());}
    private static SubmissionIdentity identity(Map<?,?> m){keys(m,"source","key");var source=text(m.get("source"),64);SubmissionCommand.namespace(source);return new SubmissionIdentity(source,text(m.get("key"),128));}
    private static boolean within(Instant v,Instant from,Instant to,boolean inclusive){return v!=null&&!v.isBefore(from)&&(inclusive?!v.isAfter(to):v.isBefore(to));}
    private static Instant earlier(Instant a,Instant b){return a==null||b.isBefore(a)?b:a;}
    private static String id(Object v){var s=text(v,19);SubmissionCommand.id(s);return s;}
    private static BigDecimal money(Object v){var s=text(v,16);if(!s.matches("(?:0|[1-9][0-9]{0,12})\\.[0-9]{2}"))throw invalid();return new BigDecimal(s);}
    private static Instant time(Object v){var s=text(v,40);if(!s.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,9})?(?:Z|[+-][0-9]{2}:[0-9]{2})")||s.endsWith("-00:00"))throw invalid();var t=OffsetDateTime.parse(s).toInstant();timestamp(t);return t;}
    private static void timestamp(Instant v){int year=v.atOffset(ZoneOffset.UTC).getYear();if(year<1||year>9999)throw invalid();}
    private static int integer(Object v,int min,int max){if(!(v instanceof BigInteger n))throw invalid();int i=n.intValueExact();if(i<min||i>max)throw invalid();return i;}
    private static String text(Object v,int max){if(!(v instanceof String s))throw invalid();SubmissionCommand.text(s,max);return s;}
    private static Map<?,?> object(Object v){if(v instanceof Map<?,?> m)return m;throw invalid();}
    private static void keys(Map<?,?> m,String...keys){equal(Set.of(keys),m.keySet());}
    private static void equal(Object a,Object b){if(!Objects.equals(a,b))throw invalid();}
    private static Map<String,Object> map(Object...pairs){var m=new TreeMap<String,Object>();for(int i=0;i<pairs.length;i+=2)m.put((String)pairs[i],pairs[i+1]);return m;}
    private static Object sorted(Object v){if(v instanceof Map<?,?> m){var copy=new TreeMap<String,Object>();m.forEach((key,value)->copy.put((String)key,sorted(value)));return copy;}if(v instanceof List<?> l)return l.stream().map(EvidenceProviderProtocol::sorted).toList();return v;}
    static String digest(Object population){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(sorted(population))));}catch(Exception e){throw failure();}}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid upstream evidence provider response");}
    static SubmissionFailure failure(){return new SubmissionFailure(SubmissionFailure.Code.DEPENDENCY_UNAVAILABLE);}
}
