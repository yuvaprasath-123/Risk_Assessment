package com.ofss.risk.upstream;

import com.ofss.internal.InternalServiceVerifier;
import com.ofss.internal.verification.EphemeralCredentials;
import com.ofss.risk.facts.FactsProviderProtocol;
import com.ofss.risk.submission.SubmissionCommand;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static com.ofss.risk.upstream.ConfiguredClientFixtures.*;

/** Temporary authenticated contract providers. Explicitly instantiated only by tests; no production bean. */
public final class ConfiguredClientsProvider implements AutoCloseable {
    public static final String AUDIENCE="configured-upstream-contract-provider";
    final ConfiguredClientFixtures fixture;final HttpServer server;final ExecutorService workers;final InternalServiceVerifier verifier;
    public final AtomicInteger factsCalls=new AtomicInteger(),evidenceCalls=new AtomicInteger();public final AtomicBoolean forbid=new AtomicBoolean();
    public final Set<String> failOnce=ConcurrentHashMap.newKeySet();final Set<String> failed=ConcurrentHashMap.newKeySet();
    final Map<String,List<byte[]>> captured=new ConcurrentHashMap<>();final Map<String,String> requests=new ConcurrentHashMap<>();
    public ConfiguredClientsProvider(ConfiguredClientFixtures fixture,EphemeralCredentials credentials)throws IOException{
        this.fixture=fixture;verifier=new InternalServiceVerifier(true,EphemeralCredentials.ISSUER,AUDIENCE,credentials.publicKeys(),"{\"risk-assessment-service\":[\"RISK_FACTS_READ\",\"RISK_EVIDENCE_READ\"]}",Set.of(FactsProviderProtocol.PERMISSION,EvidenceProviderProtocol.PERMISSION));
        server=HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0),0);workers=Executors.newFixedThreadPool(4,r->new Thread(r,"configured-client-contract-provider"));server.setExecutor(workers);server.createContext("/",this::serve);server.start();
    }
    public URI base(){return URI.create("http://127.0.0.1:"+server.getAddress().getPort());}
    @SuppressWarnings("unchecked") void serve(HttpExchange exchange)throws IOException{
        try(exchange){var path=exchange.getRequestURI().toString();boolean facts=FactsProviderProtocol.PATH.equals(path);if(!facts&&!EvidenceProviderProtocol.PATH.equals(path)){exchange.sendResponseHeaders(404,-1);return;}
            if(facts)factsCalls.incrementAndGet();else evidenceCalls.incrementAndGet();if(forbid.get()){exchange.sendResponseHeaders(503,-1);return;}
            try{var headers=exchange.getRequestHeaders().get("Authorization");if(headers==null||headers.size()!=1||!headers.getFirst().startsWith("Bearer "))throw new IllegalArgumentException();
                var principal=verifier.authenticate(headers.getFirst().substring(7));String permission=facts?FactsProviderProtocol.PERMISSION:EvidenceProviderProtocol.PERMISSION;
                if(principal.getAuthorities().stream().noneMatch(a->a.getAuthority().equals("SERVICE_PERMISSION_"+permission))){exchange.sendResponseHeaders(403,-1);return;}
            }catch(Exception failure){exchange.sendResponseHeaders(401,-1);return;}
            if(!"POST".equals(exchange.getRequestMethod())){exchange.sendResponseHeaders(405,-1);return;}
            byte[] input=exchange.getRequestBody().readNBytes(32769);if(input.length>32768){exchange.sendResponseHeaders(413,-1);return;}var body=(Map<String,Object>)JSON.readValue(input,Map.class);byte[] reply;
            if(facts){if(!body.keySet().equals(Set.of("contractVersion","submission","paymentId","validationReference","validationRevision","cutoff"))||!FactsProviderProtocol.VERSION.equals(body.get("contractVersion")))throw new IllegalArgumentException();
                var identity=object(body,"submission");var command=new SubmissionCommand(SubmissionCommand.VERSION,(String)identity.get("source"),(String)identity.get("key"),(String)body.get("paymentId"),(String)body.get("validationReference"),(String)body.get("validationRevision"),null);
                reply=bytes(fixture.facts(command,Instant.parse((String)body.get("cutoff"))));
            }else{var request=object(body,"request");String key=(String)object(request,"submission").get("key");
                if(failOnce.contains(key)&&failed.add(key)){exchange.sendResponseHeaders(503,-1);return;}
                String replay=(String)body.get("replayReference"),canonical=JSON.writeValueAsString(new TreeMap<>(request));String previous=requests.putIfAbsent(replay,canonical);if(previous!=null&&!previous.equals(canonical))throw new IllegalArgumentException();
                var pages=captured.computeIfAbsent(replay,id->fixture.evidencePages(body,key.equals("incomplete")).stream().map(ConfiguredClientFixtures::bytes).toList());
                int index=((Number)body.get("pageIndex")).intValue();if(index<0||index>=pages.size()||!Objects.equals(body.get("cursor"),index==0?null:"page-"+index)){exchange.sendResponseHeaders(404,-1);return;}reply=pages.get(index);
            }
            exchange.getResponseHeaders().set("Content-Type","application/json; charset=UTF-8");exchange.sendResponseHeaders(200,reply.length);exchange.getResponseBody().write(reply);
        }catch(RuntimeException|IOException failure){/* Test diagnostics never expose tokens, credentials or response payloads. */}
    }
    public boolean terminated(){return workers.isTerminated();}
    @Override public void close(){server.stop(0);workers.shutdownNow();try{if(!workers.awaitTermination(10,TimeUnit.SECONDS))throw new IllegalStateException("Contract provider workers remain");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Contract provider shutdown interrupted");}}
}
