package com.ofss.risk.facts;

import com.ofss.risk.submission.SubmissionCommand;
import java.time.*;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Explicit test provider data only; never a runtime adapter or seed. */
final class FactsHttpFixtures {
    static final String PROVIDER="authoritative-facts-provider";
    static final Instant CUTOFF=Instant.parse("2026-10-15T10:20:30.987654321Z");
    static final JsonMapper JSON=JsonMapper.builder().build();
    static SubmissionCommand command(){return new SubmissionCommand(SubmissionCommand.VERSION,"developer1.payment.v1","submission/ಕನ್ನಡ?x=1#id","9007199254740993","validation:immutable:100","17",null);}
    static Map<String,Object> response(SubmissionCommand command,Instant cutoff){
        var root=new TreeMap<String,Object>();root.put("contractVersion",FactsProviderProtocol.VERSION);
        root.put("submission",map("source",command.submissionSource(),"key",command.submissionKey()));
        root.put("paymentId",command.paymentReference());root.put("requesterId","9223372036854775807");root.put("cutoff",offset(cutoff));
        root.put("validation",map("reference",command.validationReference(),"revision",command.validationRevision(),"outcome","PASS","validatedAt",offset(cutoff.minusSeconds(5)),"immutableReference",command.validationRevision()==null));
        root.put("payment",map("invoiceId","75","supplierId","456","amount","9007199254740.91","currencyCode","INR","submittedAt",offset(cutoff.minusSeconds(10)),"completedAt",null,"statusAsOfCutoff","PENDING_APPROVAL"));
        root.put("invoice",map("invoiceId","75","supplierId","456","invoiceNumber","INV-ಕನ್ನಡ-😀","invoiceAmount","9999999999999.99","currencyCode","INR"));
        root.put("supplier",map("supplierId","456","statusAsOfCutoff","ACTIVE","createdAt",offset(cutoff.minus(Duration.ofDays(365)))));
        root.put("currentAndRetryIdentities",new ArrayList<>(List.of(map("source",command.submissionSource(),"key",command.submissionKey()),map("source","legacy.payment.v1","key","same-logical-submission"))));
        root.put("certification",map("code","AUTHORITATIVE_VALIDATION_PASS_V1","complete",true,"asOfCutoff",true,"immutable",true,
            "validationPaymentBindingVerified",true,"requesterOwnershipVerified",true,"eligibleForRiskAssessment",true,"retryIdentityScope","SAME_LOGICAL_SUBMISSION"));
        root.put("provenance",map("serviceId",PROVIDER,"recordReference","validation/Ω?source=1","recordRevision","record-v17"));return root;
    }
    static String offset(Instant t){return t.atOffset(ZoneOffset.ofHoursMinutes(5,30)).toString();}
    static Map<String,Object> map(Object... pairs){var result=new TreeMap<String,Object>();for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);return result;}
    @SuppressWarnings("unchecked") static Map<String,Object> object(Map<String,Object> root,String key){return (Map<String,Object>)root.get(key);}
    static byte[] bytes(Object v){return JSON.writeValueAsBytes(v);}
    static void change(Map<String,Object> root,String path,Object value){var split=path.split("\\.");var target=split.length==1?root:object(root,split[0]);target.put(split[split.length-1],value);}
}
