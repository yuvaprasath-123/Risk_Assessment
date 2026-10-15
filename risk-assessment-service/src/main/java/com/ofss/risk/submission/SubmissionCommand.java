package com.ofss.risk.submission;

import com.ofss.risk.persistence.SubmissionIdentity;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.core.StreamReadFeature;

/** Reference-only v1 contract: no caller-supplied validation result, money, hashes or scores. */
public record SubmissionCommand(String contractVersion,String submissionSource,String submissionKey,String paymentReference,
        String validationReference,String validationRevision,String configurationVersionId) {
    public static final String VERSION="risk-submission-v1";
    public SubmissionCommand {
        if(!VERSION.equals(contractVersion)) throw invalid();
        namespace(submissionSource); text(submissionKey,128); id(paymentReference); text(validationReference,128);
        if(validationRevision!=null) text(validationRevision,128);
        if(configurationVersionId!=null) id(configurationVersionId);
    }
    public SubmissionIdentity identity() { return new SubmissionIdentity(submissionSource,submissionKey); }
    public static SubmissionCommand decode(String json) {
        try {
            var mapper=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
            var body=mapper.readValue(json,Map.class);
            if(!Set.of("contractVersion","submissionSource","submissionKey","paymentReference","validationReference","validationRevision","configurationVersionId").containsAll(body.keySet())) throw invalid();
            return new SubmissionCommand(string(body,"contractVersion"),string(body,"submissionSource"),string(body,"submissionKey"),
                string(body,"paymentReference"),string(body,"validationReference"),optional(body,"validationRevision"),optional(body,"configurationVersionId"));
        } catch(RuntimeException e) { throw invalid(); }
    }
    private static String optional(Map<?,?> m,String k) { return m.get(k)==null?null:string(m,k); }
    private static String string(Map<?,?> m,String k) { if(m.get(k) instanceof String s)return s;throw invalid(); }
    public static void namespace(String s) { if(s==null || !s.matches("[a-z][a-z0-9.-]{0,63}"))throw invalid(); }
    public static void text(String s,int max) { if(s==null || s.isBlank() || !s.equals(s.trim()) || s.codePointCount(0,s.length())>max || s.codePoints().anyMatch(c->Character.isISOControl(c) || c>=0xd800 && c<=0xdfff))throw invalid(); }
    public static long id(String s) { try { if(s==null || !s.matches("[1-9][0-9]{0,18}"))throw invalid();return Long.parseLong(s); }catch(NumberFormatException e){throw invalid();} }
    private static SubmissionFailure invalid(){return new SubmissionFailure(SubmissionFailure.Code.INVALID_SUBMISSION);}
    @Override public String toString(){return "SubmissionCommand[version="+contractVersion+"]";}
}
