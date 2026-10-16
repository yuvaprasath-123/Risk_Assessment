package com.ofss.risk.persistence;

import com.ofss.risk.engine.RiskConfiguration;
import com.ofss.risk.contract.RuleCode;
import java.math.BigDecimal;
import java.util.*;

final class StoredConfigurationDecoder {
    @SuppressWarnings("unchecked") static RiskConfiguration read(String json) {
        var root=CanonicalAcceptance.read(json);
        if(!CanonicalAcceptance.HASH_FORMAT.equals(root.get("hashFormatVersion"))
            || !CanonicalAcceptance.SNAPSHOT_FORMAT.equals(root.get("snapshotFormatVersion"))) throw invalid();
        var c=(Map<String,Object>)root.get("configuration");
        var rules=((List<Map<String,Object>>)c.get("rules")).stream().map(r->{
            if(!(r.get("enabled") instanceof Boolean enabled)) throw invalid();
            return RiskConfiguration.Rule.from(RuleCode.valueOf(text(r,"code")),enabled,integer(r.get("weight")),integer(r.get("evaluationOrder")),
                text(r,"parameterSchemaVersion"),(Map<String,?>)r.get("parameters"));
        }).toList();
        var bands=((List<Map<String,Object>>)c.get("bands")).stream().map(b->new RiskConfiguration.Band(text(b,"code"),integer(b.get("minimum")),
            b.get("maximum")==null?null:integer(b.get("maximum")))).toList();
        var boundaries=new TreeMap<Integer,BigDecimal>();
        ((Map<String,Object>)c.get("approvalBoundaries")).forEach((key,value)-> {
            if(!(value instanceof String s)) throw invalid(); boundaries.put(Integer.parseInt(key),new BigDecimal(s));
        });
        var config=new RiskConfiguration(text(c,"versionId"),text(c,"rowVersion"),text(c,"contentHash"),text(c,"currencyCode"),
            integer(c.get("maxFinalScore")),rules,bands,boundaries);
        if(!new CanonicalAcceptance().configuration(config).json().equals(json)) throw invalid();
        return config;
    }
    private static int integer(Object value) { long result=PersistenceValues.number(value,false); if(result>Integer.MAX_VALUE) throw invalid(); return (int)result; }
    private static String text(Map<?,?> value,String key) { if(value.get(key) instanceof String s) return s; throw invalid(); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid configuration snapshot v1"); }
}
