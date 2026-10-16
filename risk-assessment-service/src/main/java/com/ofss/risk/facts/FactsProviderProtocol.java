package com.ofss.risk.facts;

import com.ofss.risk.engine.RiskInput;
import com.ofss.risk.persistence.SubmissionIdentity;
import com.ofss.risk.submission.*;
import com.ofss.risk.submission.SubmissionAdapters.VerifiedFacts;
import java.math.*;
import java.nio.*;
import java.nio.charset.*;
import java.time.*;
import java.util.*;
import tools.jackson.core.*;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** D2 provider protocol, not an existing Developer 1 API. All untrusted errors are discarded. */
public final class FactsProviderProtocol {
    public static final String VERSION="risk-authoritative-facts-v1",PATH="/internal/v1/risk-facts/resolve";
    public static final String PERMISSION="RISK_FACTS_READ";
    private final JsonMapper json=JsonMapper.builder(JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder()
        .maxNestingDepth(16).maxNumberLength(64).maxStringLength(16384).build()).build())
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,DeserializationFeature.USE_BIG_INTEGER_FOR_INTS).build();
    public byte[] request(SubmissionCommand command,Instant cutoff){
        try {
            Objects.requireNonNull(command);timestamp(cutoff);
            var body=new TreeMap<String,Object>();body.put("contractVersion",VERSION);
            body.put("submission",Map.of("source",command.submissionSource(),"key",command.submissionKey()));
            body.put("paymentId",command.paymentReference());body.put("validationReference",command.validationReference());
            body.put("validationRevision",command.validationRevision());body.put("cutoff",cutoff.toString());
            return json.writeValueAsBytes(body);
        }catch(RuntimeException exception){throw failure();}
    }
    public VerifiedFacts decode(byte[] bytes,SubmissionCommand request,Instant cutoff,String providerServiceId){
        try {
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            Map<?,?> root=json.readValue(text,Map.class);
            keys(root,"contractVersion","submission","paymentId","requesterId","validation","cutoff","payment","invoice","supplier","currentAndRetryIdentities","certification","provenance");
            equal(VERSION,root.get("contractVersion"));equal(request.identity(),identity(object(root.get("submission"))));
            equal(request.paymentReference(),id(root.get("paymentId")));equal(cutoff,time(root.get("cutoff")));
            String requester=id(root.get("requesterId"));
            var validation=object(root.get("validation"));keys(validation,"reference","revision","outcome","validatedAt","immutableReference");
            equal(request.validationReference(),text(validation.get("reference"),128));
            String revision=validation.get("revision")==null?null:text(validation.get("revision"),128);equal(request.validationRevision(),revision);
            equal("PASS",validation.get("outcome"));if(!(validation.get("immutableReference") instanceof Boolean immutable)||revision==null&&!immutable)throw invalid();
            var validatedAt=time(validation.get("validatedAt"));if(validatedAt.isAfter(cutoff))throw invalid();
            var cert=object(root.get("certification"));keys(cert,"code","complete","asOfCutoff","immutable","validationPaymentBindingVerified","requesterOwnershipVerified","eligibleForRiskAssessment","retryIdentityScope");
            equal("AUTHORITATIVE_VALIDATION_PASS_V1",cert.get("code"));equal("SAME_LOGICAL_SUBMISSION",cert.get("retryIdentityScope"));
            for(String flag:List.of("complete","asOfCutoff","immutable","validationPaymentBindingVerified","requesterOwnershipVerified","eligibleForRiskAssessment"))equal(Boolean.TRUE,cert.get(flag));
            var provenance=object(root.get("provenance"));keys(provenance,"serviceId","recordReference","recordRevision");
            equal(providerServiceId,text(provenance.get("serviceId"),64));
            String record=text(provenance.get("recordReference"),128),recordRevision=text(provenance.get("recordRevision"),128);
            var payment=object(root.get("payment"));keys(payment,"invoiceId","supplierId","amount","currencyCode","submittedAt","completedAt","statusAsOfCutoff");
            var invoice=object(root.get("invoice"));keys(invoice,"invoiceId","supplierId","invoiceNumber","invoiceAmount","currencyCode");
            var supplier=object(root.get("supplier"));keys(supplier,"supplierId","statusAsOfCutoff","createdAt");
            String supplierId=id(payment.get("supplierId")),invoiceId=id(payment.get("invoiceId"));
            equal(invoiceId,id(invoice.get("invoiceId")));equal(supplierId,id(invoice.get("supplierId")));equal(supplierId,id(supplier.get("supplierId")));
            equal("INR",payment.get("currencyCode"));equal("INR",invoice.get("currencyCode"));equal("ACTIVE",supplier.get("statusAsOfCutoff"));
            BigDecimal amount=money(payment.get("amount"));money(invoice.get("invoiceAmount")); // No invented partial-payment/equality policy.
            String invoiceNumber=text(invoice.get("invoiceNumber"),120);
            var submitted=time(payment.get("submittedAt"));var created=time(supplier.get("createdAt"));
            if(submitted.isAfter(validatedAt)||created.isAfter(submitted))throw invalid();
            var completed=payment.get("completedAt")==null?null:time(payment.get("completedAt"));
            if(completed!=null&&(completed.isAfter(cutoff)||completed.isBefore(submitted)))throw invalid();
            var status=RiskInput.PaymentStatus.valueOf(text(payment.get("statusAsOfCutoff"),32));
            if(Set.of(RiskInput.PaymentStatus.DRAFT,RiskInput.PaymentStatus.REJECTED,RiskInput.PaymentStatus.FAILED).contains(status)
                ||(status==RiskInput.PaymentStatus.COMPLETE)!=(completed!=null))throw invalid();
            if(!(root.get("currentAndRetryIdentities") instanceof List<?> identities)||identities.isEmpty()||identities.size()>1000)throw invalid();
            var namespaced=new HashSet<SubmissionIdentity>();var aliases=new HashSet<String>();
            for(var item:identities){var pair=identity(object(item));if(!namespaced.add(pair))throw invalid();aliases.add(alias(pair));}
            if(!namespaced.contains(request.identity()))throw invalid();
            String evidence="facts-v1:"+encoded(providerServiceId)+":"+encoded(record)+":"+encoded(recordRevision);
            var current=new RiskInput.Payment(evidence,alias(request.identity()),invoiceId,invoiceNumber,supplierId,"INR",amount,submitted,completed,status,true);
            return new VerifiedFacts(request.identity(),Long.parseLong(request.paymentReference()),Long.parseLong(requester),request.validationReference(),revision,current,aliases);
        }catch(Exception exception){throw failure();}
    }
    public static String alias(SubmissionIdentity identity){return "submission-v1:"+encoded(identity.source())+":"+encoded(identity.key());}
    private static String encoded(String value){return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));}
    private static Map<?,?> object(Object value){if(value instanceof Map<?,?> map)return map;throw invalid();}
    private static void keys(Map<?,?> map,String... fields){if(!map.keySet().equals(Set.of(fields)))throw invalid();}
    private static void equal(Object expected,Object actual){if(!Objects.equals(expected,actual))throw invalid();}
    private static String text(Object value,int length){if(!(value instanceof String s))throw invalid();SubmissionCommand.text(s,length);return s;}
    private static String id(Object value){var s=text(value,19);SubmissionCommand.id(s);return s;}
    private static SubmissionIdentity identity(Map<?,?> map){keys(map,"source","key");var source=text(map.get("source"),64);SubmissionCommand.namespace(source);return new SubmissionIdentity(source,text(map.get("key"),128));}
    private static BigDecimal money(Object value){var s=text(value,16);if(!s.matches("(?:0|[1-9][0-9]{0,12})\\.[0-9]{2}"))throw invalid();var n=new BigDecimal(s);if(n.signum()<=0)throw invalid();return n;}
    private static Instant time(Object value){
        var s=text(value,40);if(!s.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,9})?(?:Z|[+-][0-9]{2}:[0-9]{2})")||s.endsWith("-00:00"))throw invalid();
        var instant=OffsetDateTime.parse(s).toInstant();timestamp(instant);return instant;
    }
    private static void timestamp(Instant value){Objects.requireNonNull(value);int year=value.atOffset(ZoneOffset.UTC).getYear();if(year<1||year>9999)throw invalid();}
    private static IllegalArgumentException invalid(){return new IllegalArgumentException("Invalid certified Facts provider response");}
    private static SubmissionFailure failure(){return new SubmissionFailure(SubmissionFailure.Code.DEPENDENCY_UNAVAILABLE);}
}
