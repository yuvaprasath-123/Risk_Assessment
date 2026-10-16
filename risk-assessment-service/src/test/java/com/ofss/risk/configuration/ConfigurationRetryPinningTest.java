package com.ofss.risk.configuration;

import com.ofss.risk.persistence.*;
import com.ofss.risk.submission.*;
import com.ofss.risk.submission.SubmissionAdapters.*;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class ConfigurationRetryPinningTest {
    @Test void actualOrchestratorNeverRefetchesOrReplacesAnAcceptedSnapshotOnRetry(){
        var http=new ConfigurationHttpHarness();var adapter=new BusinessConfigurationHttpAdapter(new ConfigurationHttpSettings(URI.create("https://configuration.test"),Duration.ofSeconds(1),Duration.ofSeconds(1),65536),()->"header.fixture.signature",http.client);
        var acceptance=mock(RiskAcceptanceService.class);var work=mock(RiskWorkService.class);var freeze=mock(RiskEvidenceService.class);
        var finalization=mock(RiskFinalizationService.class);var snapshots=mock(SubmissionSnapshotReader.class);var reads=mock(AssessmentReadService.class);
        var facts=mock(Facts.class);var evidence=mock(Evidence.class);var tx=mock(PlatformTransactionManager.class);when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var original=SubmissionFixtures.request();var command=SubmissionFixtures.command();var accepted=new AtomicReference<AcceptedAssessment>();var pinned=new AtomicReference<AcceptanceRequest>();
        when(acceptance.find(command.identity())).thenAnswer(i->Optional.ofNullable(accepted.get()));
        when(facts.verify(any(),any(),any())).thenReturn(new VerifiedFacts(original.identity(),original.paymentId(),original.requesterId(),original.validationReference(),original.validationRevision(),original.current(),original.currentAndRetrySubmissionIds()));
        when(acceptance.accept(any())).thenAnswer(i->{AcceptanceRequest request=i.getArgument(0);pinned.set(request);var canon=new CanonicalAcceptance();var c=canon.configuration(request.configuration());var a=canon.acceptance(request,request.configuration().versionId(),request.configuration().rowVersion(),c.hash());
            var stored=new AcceptedAssessment("1",request.identity(),"2",request.configuration().versionId(),request.configuration().rowVersion(),"0","ACCEPTED",request.cutoff(),original.cutoff(),a.hash(),c.hash(),a.json(),c.json());accepted.set(stored);return stored;});
        when(snapshots.restore(any())).thenAnswer(i->pinned.get());when(snapshots.frozenHash(any())).thenReturn("a".repeat(64));
        var lease=new WorkLease("1","test-opaque",1,1,"1",original.cutoff().plusSeconds(100));when(work.claim("1","0")).thenReturn(lease);
        when(finalization.finalizeAssessment(any())).thenAnswer(i->{var s=accepted.get();accepted.set(new AcceptedAssessment(s.assessmentId(),s.identity(),s.configurationSnapshotId(),s.configurationVersionId(),s.configurationRowVersion(),s.rowVersion(),"COMPLETED",s.cutoff(),s.createdAt(),s.acceptanceHash(),s.configurationHash(),s.acceptanceJson(),s.configurationJson()));return null;});
        when(reads.get("1")).thenReturn(SubmissionFixtures.view("COMPLETED"));
        try(var calls=new DependencyCalls(Duration.ofSeconds(2))){
            var service=new RiskSubmissionService(new SubmissionSources("{\"payment-service\":[\"developer1\"]}"),facts,evidence,adapter,acceptance,work,freeze,finalization,snapshots,reads,calls,Clock.fixed(original.cutoff(),ZoneOffset.UTC),tx);
            service.submit("payment-service",command);var first=pinned.get();assertEquals("9007199254740993",first.configuration().versionId());assertEquals("9",first.configuration().rowVersion());
            String hash=first.configuration().contentHash();http.body=ConfigurationHttpFixtures.bytes(ConfigurationHttpFixtures.envelope(ConfigurationHttpFixtures.content("8"),true));
            service.submit("payment-service",command);var explicit=new SubmissionCommand(command.contractVersion(),command.submissionSource(),command.submissionKey(),command.paymentReference(),command.validationReference(),command.validationRevision(),first.configuration().versionId());service.submit("payment-service",explicit);
            assertEquals(1,http.requests.size());assertSame(first,pinned.get());assertEquals(hash,pinned.get().configuration().contentHash());assertEquals(original.cutoff(),pinned.get().cutoff());
            var conflict=new SubmissionCommand(command.contractVersion(),command.submissionSource(),command.submissionKey(),command.paymentReference(),command.validationReference(),command.validationRevision(),"8");
            assertEquals(SubmissionFailure.Code.SUBMISSION_CONFLICT,assertThrows(SubmissionFailure.class,()->service.submit("payment-service",conflict)).code());assertEquals(1,http.requests.size());
            verify(facts,times(1)).verify(any(),any(),any());verify(finalization,times(1)).finalizeAssessment(any());verifyNoInteractions(evidence,freeze);
        }
    }
}
