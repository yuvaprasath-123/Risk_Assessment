package com.ofss.risk.configuration;

import com.ofss.configuration.BusinessConfigurationApplication;
import com.ofss.configuration.activation.*;
import com.ofss.configuration.band.*;
import com.ofss.configuration.publication.*;
import com.ofss.configuration.readiness.*;
import com.ofss.configuration.rule.*;
import com.ofss.configuration.sla.*;
import com.ofss.configuration.threshold.*;
import com.ofss.configuration.version.*;
import com.ofss.internal.ServicePermissions;
import com.ofss.internal.verification.EphemeralCredentials;
import com.ofss.risk.submission.PublishedConfigurationMapping;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.ofss.risk.configuration.ConfigurationHttpFixtures.*;

/** USER-RUN ONLY. Real readiness/publication/activation and Oracle, MockMvc (NO listener/app server).
 * Committed synthetic fixtures; refuses an existing current pointer; requires exclusive test use.
 * Ephemeral test issuer is NOT genuine cross-team interoperability. */
@EnabledIfEnvironmentVariable(named="CONFIG_VERIFY_ORACLE_CONCRETE_PUBLICATION",matches="true")
@EnabledIfEnvironmentVariable(named="CONFIG_ORACLE_PUBLICATION_ISOLATED",matches="true")
@SpringBootTest(classes=BusinessConfigurationApplication.class,webEnvironment=SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS) @Execution(ExecutionMode.SAME_THREAD)
class OracleConcretePublicationFlowTest {
    static final EphemeralCredentials credentials=new EphemeralCredentials();
    static final String DESCRIPTION="Synthetic real-readiness publication fixture only";
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",()->"jdbc:oracle:thin:@//localhost:1521/FREEPDB1");
        r.add("spring.datasource.username",()->"D2_CONFIGURATION");
        r.add("spring.datasource.password",()->{var p=System.getenv("CONFIG_DB_PASSWORD");if(p==null||p.isBlank())throw new IllegalStateException("External CONFIG_DB_PASSWORD required");return p;});
        r.add("spring.sql.init.mode",()->"never");r.add("spring.jpa.hibernate.ddl-auto",()->"none");
        r.add("spring.flyway.enabled",()->false);r.add("spring.liquibase.enabled",()->false);
        r.add("security.jwt.secret",credentials::userSecret);r.add("internal-access.enabled",()->true);
        r.add("internal-access.issuer",()->EphemeralCredentials.ISSUER);r.add("internal-access.audience",()->"business-configuration-service");
        r.add("internal-access.public-keys",credentials::publicKeys);
        r.add("internal-access.service-permissions",()->"{\"risk-assessment-service\":[\"CONFIGURATION_ACTIVE_READ\",\"CONFIGURATION_VERSION_READ\"]}");
    }
    @Autowired MockMvc mvc;@Autowired DataSource source;@Autowired JdbcTemplate jdbc;@Autowired JdbcTransactionManager manager;
    @Autowired ConfigurationVersionService create;@Autowired DraftRiskRuleService rules;@Autowired DraftRiskBandService bands;
    @Autowired DraftApprovalThresholdService thresholds;@Autowired DraftApprovalSlaService slas;
    @Autowired DraftRiskRuleRepository versions;@Autowired PublicationReadinessRuleRepository ruleRows;
    @Autowired DraftRiskBandRepository bandRows;@Autowired DraftApprovalThresholdRepository tierRows;@Autowired DraftApprovalSlaRepository slaRows;
    @Autowired PublicationReadinessService readiness;@Autowired PublicationReadinessValidator validator;
    @Autowired ConfigurationPublicationService publish;@Autowired ConfigurationContentHash hashes;
    @Autowired ConfigurationActivationService active;
    String namespace;long id,revision;boolean workersStopped=true,guarded=false;
    static void requireWorkersStopped(boolean stopped){
        if(!stopped)throw new IllegalStateException("Previous fixture workers remain; no new fixture or cleanup permitted");
    }
    @BeforeEach void fixture(){
        requireWorkersStopped(workersStopped); // Never erase a previous failure before creating another fixture.
        guarded=false;id=0;workersStopped=true;namespace="D2_CFG_READY_"+UUID.randomUUID();
        var identity=jdbc.queryForMap("SELECT SYS_CONTEXT('USERENV','CON_NAME') PDB,SYS_CONTEXT('USERENV','SESSION_USER') OWNER_NAME FROM DUAL");
        assertEquals("FREEPDB1",identity.get("PDB"));assertEquals("D2_CONFIGURATION",identity.get("OWNER_NAME"));
        var tx=new TransactionTemplate(manager);tx.setTimeout(15);tx.executeWithoutResult(s->{jdbc.execute("LOCK TABLE D2_CONFIGURATION.CURRENT_CONFIGURATION IN EXCLUSIVE MODE WAIT 5");assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM D2_CONFIGURATION.CURRENT_CONFIGURATION",Long.class));});
        guarded=true;System.out.println("Real-readiness fixture namespace: "+namespace);
        id=Long.parseLong(create.createDraft(new CreateConfigurationVersionRequest(namespace,DESCRIPTION,true,"CAP_AT_MAX",100),42).versionId());revision=0;
        var content=content(Long.toString(id));
        for(var row:rows(content,"riskRules")){
            var body=new TreeMap<String,Object>(row);body.keySet().retainAll(Set.of("displayName","enabled","weight","parameterSchemaVersion","parameters","evaluationOrder"));body.put("expectedRowVersion",revision);
            rules.put(id,(String)row.get("ruleCode"),DraftRiskRuleRequest.from(body));revision++;
        }
        for(var row:rows(content,"riskBands")){
            var body=new TreeMap<String,Object>(row);body.keySet().retainAll(Set.of("minScoreInclusive","maxScoreInclusive","approvalLevel"));body.put("expectedRowVersion",revision);
            bands.put(id,(String)row.get("bandCode"),DraftRiskBandRequest.from(body));revision++;
        }
        for(var row:rows(content,"approvalThresholds")){
            var body=new TreeMap<String,Object>(row);body.keySet().retainAll(Set.of("minAmountInclusive","maxAmountExclusive"));body.put("expectedRowVersion",revision);
            thresholds.put(id,new java.math.BigInteger(row.get("approvalLevel").toString()).intValueExact(),DraftApprovalThresholdRequest.from(body));revision++;
        }
        for(var row:rows(content,"approvalSlas")){
            var body=new TreeMap<String,Object>(row);body.keySet().retainAll(Set.of("durationSeconds","startEventCode","escalationPolicyCode","terminalLevelPolicyCode"));body.put("durationSeconds",86400);body.put("expectedRowVersion",revision);
            slas.put(id,new java.math.BigInteger(row.get("approvalLevel").toString()).intValueExact(),DraftApprovalSlaRequest.from(body));revision++;
        }
        assertTrue(readiness.get(id).ready());
    }
    @Test void realReadyDraftPublishesActivatesAndAuthenticatedReadsMapExactly() throws Exception {
        mvc.perform(post("/api/v1/configuration/versions/"+id+"/publish").header("Authorization","Bearer "+credentials.user()).contentType("application/json").content("{\"expectedRowVersion\":\""+revision+"\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.publicationState").value("PUBLISHED"));
        active.activate(id,new ActivationRequest(null),42);
        var token=credentials.service("business-configuration-service","risk-assessment-service",List.of(ServicePermissions.CONFIG_ACTIVE,ServicePermissions.CONFIG_VERSION));
        for(String route:List.of("active","versions/"+id)){
            var reply=mvc.perform(get("/internal/v1/configuration/"+route).header("Authorization","Bearer "+token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
            var snapshot=new PublishedSnapshotResponse().decode(reply,route.equals("active")?null:Long.toString(id));
            assertEquals("configuration-content-v2",snapshot.hashFormat());assertTrue(snapshot.canonicalConfiguration().contains("\"approvalPolicy\""));
            var config=new PublishedConfigurationMapping().map(snapshot);assertEquals(Long.toString(revision+1),config.rowVersion());assertEquals(7,config.rules().size());assertEquals(100,config.maxFinalScore());
        }
        var before=active.active();assertEquals(before,active.activate(id,new ActivationRequest(0L),42));
        assertThrows(DraftRuleConflictException.class,()->slas.put(id,1,new DraftApprovalSlaRequest(1,"APPROVAL_PENDING_V1","NEXT_LEVEL_V1",null,revision+1)));
        assertEquals(before,active.active());
    }
    @Test void competingRealPublishersFinalizeOnlyOnce()throws Exception{
        final long fixtureId=id,expectedRevision=revision;
        var executor=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);workersStopped=false;
        try{var tasks=new ArrayList<Future<Boolean>>();for(int n=0;n<2;n++)tasks.add(executor.submit(()->{start.await();try{publish.publish(fixtureId,new PublishConfigurationRequest(expectedRevision),42);return true;}catch(AlreadyPublishedException expected){return false;}}));
            start.countDown();int successes=0;for(var t:tasks)if(t.get(25,TimeUnit.SECONDS))successes++;assertEquals(1,successes);assertEquals(revision+1,versions.findVersion(id,false).orElseThrow().rowVersion());
        }finally{start.countDown();executor.shutdownNow();workersStopped=executor.awaitTermination(5,TimeUnit.SECONDS);System.out.println("Real publication workers terminated="+workersStopped);assertTrue(workersStopped,"Cleanup deferred: worker remains");}
    }
    @Test void injectedFailureAfterActualPublicationUpdateRollsBack() {
        var reached=new java.util.concurrent.atomic.AtomicBoolean();
        var failing=new ConfigurationPublicationRepository(jdbc){@Override public void publish(long version,long expected,long actor,OffsetDateTime now,String hash){super.publish(version,expected,actor,now,hash);reached.set(true);throw new IllegalStateException("Injected test-only post-update failure");}};
        var target=new ConfigurationPublicationService(versions,ruleRows,bandRows,tierRows,slaRows,validator,hashes,failing);
        var factory=new ProxyFactory(target);factory.setProxyTargetClass(true);factory.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
        var proxy=(ConfigurationPublicationService)factory.getProxy();
        assertThrows(IllegalStateException.class,()->proxy.publish(id,new PublishConfigurationRequest(revision),42));
        assertTrue(reached.get(),"Must reach the post-update injection, not fail earlier");
        var after=versions.findVersion(id,false).orElseThrow();assertEquals("DRAFT",after.version().publicationState());assertEquals(revision,after.rowVersion());
        assertNull(new TransactionTemplate(manager).execute(s->failing.metadata(id)).contentHash());assertTrue(readiness.get(id).ready());
    }
    @AfterEach void cleanup(){
        if(!guarded)return;if(!workersStopped)throw new AssertionError("Workers remain; cleanup deferred for "+namespace);
        assertTrue(namespace.matches("D2_CFG_READY_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
        new TransactionTemplate(manager).executeWithoutResult(status->{
            jdbc.execute("LOCK TABLE D2_CONFIGURATION.CURRENT_CONFIGURATION IN EXCLUSIVE MODE WAIT 5");
            var parents=jdbc.queryForList("SELECT VERSION_ID,CREATED_BY_USER_ID,DESCRIPTION,IS_DEMONSTRATION,PUBLICATION_STATE FROM D2_CONFIGURATION.CONFIGURATION_VERSIONS WHERE VERSION_LABEL=? FOR UPDATE",namespace);
            assertTrue(parents.size()<=1);
            for(var p:parents){long own=new java.math.BigDecimal(p.get("VERSION_ID").toString()).longValueExact();assertTrue(id==0||own==id);assertEquals(DESCRIPTION,p.get("DESCRIPTION"));assertEquals("42",p.get("CREATED_BY_USER_ID").toString());assertEquals("1",p.get("IS_DEMONSTRATION").toString());
                var pointers=jdbc.queryForList("SELECT VERSION_ID,ACTIVATED_BY_USER_ID,ROW_VERSION FROM D2_CONFIGURATION.CURRENT_CONFIGURATION");
                assertTrue(pointers.size()<=1);for(var pointer:pointers){assertEquals(Long.toString(own),pointer.get("VERSION_ID").toString());assertEquals("42",pointer.get("ACTIVATED_BY_USER_ID").toString());assertEquals("0",pointer.get("ROW_VERSION").toString());}
                if("PUBLISHED".equals(p.get("PUBLICATION_STATE"))){var saved=active.published(own);assertEquals(Long.toString(own),saved.versionId());}
                if(!pointers.isEmpty())assertEquals(1,jdbc.update("DELETE FROM D2_CONFIGURATION.CURRENT_CONFIGURATION WHERE POINTER_ID=1 AND VERSION_ID=? AND ACTIVATED_BY_USER_ID=42 AND ROW_VERSION=0",own));
                for(String table:List.of("RISK_RULE_CONFIGS","RISK_BAND_CONFIGS","APPROVAL_THRESHOLD_CONFIGS","SLA_CONFIGS"))jdbc.update("DELETE FROM D2_CONFIGURATION."+table+" WHERE VERSION_ID=?",own);
                assertEquals(1,jdbc.update("DELETE FROM D2_CONFIGURATION.CONFIGURATION_VERSIONS WHERE VERSION_ID=? AND VERSION_LABEL=? AND CREATED_BY_USER_ID=42 AND DESCRIPTION=? AND IS_DEMONSTRATION=1",own,namespace,DESCRIPTION));
            }
            assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM D2_CONFIGURATION.CONFIGURATION_VERSIONS WHERE VERSION_LABEL=?",Long.class,namespace));
        });System.out.println("Real-readiness guarded cleanup remaining parents=0; namespace="+namespace);
    }
}
