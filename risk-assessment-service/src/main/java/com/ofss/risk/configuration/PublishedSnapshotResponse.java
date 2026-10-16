package com.ofss.risk.configuration;

import com.ofss.risk.submission.*;
import java.math.BigInteger;
import java.nio.*;
import java.nio.charset.*;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;

/** Strict existing internal endpoint DTO, including complete child rows; publication hash is verified before use. */
final class PublishedSnapshotResponse {
    private static final Set<String> PINNED=Set.of("versionId","versionRowVersion","publishedAt","publishedByUserId","contentHash","hashFormat","configuration");
    private static final Set<String> ACTIVE;
    static{var fields=new HashSet<>(PINNED);fields.addAll(Set.of("pointerRowVersion","activatedAt","activatedByUserId"));ACTIVE=Set.copyOf(fields);}
    private final JsonMapper json=JsonMapper.builder(JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder()
        .maxNestingDepth(64).maxNumberLength(1000).build()).build()).enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,DeserializationFeature.USE_BIG_INTEGER_FOR_INTS).build();
    private final PublishedConfigurationMapping mapping=new PublishedConfigurationMapping();
    SubmissionAdapters.PublishedSnapshot decode(byte[] bytes,String explicitVersion) {
        try {
            String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            Map<?,?> body=json.readValue(text,Map.class);keys(body,explicitVersion==null?ACTIVE:PINNED);
            expansionBudget(body,new long[]{4*1024*1024});
            String version=id(body.get("versionId"),true),revision=id(body.get("versionRowVersion"),true);
            if(explicitVersion!=null&&!explicitVersion.equals(version))throw invalid();
            Instant published=time(body.get("publishedAt"));id(body.get("publishedByUserId"),true);
            if(explicitVersion==null){id(body.get("pointerRowVersion"),false);id(body.get("activatedByUserId"),true);if(time(body.get("activatedAt")).isBefore(published))throw invalid();}
            Map<?,?> content=object(body.get("configuration"));
            keys(content,Set.of("hashFormat","versionId","versionNumber","versionLabel","description","isDemonstration","scorePolicyCode","maxFinalScore",
                "createdAt","createdByUserId","riskRules","riskBands","approvalThresholds","approvalSlas","approvalPolicy"));
            if(!version.equals(id(content.get("versionId"),true)) || published.isBefore(time(content.get("createdAt"))))throw invalid();
            id(content.get("createdByUserId"),true);integer(content.get("versionNumber"),1,Integer.MAX_VALUE);label(content.get("versionLabel"),100);
            if(content.get("description")!=null)optionalText(content.get("description"),1000);
            if(!(content.get("isDemonstration") instanceof Boolean))throw invalid();
            children(content,"riskRules","ruleConfigId",Set.of("ruleConfigId","ruleCode","displayName","enabled","weight","parameterSchemaVersion","parameters","evaluationOrder"),row->{
                label(row.get("displayName"),120);label(row.get("parameterSchemaVersion"),32);object(row.get("parameters"));});
            children(content,"riskBands","bandConfigId",Set.of("bandConfigId","bandCode","minScoreInclusive","maxScoreInclusive","approvalLevel"),row->integer(row.get("approvalLevel"),1,3));
            children(content,"approvalThresholds","thresholdConfigId",Set.of("thresholdConfigId","approvalLevel","minAmountInclusive","maxAmountExclusive"),row->{});
            children(content,"approvalSlas","slaConfigId",Set.of("slaConfigId","approvalLevel","durationSeconds","startEventCode","escalationPolicyCode","terminalLevelPolicyCode"),row->{
                integer(row.get("durationSeconds"),1,999999999999L);label(row.get("startEventCode"),32);label(row.get("escalationPolicyCode"),32);
                if(row.get("terminalLevelPolicyCode")!=null)label(row.get("terminalLevelPolicyCode"),32);});
            var snapshot=new SubmissionAdapters.PublishedSnapshot(version,revision,"PUBLISHED",string(body.get("hashFormat")),string(body.get("contentHash")),mapping.canonicalContent(content));
            // Existing authenticated route attests publication; recomputation verifies every content field, not just the scoring subset.
            mapping.map(snapshot);return snapshot;
        }catch(Exception e){throw invalid();}
    }
    private static void children(Map<?,?> content,String field,String id,Set<String> fields,java.util.function.Consumer<Map<?,?>> validate){
        if(!(content.get(field) instanceof List<?> rows))throw invalid();var ids=new HashSet<String>();
        for(Object value:rows){var row=object(value);keys(row,fields);if(!ids.add(id(row.get(id),true)))throw invalid();validate.accept(row);}
    }
    private static void keys(Map<?,?> map,Set<String> fields){if(!map.keySet().equals(fields))throw invalid();}
    private static Map<?,?> object(Object value){if(value instanceof Map<?,?> m)return m;throw invalid();}
    private static String string(Object value){if(value instanceof String s)return s;throw invalid();}
    private static String id(Object value,boolean positive){String s=string(value);if(!s.matches(positive?"[1-9][0-9]{0,18}":"0|[1-9][0-9]{0,18}"))throw invalid();Long.parseLong(s);return s;}
    private static long integer(Object value,long min,long max){if(!(value instanceof BigInteger b))throw invalid();long n=b.longValueExact();if(n<min||n>max)throw invalid();return n;}
    private static void label(Object value,int max){String s=string(value);if(s.isBlank())throw invalid();optionalText(s,max);}
    private static void optionalText(Object value,int max){String s=string(value);if(s.codePointCount(0,s.length())>max||s.codePoints().anyMatch(c->c>=0xd800&&c<=0xdfff))throw invalid();}
    private static Instant time(Object value){String s=string(value);if(!s.endsWith("Z"))throw invalid();return Instant.parse(s);}
    // Scientific notation must not turn a bounded response into an unbounded canonical decimal allocation.
    private static void expansionBudget(Object value,long[] budget){
        long size=1;
        if(value instanceof java.math.BigDecimal b)size=(long)b.precision()+Math.abs((long)b.scale())+3;
        else if(value instanceof BigInteger b)size=b.toString().length();
        else if(value instanceof String s)size=s.length();
        else if(value instanceof Map<?,?> m){m.forEach((k,v)->{expansionBudget(k,budget);expansionBudget(v,budget);});}
        else if(value instanceof List<?> l)l.forEach(v->expansionBudget(v,budget));
        budget[0]-=size;if(budget[0]<0)throw invalid();
    }
    private static SubmissionFailure invalid(){return new SubmissionFailure(SubmissionFailure.Code.DEPENDENCY_UNAVAILABLE);}
}
