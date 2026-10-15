package com.ofss.risk.submission;

import com.ofss.risk.engine.RiskConfiguration;
import com.ofss.risk.contract.RuleCode;
import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.core.StreamReadFeature;

/** Exact configuration-content-v1 mapping. Transport provenance is the adapter's responsibility. */
public final class PublishedConfigurationMapping {
    private final JsonMapper json=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS,DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,
                DeserializationFeature.USE_BIG_INTEGER_FOR_INTS).build();
    public RiskConfiguration map(SubmissionAdapters.PublishedSnapshot snapshot) {
        try { return verified(snapshot); }
        catch(Exception e){throw new SubmissionFailure(SubmissionFailure.Code.DEPENDENCY_UNAVAILABLE);}
    }
    @SuppressWarnings("unchecked") RiskConfiguration verified(SubmissionAdapters.PublishedSnapshot snapshot) throws Exception {
            if(snapshot==null || !"PUBLISHED".equals(snapshot.publicationState()) || !"configuration-content-v1".equals(snapshot.hashFormat())
                || snapshot.contentHash()==null || !snapshot.contentHash().matches("[0-9a-f]{64}"))throw new IllegalArgumentException();
            var root=json.readValue(snapshot.canonicalConfiguration(),Map.class);
            if(!root.keySet().equals(Set.of("hashFormat","versionId","versionNumber","versionLabel","description","isDemonstration",
                "scorePolicyCode","maxFinalScore","createdAt","createdByUserId","riskRules","riskBands","approvalThresholds","approvalSlas")))throw new IllegalArgumentException();
            String canonical=canonical(root);
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
            if(!canonical.equals(snapshot.canonicalConfiguration()) || !hash.equals(snapshot.contentHash())
                || !snapshot.versionId().equals(root.get("versionId")) || !snapshot.hashFormat().equals(root.get("hashFormat")))throw new IllegalArgumentException();
            SubmissionCommand.id(snapshot.versionId());
            if(!snapshot.rowVersion().matches("0|[1-9][0-9]{0,18}"))throw new IllegalArgumentException();Long.parseLong(snapshot.rowVersion());
            var rules=((List<Map<String,Object>>)root.get("riskRules")).stream().map(r->{
                if(!(r.get("enabled") instanceof Boolean enabled))throw new IllegalArgumentException();
                return RiskConfiguration.Rule.from(RuleCode.valueOf((String)r.get("ruleCode")),enabled,integer(r.get("weight")),integer(r.get("evaluationOrder")),
                    (String)r.get("parameterSchemaVersion"),(Map<String,Object>)r.get("parameters"));
            }).toList();
            var bands=((List<Map<String,Object>>)root.get("riskBands")).stream().map(b->new RiskConfiguration.Band((String)b.get("bandCode"),
                integer(b.get("minScoreInclusive")),b.get("maxScoreInclusive")==null?null:integer(b.get("maxScoreInclusive")))).toList();
            var tiers=(List<Map<String,Object>>)root.get("approvalThresholds");var boundaries=new TreeMap<Integer,BigDecimal>();
            var levels=new HashSet<Integer>();BigDecimal next=BigDecimal.ZERO;
            for(var tier:tiers.stream().sorted(Comparator.comparingInt(t->integer(t.get("approvalLevel")))).toList()) {
                int level=integer(tier.get("approvalLevel"));if(level<1 || level>3 || !levels.add(level))throw new IllegalArgumentException();
                BigDecimal min=money(tier.get("minAmountInclusive"));if(next==null || min.compareTo(next)!=0)throw new IllegalArgumentException();
                BigDecimal max=tier.get("maxAmountExclusive")==null?null:money(tier.get("maxAmountExclusive"));
                if(max!=null && max.compareTo(min)<=0 || level<3 && max==null || level==3 && max!=null)throw new IllegalArgumentException();
                if(level>1)boundaries.put(level,min);next=max;
            }
            if(!levels.equals(Set.of(1,2,3)))throw new IllegalArgumentException();
            var slas=(List<Map<String,Object>>)root.get("approvalSlas");
            if(slas.size()!=3 || !slas.stream().map(s->integer(s.get("approvalLevel"))).collect(java.util.stream.Collectors.toSet()).equals(Set.of(1,2,3)))throw new IllegalArgumentException();
            int cap;
            if("CAP_AT_MAX".equals(root.get("scorePolicyCode")))cap=integer(root.get("maxFinalScore"));
            else if("SUM".equals(root.get("scorePolicyCode")) && root.get("maxFinalScore")==null)
                cap=rules.stream().filter(RiskConfiguration.Rule::enabled).mapToInt(RiskConfiguration.Rule::weight).reduce(0,Math::addExact);
            else throw new IllegalArgumentException();
            return new RiskConfiguration(snapshot.versionId(),snapshot.rowVersion(),hash,"INR",cap,rules,bands,boundaries);
    }
    private static int integer(Object value){if(value instanceof BigInteger b)return b.intValueExact();if(value instanceof Integer i)return i;throw new IllegalArgumentException();}
    private static BigDecimal money(Object value){if(!(value instanceof String s) || !s.matches("[0-9]+\\.[0-9]{2}"))throw new IllegalArgumentException();var n=new BigDecimal(s);if(n.precision()>15)throw new IllegalArgumentException();return n;}
    private String canonical(Object value) {
        if(value==null)return "null";if(value instanceof String s)return json.writeValueAsString(s);if(value instanceof Boolean b)return b.toString();
        if(value instanceof BigDecimal b)return b.signum()==0?"0":b.stripTrailingZeros().toPlainString();
        if(value instanceof BigInteger || value instanceof Integer)return value.toString();
        if(value instanceof List<?> l)return "["+String.join(",",l.stream().map(this::canonical).toList())+"]";
        if(value instanceof Map<?,?> m){var sorted=new TreeMap<String,Object>();m.forEach((k,v)->{if(!(k instanceof String s))throw new IllegalArgumentException();sorted.put(s,v);});
            return "{"+String.join(",",sorted.entrySet().stream().map(e->canonical(e.getKey())+":"+canonical(e.getValue())).toList())+"}";}
        throw new IllegalArgumentException();
    }
}
