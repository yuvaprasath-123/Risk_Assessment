package com.ofss.risk.upstream;

import com.ofss.risk.facts.FactsHttpSettings;
import com.ofss.risk.persistence.AcceptanceRequest;
import com.ofss.risk.provider.ProviderHttpTransport;
import com.ofss.risk.submission.*;
import com.ofss.risk.submission.SubmissionAdapters.*;
import java.net.URI;
import java.time.Duration;
import org.springframework.core.env.Environment;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Only upstream payment/supplier dependencies; prior risk history is deliberately absent. */
public final class UpstreamEvidenceHttpAdapter implements UpstreamEvidence,AutoCloseable {
    private final EvidenceHttpSettings settings;private final ProviderHttpTransport transport;
    public UpstreamEvidenceHttpAdapter(EvidenceHttpSettings settings,ProviderHttpTransport.ServiceCredential credential){
        this.settings=settings;transport=new ProviderHttpTransport(settings.transport(),credential);
    }
    public static UpstreamEvidence configured(Environment env){
        EvidenceHttpSettings settings;ProviderHttpTransport.ServiceCredential credential;
        try {
            if(!env.getProperty("risk-evidence-client.enabled",Boolean.class,false))return (accepted,deadline)->{throw SubmissionAdapters.unavailable();};
            settings=new EvidenceHttpSettings(new FactsHttpSettings(URI.create(env.getRequiredProperty("risk-evidence-client.base-url")),env.getRequiredProperty("risk-evidence-client.provider-service-id"),
                Duration.parse(env.getRequiredProperty("risk-evidence-client.connect-timeout")),Duration.parse(env.getRequiredProperty("risk-evidence-client.header-timeout")),
                Duration.parse(env.getRequiredProperty("risk-evidence-client.body-timeout")),Integer.parseInt(env.getRequiredProperty("risk-evidence-client.max-response-bytes"))),
                Integer.parseInt(env.getRequiredProperty("risk-evidence-client.page-size")),Integer.parseInt(env.getRequiredProperty("risk-evidence-client.max-pages")),Integer.parseInt(env.getRequiredProperty("risk-evidence-client.max-records")));
            credential=()->env.getProperty("risk-evidence-client.service-token","");ProviderHttpTransport.token(credential);
        }catch(RuntimeException failure){throw EvidenceHttpSettings.invalid();}
        try{return new UpstreamEvidenceHttpAdapter(settings,credential);}
        catch(RuntimeException failure){throw new IllegalStateException("Upstream evidence HTTP transport initialization failed");}
    }
    @Override public UpstreamBundle retrieve(AcceptanceRequest accepted,Deadline deadline){
        try {
            if(TransactionSynchronizationManager.isActualTransactionActive()||deadline==null||deadline.remainingNanos()==0)throw EvidenceProviderProtocol.failure();
            var protocol=new EvidenceProviderProtocol(accepted,settings.transport().providerServiceId(),settings.pageSize());var collector=protocol.collector();
            do {
                byte[] bytes;
                try{bytes=transport.post(EvidenceProviderProtocol.PATH,protocol.request(collector.nextIndex(),collector.cursor()),deadline,true);}
                catch(SubmissionFailure failure){
                    if(collector.nextIndex()>0&&failure.code()==SubmissionFailure.Code.DEPENDENCY_UNAVAILABLE)return collector.finish(true);
                    throw failure;
                }
                collector.accept(bytes,settings.maxPages(),settings.maxRecords());
            }while(collector.more());
            if(deadline.remainingNanos()==0)throw EvidenceProviderProtocol.failure();return collector.finish(false);
        }catch(SubmissionFailure failure){throw failure;}catch(RuntimeException failure){throw EvidenceProviderProtocol.failure();}
    }
    @Override public void close(){transport.close();}
    @Override public String toString(){return "Upstream evidence HTTP adapter [credentials withheld]";}
}
