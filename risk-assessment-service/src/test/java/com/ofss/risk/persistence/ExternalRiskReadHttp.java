package com.ofss.risk.persistence;

import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.io.IOException;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Genuine externally supplied tokens only; no signing key, token minting, redirects or response-body logging. */
final class ExternalRiskReadHttp {
    static final String ROOT="/api/v1/risk-assessments/";
    private static final JsonMapper JSON=JsonMapper.builder().build();
    record Reply(int status,HttpHeaders headers,Map<String,Object> body) {}
    private final HttpClient client; private final URI base; private final Runnable uncertain;
    ExternalRiskReadHttp(HttpClient client,URI base,Runnable uncertain) {
        if(client.followRedirects()!=HttpClient.Redirect.NEVER) throw new IllegalArgumentException("Redirects must be disabled");
        this.client=client; this.base=base(base.toString()); this.uncertain=uncertain;
    }
    static URI base(String value) {
        try {
            URI uri=URI.create(value==null||value.isBlank()?"http://127.0.0.1:8087":value);
            String host=uri.getHost();
            if(!Set.of("http","https").contains(uri.getScheme()) || host==null
                || !Set.of("localhost","127.0.0.1","::1","[::1]").contains(host.toLowerCase(Locale.ROOT))
                || uri.getPort()<1 || uri.getPort()>65535 || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null
                || !Set.of("","/").contains(uri.getPath())) throw new IllegalArgumentException();
            return uri;
        } catch(RuntimeException e) {throw new IllegalArgumentException("Explicit loopback risk HTTP URL/port required");}
    }
    static String requiredToken(String value) {
        if(value==null || !value.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"))
            throw new IllegalArgumentException("Supply a genuine raw issuer token externally; token contents withheld");
        return value;
    }
    static String optionalToken(String value) {return value==null||value.isBlank()?null:requiredToken(value);}
    @SuppressWarnings("unchecked") Reply get(String path,String token,String scheme) {
        if(!path.startsWith(ROOT) || !path.matches("[/A-Za-z0-9._-]+") || path.contains("..")) throw new IllegalArgumentException("Risk read path required");
        var builder=HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(15)).header("Accept","application/json").GET();
        if(token!=null) builder.header("Authorization",scheme+" "+token);
        final HttpResponse<String> reply;
        try {reply=client.send(builder.build(),HttpResponse.BodyHandlers.ofString());}
        catch(InterruptedException e) {uncertain.run(); Thread.currentThread().interrupt(); throw new AssertionError("HTTP request interrupted; fixture cleanup deferred");}
        catch(IOException e) {uncertain.run(); throw new AssertionError("HTTP request completion uncertain; fixture cleanup deferred; response details withheld");}
        try {
            if(!reply.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT).startsWith("application/json")) throw new IllegalArgumentException();
            Map<String,Object> body=JSON.readValue(reply.body(),Map.class); if(body==null) throw new IllegalArgumentException();
            return new Reply(reply.statusCode(),reply.headers(),Collections.unmodifiableMap(body));
        } catch(RuntimeException e) {throw new AssertionError("Expected a JSON API response; body withheld");}
    }
}
