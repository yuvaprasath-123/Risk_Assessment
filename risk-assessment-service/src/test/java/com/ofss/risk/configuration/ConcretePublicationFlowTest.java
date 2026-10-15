package com.ofss.risk.configuration;

import com.ofss.configuration.BusinessConfigurationApplication;
import com.ofss.configuration.activation.*;
import com.ofss.configuration.band.*;
import com.ofss.configuration.publication.*;
import com.ofss.configuration.readiness.*;
import com.ofss.configuration.rule.*;
import com.ofss.configuration.sla.*;
import com.ofss.configuration.threshold.*;
import com.ofss.configuration.version.ConfigurationVersion;
import com.ofss.internal.ServicePermissions;
import com.ofss.internal.verification.EphemeralCredentials;
import com.ofss.risk.submission.PublishedConfigurationMapping;
import java.math.*;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.ofss.risk.configuration.ConfigurationHttpFixtures.*;

/** Real readiness, publication, activation, Spring transactions, HTTP security, snapshot decoder and engine mapper.
 * Only persistence/connection are mocked. This is not Oracle or genuine issuer interoperability proof. */
@SpringBootTest(classes=BusinessConfigurationApplication.class, webEnvironment=SpringBootTest.WebEnvironment.MOCK,
    properties={"spring.datasource.username=mock-fixture","spring.datasource.password="})
