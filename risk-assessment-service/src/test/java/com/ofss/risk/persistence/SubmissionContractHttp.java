package com.ofss.risk.persistence;

import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.DeserializationFeature;

/** Test-only loopback client. No redirects, tokens/bodies in diagnostics or arbitrary targets. */
final class SubmissionContractHttp implements AutoCloseable {
    static final String POST="/internal/v1/risk-assessments",STATUS="/internal/v1/risk-assessment-submissions/status";
    private final HttpClient client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();
    private final Runnable uncertain;
    private final JsonMapper json=JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,DeserializationFeature.USE_BIG_INTEGER_FOR_INTS).build();
    record Reply(int status,Map<String,Object> body) {}
    SubmissionContractHttp(Runnable uncertain){this.uncertain=uncertain;}
    static URI target(URI base,String path,String source,String key){
        if(base==null||!"http".equals(base.getScheme())||!"127.0.0.1".equals(base.getHost())||base.getPort()<1||base.getUserInfo()!=null||base.getRawQuery()!=null||base.getFragment()!=null||!base.getRawPath().isEmpty())throw new IllegalArgumentException("Loopback test origin required");
        if(!POST.equals(path)&&!STATUS.equals(path)&&!path.matches("/internal/v1/risk-assessments/[1-9][0-9]*(?:/factors|/configuration-snapshot)?"))throw new IllegalArgumentException("Unknown submission verification route");
        if(source!=null){if(!STATUS.equals(path)||key==null)throw new IllegalArgumentException("Status references required");OracleSubmissionFixtures.requireNamespace(source);if(!OracleSubmissionFixtures.KEYS.contains(key))throw new IllegalArgumentException("Unknown fixture key");path+="?submissionSource="+source+"&submissionKey="+key;}
        else if(key!=null||STATUS.equals(path))throw new IllegalArgumentException("Status references required");
        return base.resolve(path);
    }
    @SuppressWarnings("unchecked") Reply send(URI base,String path,String source,String key,String token,String body){
        var request=HttpRequest.newBuilder(target(base,path,source,key)).timeout(Duration.ofSeconds(45)).header("Accept","application/json");
        if(token!=null)request.header("Authorization","Bearer "+token);
        if(body==null)request.GET();else {if(!POST.equals(path))throw new IllegalArgumentException("POST path required");request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body));}
        final HttpResponse<String> response;
        try{response=client.send(request.build(),HttpResponse.BodyHandlers.ofString());}
        catch(Exception e){uncertain.run();if(e instanceof InterruptedException)Thread.currentThread().interrupt();throw new AssertionError("Submission HTTP completion uncertain; stop workers before cleanup; payload withheld");}
        try{if(!response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"))throw new IllegalArgumentException();Object parsed=json.readValue(response.body(),Object.class);if(!(parsed instanceof Map<?,?> map))throw new IllegalArgumentException();return new Reply(response.statusCode(),(Map<String,Object>)map);}
        catch(RuntimeException e){throw new AssertionError("Expected JSON verification response; body withheld");}
    }
    @Override public void close(){client.shutdown();try{if(!client.awaitTermination(Duration.ofSeconds(10))){client.shutdownNow();if(!client.awaitTermination(Duration.ofSeconds(5))){uncertain.run();throw new AssertionError("HTTP client not terminated; defer cleanup");}}}catch(InterruptedException e){Thread.currentThread().interrupt();uncertain.run();throw new AssertionError("HTTP client shutdown interrupted; defer cleanup");}}
}
