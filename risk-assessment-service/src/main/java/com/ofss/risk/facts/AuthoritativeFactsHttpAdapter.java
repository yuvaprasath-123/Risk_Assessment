package com.ofss.risk.facts;

import com.ofss.risk.provider.ProviderHttpTransport;
import com.ofss.risk.submission.*;
import com.ofss.risk.submission.SubmissionAdapters.*;
import java.net.URI;
import java.time.*;
import org.springframework.core.env.Environment;

/** Read-only authoritative resolution. Shares bounded native transport with upstream evidence. */
public final class AuthoritativeFactsHttpAdapter implements Facts,AutoCloseable {
    @FunctionalInterface public interface ServiceCredential {String token();}
    private final FactsHttpSettings settings;private final ProviderHttpTransport transport;
    private final FactsProviderProtocol protocol=new FactsProviderProtocol();
    public AuthoritativeFactsHttpAdapter(FactsHttpSettings settings,ServiceCredential credential){
        this.settings=settings;transport=new ProviderHttpTransport(settings,credential::token);
    }
    public static Facts configured(Environment env){
        FactsHttpSettings settings;ServiceCredential credential;
        try {
            if(!env.getProperty("risk-facts-client.enabled",Boolean.class,false))return (command,cutoff,deadline)->{throw SubmissionAdapters.unavailable();};
            settings=new FactsHttpSettings(URI.create(env.getRequiredProperty("risk-facts-client.base-url")),env.getRequiredProperty("risk-facts-client.provider-service-id"),
                Duration.parse(env.getRequiredProperty("risk-facts-client.connect-timeout")),Duration.parse(env.getRequiredProperty("risk-facts-client.header-timeout")),
                Duration.parse(env.getRequiredProperty("risk-facts-client.body-timeout")),Integer.parseInt(env.getRequiredProperty("risk-facts-client.max-response-bytes")));
            credential=()->env.getProperty("risk-facts-client.service-token","");ProviderHttpTransport.token(credential::token);
        }catch(RuntimeException exception){throw FactsHttpSettings.invalid();}
        try{return new AuthoritativeFactsHttpAdapter(settings,credential);}
        catch(RuntimeException exception){throw new IllegalStateException("Facts HTTP transport initialization failed");}
    }
    @Override public VerifiedFacts verify(SubmissionCommand command,Instant cutoff,Deadline deadline){
        var facts=protocol.decode(transport.post(FactsProviderProtocol.PATH,protocol.request(command,cutoff),deadline,false),command,cutoff,settings.providerServiceId());
        if(deadline.remainingNanos()==0)throw new SubmissionFailure(SubmissionFailure.Code.DEPENDENCY_UNAVAILABLE);return facts;
    }
    @Override public void close(){transport.close();}
    @Override public String toString(){return "Authoritative Facts HTTP adapter [credentials withheld]";}
}