@AutoConfigureMockMvc
class ConcretePublicationFlowTest {
    static final EphemeralCredentials credentials=new EphemeralCredentials();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("security.jwt.secret",credentials::userSecret);r.add("internal-access.enabled",()->true);
        r.add("internal-access.issuer",()->EphemeralCredentials.ISSUER);r.add("internal-access.audience",()->"business-configuration-service");
        r.add("internal-access.public-keys",credentials::publicKeys);
        r.add("internal-access.service-permissions",()->"{\"risk-assessment-service\":[\"CONFIGURATION_ACTIVE_READ\",\"CONFIGURATION_VERSION_READ\"]}");
    }
    @Autowired MockMvc mvc;
    @Autowired PublicationReadinessValidator validator;
    @MockitoBean DataSource source;
    @MockitoBean DraftRiskRuleRepository versions;
    @MockitoBean PublicationReadinessRuleRepository rules;
    @MockitoBean DraftRiskBandRepository bands;
    @MockitoBean DraftApprovalThresholdRepository thresholds;
    @MockitoBean DraftApprovalSlaRepository slas;
    @MockitoBean ConfigurationPublicationRepository publication;
    @MockitoBean ConfigurationActivationRepository activation;
    final AtomicReference<ConfigurationVersionDetails> parent=new AtomicReference<>();
    final AtomicReference<ConfigurationPublicationRepository.Metadata> metadata=new AtomicReference<>();
    final AtomicReference<ConfigurationActivationRepository.Pointer> pointer=new AtomicReference<>();
    Connection connection;
    @BeforeEach void setup() throws Exception {
        assertFalse(org.mockito.Mockito.mockingDetails(validator).isMock());
        connection=mock(Connection.class);when(source.getConnection()).thenReturn(connection);when(connection.getAutoCommit()).thenReturn(true);
        pointer.set(null);metadata.set(new ConfigurationPublicationRepository.Metadata(42,null,null,null));
        parent.set(new ConfigurationVersionDetails(new ConfigurationVersion(7,1,"Approved demonstration fixture","DRAFT",true,
            OffsetDateTime.parse("2026-01-01T00:00:00Z"),null),null,"CAP_AT_MAX",100,8));
        var root=content("7");
        var rr=rows(root,"riskRules").stream().map(r->new RiskRule(id(r,"ruleConfigId"),7,(String)r.get("ruleCode"),(String)r.get("displayName"),(Boolean)r.get("enabled"),
            n(r,"weight"),(String)r.get("parameterSchemaVersion"),object(r.get("parameters")),n(r,"evaluationOrder"))).toList();
        var bb=rows(root,"riskBands").stream().map(b->new RiskBand(id(b,"bandConfigId"),7,(String)b.get("bandCode"),n(b,"minScoreInclusive"),b.get("maxScoreInclusive")==null?null:n(b,"maxScoreInclusive"),n(b,"approvalLevel"))).toList();
        var tt=rows(root,"approvalThresholds").stream().map(t->new ApprovalThreshold(id(t,"thresholdConfigId"),7,n(t,"approvalLevel"),new BigDecimal((String)t.get("minAmountInclusive")),t.get("maxAmountExclusive")==null?null:new BigDecimal((String)t.get("maxAmountExclusive")))).toList();
        var ss=rows(root,"approvalSlas").stream().map(s->new ApprovalSla(id(s,"slaConfigId"),7,n(s,"approvalLevel"),new BigInteger(s.get("durationSeconds").toString()).longValueExact(),(String)s.get("startEventCode"),(String)s.get("escalationPolicyCode"),(String)s.get("terminalLevelPolicyCode"))).toList();
        when(versions.findVersion(eq(7L),anyBoolean())).thenAnswer(i->Optional.of(parent.get()));
        when(versions.listRules(7)).thenReturn(rr);when(rules.list(7)).thenReturn(rr);when(bands.list(7)).thenReturn(bb);when(thresholds.list(7)).thenReturn(tt);when(slas.list(7)).thenReturn(ss);
        when(publication.metadata(7)).thenAnswer(i->metadata.get());
        doAnswer(i->{assertTrue(TransactionSynchronizationManager.isActualTransactionActive());var old=parent.get();var at=i.getArgument(3,OffsetDateTime.class);
            parent.set(new ConfigurationVersionDetails(new ConfigurationVersion(7,1,old.version().versionLabel(),"PUBLISHED",true,old.version().createdAt(),at),null,"CAP_AT_MAX",100,9));
            metadata.set(new ConfigurationPublicationRepository.Metadata(42,at,i.getArgument(2,Long.class),i.getArgument(4,String.class)));return null;
        }).when(publication).publish(eq(7L),eq(8L),eq(42L),any(),anyString());
        when(activation.pointer()).thenAnswer(i->Optional.ofNullable(pointer.get()));
        when(activation.metadata(7)).thenAnswer(i->{var m=metadata.get();return new ConfigurationActivationRepository.Metadata(42,m.publishedAt(),m.publishedByUserId(),m.contentHash());});
        doAnswer(i->{assertTrue(TransactionSynchronizationManager.isActualTransactionActive());pointer.set(new ConfigurationActivationRepository.Pointer(7,i.getArgument(3),42,0));return null;})
            .when(activation).save(isNull(),eq(7L),eq(42L),any());
    }
    @SuppressWarnings("unchecked") static Map<String,Object> object(Object v){return (Map<String,Object>)v;}
    static int n(Map<String,Object> v,String k){return new BigInteger(v.get(k).toString()).intValueExact();}
    static long id(Map<String,Object> v,String k){return Long.parseLong((String)v.get(k));}
    void postManager(String path,String body) throws Exception {mvc.perform(post(path).header("Authorization","Bearer "+credentials.user()).contentType("application/json").content(body)).andExpect(status().isOk());}
    @Test void realReadyDraftPublishesActivatesAndIsAuthenticatedMappedAndImmutable() throws Exception {
        mvc.perform(get("/api/v1/configuration/versions/7/publication-readiness").header("Authorization","Bearer "+credentials.user()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.ready").value(true)).andExpect(jsonPath("$.issues").isEmpty());
        postManager("/api/v1/configuration/versions/7/publish","{\"expectedRowVersion\":8}");
        assertEquals("PUBLISHED",parent.get().version().publicationState());assertEquals(9,parent.get().rowVersion());
        postManager("/api/v1/configuration/versions/7/activate","{\"expectedPointerRowVersion\":null}");
        String token=credentials.service("business-configuration-service","risk-assessment-service",List.of(ServicePermissions.CONFIG_ACTIVE,ServicePermissions.CONFIG_VERSION));
        for(String route:List.of("active","versions/7")){
            var bytes=mvc.perform(get("/internal/v1/configuration/"+route).header("Authorization","Bearer "+token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
            var snapshot=new PublishedSnapshotResponse().decode(bytes,route.equals("active")?null:"7");
            assertEquals("configuration-content-v2",snapshot.hashFormat());
            assertTrue(snapshot.canonicalConfiguration().contains("\"approvalPolicy\""));
            assertTrue(snapshot.canonicalConfiguration().contains("\"backupRequiresSameLevelAuthorization\":true"));
            var engine=new PublishedConfigurationMapping().map(snapshot);assertEquals(metadata.get().contentHash(),engine.contentHash());assertEquals("9",engine.rowVersion());
            assertEquals(7,engine.rules().size());assertEquals(3,engine.bands().size());assertEquals(new BigDecimal("123.00"),engine.approvalBoundaries().get(2));
            var corrupt=object(JSON.readValue(bytes,Map.class));corrupt.put("contentHash","0".repeat(64));
            assertThrows(com.ofss.risk.submission.SubmissionFailure.class,()->new PublishedSnapshotResponse().decode(bytes(corrupt),route.equals("active")?null:"7"));
        }
        var before=metadata.get();
        mvc.perform(put("/api/v1/configuration/versions/7/approval-slas/1").header("Authorization","Bearer "+credentials.user()).contentType("application/json")
            .content("{\"durationSeconds\":1,\"startEventCode\":\"APPROVAL_PENDING_V1\",\"escalationPolicyCode\":\"NEXT_LEVEL_V1\",\"terminalLevelPolicyCode\":null,\"expectedRowVersion\":9}"))
            .andExpect(status().isConflict());
        assertEquals(before,metadata.get());verify(slas,never()).save(anyLong(),anyInt(),any(),any());
        mvc.perform(post("/api/v1/configuration/versions/7/publish").header("Authorization","Bearer "+credentials.user()).contentType("application/json").content("{\"expectedRowVersion\":9}"))
            .andExpect(status().isConflict());
        verify(connection,atLeast(2)).commit();verify(connection,atLeastOnce()).rollback();
    }
    @Test void staleDraftCannotPublishAndInvalidPolicyCannotBeApproved() throws Exception {
        mvc.perform(post("/api/v1/configuration/versions/7/publish").header("Authorization","Bearer "+credentials.user()).contentType("application/json").content("{\"expectedRowVersion\":7}"))
            .andExpect(status().isConflict());
        when(slas.list(7)).thenReturn(List.of(new ApprovalSla(1,7,1,86400,"APPROVAL_PENDING_V2","NEXT_LEVEL_V1",null)));
        mvc.perform(post("/api/v1/configuration/versions/7/publish").header("Authorization","Bearer "+credentials.user()).contentType("application/json").content("{\"expectedRowVersion\":8}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFIGURATION_NOT_READY"));
        assertEquals("DRAFT",parent.get().version().publicationState());assertNull(metadata.get().contentHash());
        verify(publication,never()).publish(anyLong(),anyLong(),anyLong(),any(),any());verify(connection,never()).commit();verify(connection,times(2)).rollback();
    }
}
