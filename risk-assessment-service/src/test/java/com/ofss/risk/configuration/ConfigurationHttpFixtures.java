package com.ofss.risk.configuration;

import com.ofss.risk.persistence.SubmissionFixtures;
import java.util.*;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.DeserializationFeature;

/** Controlled publication-shaped HTTP documents; does not bypass or establish publication readiness. */
final class ConfigurationHttpFixtures {
    static final JsonMapper JSON=JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,DeserializationFeature.USE_BIG_INTEGER_FOR_INTS).build();
    @SuppressWarnings("unchecked") static Map<String,Object> content(String version){
        var root=(Map<String,Object>)JSON.readValue(SubmissionFixtures.published().canonicalConfiguration(),Map.class);
        root.put("versionId",version);root.put("versionLabel","HTTP contract परीक्षण 😀");root.put("description","Exact full publication fixture");
        int id=100;
        for(var row:rows(root,"riskRules")){row.put("ruleConfigId",Integer.toString(id++));row.put("displayName","Fixture "+row.get("ruleCode"));}
        for(var row:rows(root,"riskBands"))row.put("bandConfigId",Integer.toString(id++));
        for(var row:rows(root,"approvalThresholds"))row.put("thresholdConfigId",Integer.toString(id++));
        for(var row:rows(root,"approvalSlas")){
            row.put("slaConfigId",Integer.toString(id++));row.put("durationSeconds",new BigInteger("999999999999"));
            // Preserve supported versioned policy codes from the base fixture.
        }
        root.put("approvalPolicy",com.ofss.risk.contract.ApprovalPolicyContracts.snapshot(com.ofss.risk.contract.ApprovalPolicyContracts.BAND_LEVELS,
            java.util.stream.IntStream.rangeClosed(1,3).mapToObj(level->new com.ofss.risk.contract.ApprovalPolicyContracts.Sla(level,999999999999L,"APPROVAL_PENDING_V1",level<3?"NEXT_LEVEL_V1":"SAME_LEVEL_BACKUP_ONCE_V1",level<3?null:"MANUAL_REVIEW_V1")).toList()));
        return root;
    }
    @SuppressWarnings("unchecked") static List<Map<String,Object>> rows(Map<String,Object> root,String field){return (List<Map<String,Object>>)root.get(field);}
    static Map<String,Object> envelope(Map<String,Object> content,boolean active){
        var body=new TreeMap<String,Object>();body.put("versionId",content.get("versionId"));body.put("versionRowVersion","9");
        body.put("publishedAt","2026-10-14T00:00:00.123456789Z");body.put("publishedByUserId","42");
        body.put("hashFormat","configuration-content-v2");body.put("contentHash",hash(content));body.put("configuration",content);
        if(active){body.put("pointerRowVersion","9007199254740993");body.put("activatedAt","2026-10-15T00:00:00.987654321Z");body.put("activatedByUserId","43");}
        return body;
    }
    static byte[] bytes(Map<String,Object> body){return JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);}
    static byte[] response(boolean active){return bytes(envelope(content("9007199254740993"),active));}
    static String hash(Object value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical(value).getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new AssertionError(e);}}
    // Independent publisher algorithm: sorted object keys, ordered arrays, exact normalized numeric values.
    static String canonical(Object value){
        if(value instanceof Map<?,?> m){var sorted=new TreeMap<String,Object>();m.forEach((k,v)->sorted.put((String)k,v));return "{"+String.join(",",sorted.entrySet().stream().map(e->JSON.writeValueAsString(e.getKey())+":"+canonical(e.getValue())).toList())+"}";}
        if(value instanceof List<?> l)return "["+String.join(",",l.stream().map(ConfigurationHttpFixtures::canonical).toList())+"]";
        if(value instanceof BigDecimal b)return b.signum()==0?"0":b.stripTrailingZeros().toPlainString();return JSON.writeValueAsString(value);
    }
}
