package com.ofss.risk.persistence;

import com.ofss.configuration.activation.*;
import com.ofss.configuration.band.*;
import com.ofss.configuration.publication.*;
import com.ofss.configuration.readiness.*;
import com.ofss.configuration.rule.*;
import com.ofss.configuration.sla.*;
import com.ofss.configuration.threshold.*;
import com.ofss.configuration.version.*;
import com.ofss.internal.verification.EphemeralReceiver;
import com.ofss.risk.upstream.ConfiguredClientFixtures;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** User-run only. No direct PUBLISHED inserts or readiness overrides. Refuses any existing active pointer. */
final class OracleConfiguredClientConfiguration {
    static final String DESCRIPTION="D2 configured-client real-readiness synthetic fixture";
    final String namespace;final JdbcTemplate jdbc;final JdbcTransactionManager tx;
    final Map<String,Long> ids=new TreeMap<>();final Map<Long,String> revisions=new TreeMap<>();Map<String,Object> pointer;
    OracleConfiguredClientConfiguration(String namespace,JdbcTemplate jdbc,JdbcTransactionManager tx){ConfiguredClientFixtures.requireNamespace(namespace);this.namespace=namespace;this.jdbc=jdbc;this.tx=tx;}
    String label(String code){if(!Set.of("A","B").contains(code))throw new IllegalArgumentException();return "D2_CLIENTS_"+namespace.substring(11)+"_"+code;}
    void emptyPointerGuard(){new TransactionTemplate(tx).executeWithoutResult(s->{jdbc.execute("LOCK TABLE D2_CONFIGURATION.CURRENT_CONFIGURATION IN EXCLUSIVE MODE WAIT 5");assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM D2_CONFIGURATION.CURRENT_CONFIGURATION",Long.class),"Existing pointer: test refused before fixture writes");});}
    @SuppressWarnings("unchecked") long create(EphemeralReceiver receiver,String code){
        long id=Long.parseLong(receiver.bean(ConfigurationVersionService.class).createDraft(new CreateConfigurationVersionRequest(label(code),DESCRIPTION,true,"CAP_AT_MAX",100),42).versionId());ids.put(code,id);long revision=0;
        var content=ConfiguredClientFixtures.JSON.readValue(SubmissionFixtures.published().canonicalConfiguration(),Map.class);
        for(var row:(List<Map<String,Object>>)content.get("riskRules")){var body=new TreeMap<String,Object>(row);body.keySet().retainAll(Set.of("enabled","weight","parameterSchemaVersion","parameters","evaluationOrder"));body.put("displayName","Configured client test "+row.get("ruleCode"));body.put("expectedRowVersion",revision++);if(code.equals("B")&&row.get("ruleCode").equals("STRUCTURING"))body.put("weight",34);
            receiver.bean(DraftRiskRuleService.class).put(id,(String)row.get("ruleCode"),DraftRiskRuleRequest.from(body));}
        for(var row:(List<Map<String,Object>>)content.get("riskBands")){var body=new TreeMap<String,Object>(row);body.keySet().retainAll(Set.of("minScoreInclusive","maxScoreInclusive","approvalLevel"));body.put("expectedRowVersion",revision++);receiver.bean(DraftRiskBandService.class).put(id,(String)row.get("bandCode"),DraftRiskBandRequest.from(body));}
        for(var row:(List<Map<String,Object>>)content.get("approvalThresholds")){var body=new TreeMap<String,Object>(row);body.keySet().retainAll(Set.of("minAmountInclusive","maxAmountExclusive"));body.put("expectedRowVersion",revision++);receiver.bean(DraftApprovalThresholdService.class).put(id,((Number)row.get("approvalLevel")).intValue(),DraftApprovalThresholdRequest.from(body));}
        for(var row:(List<Map<String,Object>>)content.get("approvalSlas")){var body=new TreeMap<String,Object>(row);body.keySet().retainAll(Set.of("durationSeconds","startEventCode","escalationPolicyCode","terminalLevelPolicyCode"));body.put("expectedRowVersion",revision++);receiver.bean(DraftApprovalSlaService.class).put(id,((Number)row.get("approvalLevel")).intValue(),DraftApprovalSlaRequest.from(body));}
        assertTrue(receiver.bean(PublicationReadinessService.class).get(id).ready(),"Real readiness must approve the complete fixture");
        receiver.bean(ConfigurationPublicationService.class).publish(id,new PublishConfigurationRequest(revision),42);
        var published=receiver.bean(ConfigurationActivationService.class).published(id);assertEquals("configuration-content-v2",published.hashFormat());revisions.put(id,published.versionRowVersion());return id;
    }
    void activate(EphemeralReceiver receiver,String code){
        receiver.bean(ConfigurationActivationService.class).activate(ids.get(code),new ActivationRequest(pointer==null?null:((java.math.BigDecimal)pointer.get("ROW_VERSION")).longValueExact()),42);
        pointer=jdbc.queryForObject("SELECT * FROM D2_CONFIGURATION.CURRENT_CONFIGURATION WHERE POINTER_ID=1",(rs,n)->OracleWorkPersistenceTest.values(rs));
    }
    Set<Long> discover(){for(String code:List.of("A","B")){var found=jdbc.queryForList("SELECT VERSION_ID FROM D2_CONFIGURATION.CONFIGURATION_VERSIONS WHERE VERSION_LABEL=?",Long.class,label(code));assertTrue(found.size()<=1);if(!found.isEmpty())ids.put(code,found.getFirst());}return Set.copyOf(ids.values());}
    Map<Long,String> verifiedHashes(){
        discover();var hashes=new TreeMap<Long,String>();var mapper=ConfiguredClientFixtures.JSON;
        var reader=new ConfigurationActivationService(new ConfigurationActivationRepository(jdbc),new DraftRiskRuleRepository(jdbc,mapper),new DraftRiskBandRepository(jdbc),new DraftApprovalThresholdRepository(jdbc),new DraftApprovalSlaRepository(jdbc),new ConfigurationContentHash(mapper));
        for(long id:ids.values())if("PUBLISHED".equals(jdbc.queryForObject("SELECT PUBLICATION_STATE FROM D2_CONFIGURATION.CONFIGURATION_VERSIONS WHERE VERSION_ID=?",String.class,id))){var read=new TransactionTemplate(tx);read.setReadOnly(true);hashes.put(id,read.execute(s->reader.published(id).contentHash()));}
        return hashes;
    }
    static void guardPointer(Map<String,Object> expected,List<Map<String,Object>> active,Set<Long> ids){
        if(active.size()>1)throw new IllegalStateException("Unexpected fixture pointer population");
        if(active.isEmpty()){if(expected!=null)throw new IllegalStateException("Owned pointer disappeared; cleanup refused");return;}
        var current=active.getFirst();if(number(current.get("POINTER_ID"))!=1||!ids.contains(number(current.get("VERSION_ID")))||number(current.get("ACTIVATED_BY_USER_ID"))!=42)throw new IllegalStateException("Foreign pointer; cleanup refused");
        if(expected!=null?!expected.equals(current):number(current.get("ROW_VERSION"))>1)throw new IllegalStateException("Unexpected fixture pointer revision or metadata; cleanup refused");
    }
    void preflightCleanup(){guardPointer(pointer,jdbc.query("SELECT * FROM D2_CONFIGURATION.CURRENT_CONFIGURATION",(rs,n)->OracleWorkPersistenceTest.values(rs)),Set.copyOf(ids.values()));}
    void clean(){
        var hashes=verifiedHashes();
        new TransactionTemplate(tx).executeWithoutResult(s->{
            jdbc.execute("LOCK TABLE D2_CONFIGURATION.CURRENT_CONFIGURATION IN EXCLUSIVE MODE WAIT 5");var active=jdbc.query("SELECT * FROM D2_CONFIGURATION.CURRENT_CONFIGURATION",(rs,n)->OracleWorkPersistenceTest.values(rs));guardPointer(pointer,active,Set.copyOf(ids.values()));
            if(!active.isEmpty()){var current=active.getFirst();long id=number(current.get("VERSION_ID"));assertTrue(ids.containsValue(id),"Foreign pointer: cleanup refused");assertEquals(42,number(current.get("ACTIVATED_BY_USER_ID")));
                if(pointer!=null)assertEquals(pointer,current,"Pointer changed unexpectedly: cleanup refused");else assertTrue(number(current.get("ROW_VERSION"))<=1,"Unexpected pointer revision: recovery refused");
                assertEquals(1,jdbc.update("DELETE FROM D2_CONFIGURATION.CURRENT_CONFIGURATION WHERE POINTER_ID=1 AND VERSION_ID=? AND ROW_VERSION=? AND ACTIVATED_BY_USER_ID=42",id,current.get("ROW_VERSION")));
            }else assertNull(pointer,"Owned pointer disappeared: cleanup refused");
            for(String code:List.of("A","B")){var parents=jdbc.queryForList("SELECT * FROM D2_CONFIGURATION.CONFIGURATION_VERSIONS WHERE VERSION_LABEL=? FOR UPDATE",label(code));assertTrue(parents.size()<=1);
                for(var parent:parents){long id=number(parent.get("VERSION_ID"));assertEquals(ids.get(code),id);assertEquals(DESCRIPTION,parent.get("DESCRIPTION"));assertEquals(42,number(parent.get("CREATED_BY_USER_ID")));assertEquals(1,number(parent.get("IS_DEMONSTRATION")));assertTrue(Set.of("DRAFT","PUBLISHED").contains(parent.get("PUBLICATION_STATE")));
                    if("PUBLISHED".equals(parent.get("PUBLICATION_STATE")))assertEquals(hashes.get(id),parent.get("CONTENT_HASH"));
                    for(String table:List.of("RISK_RULE_CONFIGS","RISK_BAND_CONFIGS","APPROVAL_THRESHOLD_CONFIGS","SLA_CONFIGS"))jdbc.update("DELETE FROM D2_CONFIGURATION."+table+" WHERE VERSION_ID=?",id);
                    assertEquals(1,jdbc.update("DELETE FROM D2_CONFIGURATION.CONFIGURATION_VERSIONS WHERE VERSION_ID=? AND VERSION_LABEL=? AND DESCRIPTION=? AND CREATED_BY_USER_ID=42 AND IS_DEMONSTRATION=1",id,label(code),DESCRIPTION));
                }
            }
        });for(String code:List.of("A","B"))assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM D2_CONFIGURATION.CONFIGURATION_VERSIONS WHERE VERSION_LABEL=?",Long.class,label(code)));
    }
    static long number(Object value){return new java.math.BigDecimal(value.toString()).longValueExact();}
}
