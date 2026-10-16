package com.ofss.risk.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;
import static com.ofss.risk.persistence.EvidenceFreezeRequest.*;

/** v1: exact money strings, UTC nanoseconds, sorted maps/sets, meaningful list order and explicit nulls. */
public final class CanonicalEvidence {
    public static final String HASH_FORMAT="risk-evidence-canonical-v1", EVIDENCE_FORMAT="risk-evidence-v1",
        INPUT_FORMAT="risk-input-v1", MANIFEST_FORMAT="risk-input-manifest-v1";
    private static final JsonMapper JSON=JsonMapper.builder().build();
    record Document(String json,String hash) {}
    Document evidence(Kind kind,Source source,Object envelope) {
        return document(map("hashFormatVersion",HASH_FORMAT,"evidenceFormatVersion",EVIDENCE_FORMAT,
            "kind",kind.name(),"source",source,"evidence",envelope));
    }
    Document input(EvidenceContent request,AcceptedAssessment accepted) {
        return document(map("hashFormatVersion",HASH_FORMAT,"inputFormatVersion",INPUT_FORMAT,
            "acceptanceHash",accepted.acceptanceHash(),"configurationHash",accepted.configurationHash(),
            "evaluatorVersion",request.acceptance().evaluatorVersion(),"input",request.input(),"sources",request.sources()));
    }
    Document manifest(Document input,List<RiskEvidenceRepository.Stored> children) {
        var references=children.stream().map(row -> map("key",row.key(),"evidenceId",Long.toString(row.id()),"hash",row.hash())).toList();
        return document(map("hashFormatVersion",HASH_FORMAT,"manifestFormatVersion",MANIFEST_FORMAT,
            "inputHash",input.hash(),"inputDocument",CanonicalAcceptance.read(input.json()),"references",references));
    }
    static Document document(Object body) {
        String json=JSON.writeValueAsString(normalize(body)); return new Document(json,CanonicalAcceptance.hash(json));
    }
    static Map<String,Object> map(Object... values) {
        var result=new TreeMap<String,Object>(); for(int i=0;i<values.length;i+=2) result.put((String)values[i],values[i+1]); return result;
    }
    private static Object normalize(Object value) {
        if(value==null || value instanceof Boolean || value instanceof Integer || value instanceof Long) return value;
        if(value instanceof String s) { PersistenceValues.unicode(s); return s; }
        if(value instanceof Enum<?> e) return e.name();
        if(value instanceof Instant t) { PersistenceValues.timestamp(t); return t.toString(); }
        if(value instanceof BigDecimal d) return d.setScale(2,java.math.RoundingMode.UNNECESSARY).toPlainString();
        if(value instanceof Set<?> set) return set.stream().map(CanonicalEvidence::normalize)
            .sorted(Comparator.comparing(JSON::writeValueAsString)).toList();
        if(value instanceof List<?> list) return list.stream().map(CanonicalEvidence::normalize).toList();
        if(value instanceof Map<?,?> source) {
            var result=new TreeMap<String,Object>(); source.forEach((k,v)->result.put(k instanceof Enum<?> e?e.name():k.toString(),normalize(v))); return result;
        }
        if(value.getClass().isRecord()) {
            var result=new TreeMap<String,Object>();
            for(var component:value.getClass().getRecordComponents()) {
                try { result.put(component.getName(),normalize(component.getAccessor().invoke(value))); }
                catch(ReflectiveOperationException e) { throw new IllegalArgumentException("Unsupported evidence record"); }
            }
            return result;
        }
        throw new IllegalArgumentException("Unsupported exact evidence type");
    }
}
