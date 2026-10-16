package com.ofss.risk.upstream;

import com.ofss.internal.verification.EphemeralCredentials;
import com.ofss.risk.facts.*;
import com.ofss.risk.persistence.SubmissionFixtures;
import com.ofss.risk.submission.*;
import com.ofss.risk.submission.SubmissionAdapters.*;
import com.ofss.risk.engine.RiskInput.Availability;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Proves prepared provider fixtures satisfy actual native clients, without Oracle or either application receiver. */
class ConfiguredClientsProviderOfflineTest {
    Deadline deadline(){return new Deadline(System.nanoTime()+Duration.ofSeconds(10).toNanos());}
    @Test void actualPreparedProvidersSupportExactClientsPaginationAndImmutableRecovery()throws Exception{
        var credentials=new EphemeralCredentials();var fixture=new ConfiguredClientFixtures("d2-clients-00000000-0000-0000-0000-000000000001");
        try(var provider=new ConfiguredClientsProvider(fixture,credentials)){
            var time=Duration.ofSeconds(2);var token=credentials.service(ConfiguredClientsProvider.AUDIENCE,"risk-assessment-service",List.of(FactsProviderProtocol.PERMISSION,EvidenceProviderProtocol.PERMISSION));
            try(var facts=new AuthoritativeFactsHttpAdapter(new FactsHttpSettings(provider.base(),ConfiguredClientFixtures.FACTS,time,time,time,1048576),()->token);
                var evidence=new UpstreamEvidenceHttpAdapter(new EvidenceHttpSettings(new FactsHttpSettings(provider.base(),ConfiguredClientFixtures.EVIDENCE,time,time,time,1048576),2,10,100),()->token)){
                var cutoff=Instant.parse("2026-10-16T00:00:00.123456789Z");var verified=facts.verify(fixture.command("dependency"),cutoff,deadline());var accepted=verified.acceptance(cutoff,SubmissionFixtures.request().configuration());
                provider.failOnce.add("dependency");assertEquals(SubmissionFailure.Code.DEPENDENCY_UNAVAILABLE,assertThrows(SubmissionFailure.class,()->evidence.retrieve(accepted,deadline())).code());
                var first=evidence.retrieve(accepted,deadline());var replay=evidence.retrieve(accepted,deadline());assertEquals(first,replay);assertEquals(6,first.payments().records().size());assertEquals(Availability.COMPLETE,first.payments().availability());assertEquals(cutoff,first.payments().cutoff());
                assertEquals(1,provider.factsCalls.get());assertEquals(7,provider.evidenceCalls.get());assertTrue(first.payments().records().stream().anyMatch(p->p.amount().toPlainString().equals("40.01")));
                var other=facts.verify(fixture.command("retry"),cutoff.plusSeconds(1),deadline()).acceptance(cutoff.plusSeconds(1),accepted.configuration());var shared=evidence.retrieve(other,deadline());
                assertEquals(first.payments().records(),shared.payments().records());assertEquals(first.transitions().records(),shared.transitions().records());
                assertNotEquals(accepted.paymentId(),other.paymentId());assertNotEquals(accepted.current().invoiceId(),other.current().invoiceId());
                var incomplete=facts.verify(fixture.command("incomplete"),cutoff,deadline()).acceptance(cutoff,accepted.configuration());assertEquals(Availability.INCOMPLETE,evidence.retrieve(incomplete,deadline()).payments().availability());
            }
        }
    }
    @Test void preparedProviderReallyDeniesWrongAudienceAndUserTokens()throws Exception{
        var keys=new EphemeralCredentials();var fixture=new ConfiguredClientFixtures("d2-clients-00000000-0000-0000-0000-000000000002");
        try(var provider=new ConfiguredClientsProvider(fixture,keys)){var timeout=Duration.ofSeconds(2);for(String value:List.of(keys.user(),keys.service("wrong","risk-assessment-service",List.of(FactsProviderProtocol.PERMISSION)))){
            try(var adapter=new AuthoritativeFactsHttpAdapter(new FactsHttpSettings(provider.base(),ConfiguredClientFixtures.FACTS,timeout,timeout,timeout,65536),()->value)){
                assertEquals(SubmissionFailure.Code.DEPENDENCY_UNAVAILABLE,assertThrows(SubmissionFailure.class,()->adapter.verify(fixture.command("retry"),Instant.now(),deadline())).code());
            }
        }}
    }
}
