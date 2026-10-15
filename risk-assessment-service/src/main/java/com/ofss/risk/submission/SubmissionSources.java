package com.ofss.risk.submission;

import java.util.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.core.StreamReadFeature;

/** Receiver-owned namespace ownership; a namespace has exactly one authenticated owner. */
public final class SubmissionSources {
    private final Map<String,String> owners;
    public SubmissionSources(String json) {
        try {
            Map<?,?> root=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build().readValue(json,Map.class);
            var result=new HashMap<String,String>();
            if(root.isEmpty())throw new IllegalArgumentException();
            for(var entry:root.entrySet()) {
                if(!(entry.getKey() instanceof String service) || !service.matches("[a-z][a-z0-9-]{0,63}")
                    || !(entry.getValue() instanceof List<?> namespaces) || namespaces.isEmpty())throw new IllegalArgumentException();
                for(Object value:namespaces) {
                    if(!(value instanceof String source))throw new IllegalArgumentException();
                    SubmissionCommand.namespace(source);
                    if(result.putIfAbsent(source,service)!=null)throw new IllegalArgumentException();
                }
            }
            owners=Map.copyOf(result);
        }catch(RuntimeException e){throw new IllegalStateException("Valid external submission namespace ownership is required");}
    }
    public void require(String service,String namespace) {
        SubmissionCommand.namespace(namespace);
        if(service==null || !Objects.equals(service,owners.get(namespace)))throw new SubmissionFailure(SubmissionFailure.Code.SOURCE_FORBIDDEN);
    }
}
