package com.ofss.risk.persistence;

import com.ofss.risk.engine.RiskInput;
import com.ofss.risk.contract.RuleCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import static com.ofss.risk.engine.RiskInput.*;

/** Exact, version-gated decoder for persisted input v1; no Jackson numeric/time coercion. */
final class FrozenInputDecoder {
    static RiskInput read(String canonicalJson) {
        var root=CanonicalAcceptance.read(canonicalJson);
        keys(root,"hashFormatVersion","inputFormatVersion","acceptanceHash","configurationHash","evaluatorVersion","input","sources");
        if(!CanonicalEvidence.HASH_FORMAT.equals(root.get("hashFormatVersion"))
            || !CanonicalEvidence.INPUT_FORMAT.equals(root.get("inputFormatVersion"))) throw invalid();
        var input=object(root.get("input"));
        keys(input,"current","cutoff","currentAndRetrySubmissionIds","payments","supplier","supplierTransitions","previousAssessments");
        return new RiskInput(payment(object(input.get("current"))),time(input,"cutoff"),strings(input.get("currentAndRetrySubmissionIds")),
            evidence(object(input.get("payments")),FrozenInputDecoder::payment),
            evidence(object(input.get("supplier")),FrozenInputDecoder::supplier),
            evidence(object(input.get("supplierTransitions")),FrozenInputDecoder::transition),
            evidence(object(input.get("previousAssessments")),FrozenInputDecoder::prior));
    }
    private static <T> Evidence<T> evidence(Map<?,?> value,Function<Map<?,?>,T> records) {
        keys(value,"availability","sourceSnapshotId","supplierId","currencyCode","cutoff","coveredFrom","records");
        return new Evidence<>(Availability.valueOf(text(value,"availability")),optional(value,"sourceSnapshotId"),
            text(value,"supplierId"),text(value,"currencyCode"),time(value,"cutoff"),optionalTime(value,"coveredFrom"),
            list(value.get("records")).stream().map(v->records.apply(object(v))).toList());
    }
    static Evidence<PriorAssessment> priorEvidence(Map<?,?> value){return evidence(value,FrozenInputDecoder::prior);}
    private static Payment payment(Map<?,?> value) {
        keys(value,"evidenceId","submissionId","invoiceId","invoiceNumber","supplierId","currencyCode","amount","submittedAt","completedAt","statusAsOfCutoff","validated");
        if(!(value.get("validated") instanceof Boolean validated)) throw invalid();
        String money=text(value,"amount"); if(!money.matches("[0-9]+\\.[0-9]{2}")) throw invalid();
        return new Payment(text(value,"evidenceId"),text(value,"submissionId"),text(value,"invoiceId"),text(value,"invoiceNumber"),
            text(value,"supplierId"),text(value,"currencyCode"),new BigDecimal(money),time(value,"submittedAt"),optionalTime(value,"completedAt"),
            PaymentStatus.valueOf(text(value,"statusAsOfCutoff")),validated);
    }
    private static Supplier supplier(Map<?,?> value) {
        keys(value,"evidenceId","supplierId","createdAt","status");
        return new Supplier(text(value,"evidenceId"),text(value,"supplierId"),time(value,"createdAt"),SupplierStatus.valueOf(text(value,"status")));
    }
    private static StatusTransition transition(Map<?,?> value) {
        keys(value,"evidenceId","supplierId","previous","next","changedAt");
        return new StatusTransition(text(value,"evidenceId"),text(value,"supplierId"),SupplierStatus.valueOf(text(value,"previous")),
            SupplierStatus.valueOf(text(value,"next")),time(value,"changedAt"));
    }
    private static PriorAssessment prior(Map<?,?> value) {
        keys(value,"evidenceId","submissionId","supplierId","assessedAt","triggeredFactors");
        var factors=strings(value.get("triggeredFactors")).stream().map(RuleCode::valueOf).collect(java.util.stream.Collectors.toSet());
        return new PriorAssessment(text(value,"evidenceId"),text(value,"submissionId"),text(value,"supplierId"),time(value,"assessedAt"),factors);
    }
    private static Set<String> strings(Object value) {
        var result=new HashSet<String>(); for(Object item:list(value)) {
            if(!(item instanceof String s) || !result.add(s)) throw invalid();
        } return Set.copyOf(result);
    }
    private static String text(Map<?,?> value,String key) { if(value.get(key) instanceof String s) return s; throw invalid(); }
    private static String optional(Map<?,?> value,String key) { return value.get(key)==null?null:text(value,key); }
    private static Instant time(Map<?,?> value,String key) { var time=Instant.parse(text(value,key)); PersistenceValues.timestamp(time); return time; }
    private static Instant optionalTime(Map<?,?> value,String key) { return value.get(key)==null?null:time(value,key); }
    private static Map<?,?> object(Object value) { if(value instanceof Map<?,?> m) return m; throw invalid(); }
    private static List<?> list(Object value) { if(value instanceof List<?> l) return l; throw invalid(); }
    private static void keys(Map<?,?> value,String... keys) { if(!value.keySet().equals(Set.of(keys))) throw invalid(); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid persisted input v1"); }
}
