package com.ofss.risk.facts;

import com.ofss.risk.configuration.LimitedConfigurationBody;
import com.ofss.risk.submission.*;
import com.ofss.risk.submission.SubmissionAdapters.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Read-only authoritative resolution at one trusted provider. No issuance, impersonation, redirects or fallback. */
public final class AuthoritativeFactsHttpAdapter implements Facts,AutoCloseable {
    @FunctionalInterface public interface ServiceCredential {String token();}
    private final FactsHttpSettings settings;private final ServiceCredential credential;private final HttpClient client;
    private final FactsProviderProtocol protocol=new FactsProviderProtocol();
    public AuthoritativeFactsHttpAdapter(FactsHttpSettings settings,ServiceCredential credential){
        this.settings=Objects.requireNonNull(settings);this.credential=Objects.requireNonNull(credential);
        client=HttpClient.newBuilder().connectTimeout(settings.connectTimeout()).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public static Facts configured(Environment env){
        FactsHttpSettings settings;ServiceCredential credential;
        try {
            if(!env.getProperty("risk-facts-client.enabled",Boolean.class,false))return (command,cutoff,deadline)->{throw SubmissionAdapters.unavailable();};
            settings=new FactsHttpSettings(URI.create(env.getRequiredProperty("risk-facts-client.base-url")),env.getRequiredProperty("risk-facts-client.provider-service-id"),
                Duration.parse(env.getRequiredProperty("risk-facts-client.connect-timeout")),Duration.parse(env.getRequiredProperty("risk-facts-client.header-timeout")),
                Duration.parse(env.getRequiredProperty("risk-facts-client.body-timeout")),Integer.parseInt(env.getRequiredProperty("risk-facts-client.max-response-bytes")));
            credential=()->env.getProperty("risk-facts-client.service-token","");token(credential);
        }catch(RuntimeException exception){throw FactsHttpSettings.invalid();}
        try{return new AuthoritativeFactsHttpAdapter(settings,credential);}
        catch(RuntimeException exception){throw new IllegalStateException("Facts HTTP transport initialization failed");}
    }
    @Override public VerifiedFacts verify(SubmissionCommand command,Instant cutoff,Deadline deadline){
        CompletableFuture<HttpResponse<byte[]>> response=null;
        try {
            if(TransactionSynchronizationManager.isActualTransactionActive()||deadline==null||deadline.remainingNanos()==0)throw failure();
            byte[] body=protocol.request(command,cutoff);String token=token(credential);
            long headerWait=Math.min(settings.headerTimeout().toNanos(),deadline.remainingNanos());if(headerWait<=0)throw failure();
            var headers=new CompletableFuture<Long>();
            var request=HttpRequest.newBuilder(settings.origin().resolve(FactsProviderProtocol.PATH))
                .timeout(Duration.ofNanos(deadline.remainingNanos())).header("Accept","application/json").header("Accept-Encoding","identity")
                .header("Content-Type","application/json; charset=UTF-8").header("Authorization","Bearer "+token)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            response=client.sendAsync(request,info->{long at=System.nanoTime();headers.complete(at);return body(info);});
            response.whenComplete((value,error)->{if(error!=null)headers.completeExceptionally(new IllegalStateException("Facts transport failed"));});
            long headerAt=headers.get(headerWait,TimeUnit.NANOSECONDS);
            long bodyWait=Math.min(settings.bodyTimeout().toNanos()-Math.max(0,System.nanoTime()-headerAt),deadline.remainingNanos());
            if(bodyWait<=0)throw failure();var received=response.get(bodyWait,TimeUnit.NANOSECONDS);
            if(received.statusCode()!=200||received.body()==null||received.body().length>settings.maxResponseBytes())throw failure();
            var facts=protocol.decode(received.body(),command,cutoff,settings.providerServiceId());
            if(deadline.remainingNanos()==0)throw failure();return facts;
        }catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw failure();}
        catch(SubmissionFailure exception){throw exception;}
        catch(Exception exception){throw failure();}
        finally{if(response!=null&&!response.isDone())response.cancel(true);}
    }
    private HttpResponse.BodySubscriber<byte[]> body(HttpResponse.ResponseInfo info){
        boolean acceptable=info.statusCode()==200;long declared=-1;
        try {
            var types=info.headers().allValues("Content-Type");if(types.size()!=1)throw new IllegalArgumentException();
            var type=MediaType.parseMediaType(types.getFirst());
            if(!"application".equalsIgnoreCase(type.getType())||!"json".equalsIgnoreCase(type.getSubtype())||type.getCharset()!=null&&!StandardCharsets.UTF_8.equals(type.getCharset()))throw new IllegalArgumentException();
            var encoding=info.headers().allValues("Content-Encoding");if(!encoding.isEmpty()&&!(encoding.size()==1&&"identity".equalsIgnoreCase(encoding.getFirst())))throw new IllegalArgumentException();
            var lengths=info.headers().allValues("Content-Length");if(lengths.size()>1)throw new IllegalArgumentException();
            if(!lengths.isEmpty()){if(!lengths.getFirst().matches("[0-9]+"))throw new IllegalArgumentException();declared=Long.parseLong(lengths.getFirst());}
        }catch(RuntimeException exception){acceptable=false;}
        return new LimitedConfigurationBody(settings.maxResponseBytes(),declared,acceptable);
    }
    private static String token(ServiceCredential credential){
        try{String token=credential.token();if(token==null||token.length()>16384||!token.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"))throw new IllegalArgumentException();return token;}
        catch(RuntimeException exception){throw SubmissionAdapters.unavailable();}
    }
    private static SubmissionFailure failure(){return new SubmissionFailure(SubmissionFailure.Code.DEPENDENCY_UNAVAILABLE);}
    @Override public void close(){client.shutdownNow();try{if(!client.awaitTermination(Duration.ofSeconds(5)))throw new IllegalStateException("Facts HTTP client did not terminate");}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException("Facts HTTP client shutdown interrupted");}}
    @Override public String toString(){return "Authoritative Facts HTTP adapter [credentials withheld]";}
}
