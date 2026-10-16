package com.ofss.risk.configuration;

import com.ofss.risk.submission.*;
import com.ofss.risk.submission.SubmissionAdapters.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Real read transport only: external service JWT, bounded complete body, no redirects/fallback/caller transactions. */
public final class BusinessConfigurationHttpAdapter implements PublishedConfiguration,AutoCloseable {
    private final ConfigurationHttpSettings settings;private final ConfigurationServiceTokenProvider credentials;private final HttpClient client;
    private final PublishedSnapshotResponse decoder=new PublishedSnapshotResponse();
    public BusinessConfigurationHttpAdapter(ConfigurationHttpSettings settings,ConfigurationServiceTokenProvider credentials){this(settings,credentials,transport(settings));}
    BusinessConfigurationHttpAdapter(ConfigurationHttpSettings settings,ConfigurationServiceTokenProvider credentials,HttpClient client){this.settings=Objects.requireNonNull(settings);this.credentials=Objects.requireNonNull(credentials);this.client=Objects.requireNonNull(client);}
    static HttpClient transport(ConfigurationHttpSettings settings){return HttpClient.newBuilder().connectTimeout(settings.connectTimeout()).followRedirects(HttpClient.Redirect.NEVER).build();}
    public static PublishedConfiguration configured(Environment env){
        ConfigurationHttpSettings settings;ConfigurationServiceTokenProvider credentials;
        try {
            if(!env.getProperty("risk-configuration-client.enabled",Boolean.class,false))return (version,deadline)->{throw SubmissionAdapters.unavailable();};
            settings=new ConfigurationHttpSettings(URI.create(env.getRequiredProperty("risk-configuration-client.base-url")),
                Duration.parse(env.getRequiredProperty("risk-configuration-client.connect-timeout")),Duration.parse(env.getRequiredProperty("risk-configuration-client.request-timeout")),
                Integer.parseInt(env.getRequiredProperty("risk-configuration-client.max-response-bytes")));
            credentials=()->env.getProperty("risk-configuration-client.service-token","");
            credential(credentials); // Explicit enabled-but-incomplete configuration fails startup without key/token details.
        }catch(RuntimeException e){throw ConfigurationHttpSettings.invalid();}
        // Native selector/socket failures are operational failures, not invalid credential/settings errors.
        try{return new BusinessConfigurationHttpAdapter(settings,credentials);}
        catch(RuntimeException e){throw new IllegalStateException("Configuration HTTP transport initialization failed");}
    }
    @Override public PublishedSnapshot resolve(String explicitVersionId,Deadline deadline){
        CompletableFuture<HttpResponse<byte[]>> response=null;
        try {
            if(TransactionSynchronizationManager.isActualTransactionActive() || deadline==null)throw failure();
            if(explicitVersionId!=null)SubmissionCommand.id(explicitVersionId);
            long remaining=Math.min(settings.requestTimeout().toNanos(),deadline.remainingNanos());if(remaining<=0)throw failure();
            String path=explicitVersionId==null?"/internal/v1/configuration/active":"/internal/v1/configuration/versions/"+explicitVersionId;
            var request=HttpRequest.newBuilder(settings.origin().resolve(path)).timeout(Duration.ofNanos(remaining))
                .header("Accept","application/json").header("Accept-Encoding","identity")
                .header("Authorization","Bearer "+credential(credentials)).GET().build();
            response=client.sendAsync(request,bodyHandler());
            var received=response.get(remaining,TimeUnit.NANOSECONDS);
            if(received.statusCode()!=200 || received.body()==null || received.body().length>settings.maxResponseBytes())throw failure();
            var snapshot=decoder.decode(received.body(),explicitVersionId);
            if(deadline.remainingNanos()==0)throw failure();return snapshot;
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw failure();}
        catch(SubmissionFailure e){if(e.code()==SubmissionFailure.Code.ADAPTER_UNAVAILABLE)throw e;throw failure();}
        catch(Exception e){throw failure();}
        finally{if(response!=null&&!response.isDone())response.cancel(true);}
    }
    private static String credential(ConfigurationServiceTokenProvider provider){
        try{String value=provider.token();if(value==null||value.length()>16384||!value.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"))throw new IllegalArgumentException();return value;}
        catch(RuntimeException e){throw SubmissionAdapters.unavailable();}
    }
    HttpResponse.BodyHandler<byte[]> bodyHandler(){return info->{
        boolean acceptable=info.statusCode()==200;long declared=-1;
        try {
            var types=info.headers().allValues("Content-Type");if(types.size()!=1)throw new IllegalArgumentException();
            var type=MediaType.parseMediaType(types.getFirst());
            if(!"application".equalsIgnoreCase(type.getType())||!"json".equalsIgnoreCase(type.getSubtype())
                ||type.getCharset()!=null&&!StandardCharsets.UTF_8.equals(type.getCharset()))throw new IllegalArgumentException();
            var encoding=info.headers().allValues("Content-Encoding");if(!encoding.isEmpty()&&!(encoding.size()==1&&"identity".equalsIgnoreCase(encoding.getFirst())))throw new IllegalArgumentException();
            var lengths=info.headers().allValues("Content-Length");if(lengths.size()>1)throw new IllegalArgumentException();
            if(!lengths.isEmpty()){if(!lengths.getFirst().matches("[0-9]+"))throw new IllegalArgumentException();declared=Long.parseLong(lengths.getFirst());}
        }catch(RuntimeException e){acceptable=false;}
        return new LimitedConfigurationBody(settings.maxResponseBytes(),declared,acceptable);
    };}
    private static SubmissionFailure failure(){return new SubmissionFailure(SubmissionFailure.Code.DEPENDENCY_UNAVAILABLE);}
    @Override public void close(){client.shutdownNow();try{if(!client.awaitTermination(Duration.ofSeconds(5)))throw new IllegalStateException("Configuration HTTP client did not terminate");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException("Configuration HTTP client shutdown interrupted");}}
    @Override public String toString(){return "Business Configuration HTTP adapter [credentials withheld]";}
}
