package com.ofss.risk.persistence;

import com.ofss.risk.facts.FactsProviderProtocol;
import com.ofss.risk.upstream.ConfiguredClientFixtures;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Exact known namespace + facts/config/input/result verification before ANY mutation. Test-only. */
final class OracleConfiguredClientRiskCleaner {
    static final String MARKER="ASSESSMENT_ID=? AND SUBMISSION_SOURCE=? AND SUBMISSION_KEY=? AND ACCEPTANCE_HASH=? AND CONFIGURATION_HASH=? AND ROW_VERSION=?";
    record Verified(long id,long snapshot,int evidence,int factors,Object[] marker){}
    final JdbcTemplate jdbc;final JdbcTransactionManager tx;
    OracleConfiguredClientRiskCleaner(JdbcTemplate jdbc,JdbcTransactionManager tx){this.jdbc=jdbc;this.tx=tx;}
    void clean(String namespace,Map<Long,String> allowedConfigurations){
        var fixture=new ConfiguredClientFixtures(namespace);
        new TransactionTemplate(tx).executeWithoutResult(s->{
            var all=jdbc.query("SELECT * FROM D2_RISK.RISK_ASSESSMENTS WHERE SUBMISSION_SOURCE=? FOR UPDATE",(rs,n)->OracleWorkPersistenceTest.values(rs),namespace);
            var rows=new ArrayList<Verified>();var keys=new HashSet<String>();var accepts=new RiskAcceptanceRepository(jdbc);var work=new RiskWorkRepository(jdbc);var evidence=new RiskEvidenceRepository(jdbc);
            var restorer=new SubmissionSnapshotReader(accepts,work,evidence,tx);
            for(var parent:all){String key=(String)parent.get("SUBMISSION_KEY");assertTrue(ConfiguredClientFixtures.KEYS.contains(key)&&keys.add(key));assertEquals("configured-pass/"+key,parent.get("VALIDATION_REFERENCE"));assertEquals("configured-v1",parent.get("VALIDATION_REVISION"));assertEquals(PinnedRiskEvaluators.CURRENT,parent.get("EVALUATOR_VERSION"));assertNull(parent.get("CORRELATION_ID"));
                var stored=accepts.find(new SubmissionIdentity(namespace,key)).orElseThrow();var accepted=restorer.restore(stored);assertEquals(fixture.supplier,accepted.current().supplierId());
                assertEquals(allowedConfigurations.get(Long.parseLong(stored.configurationVersionId())),accepted.configuration().contentHash());assertNotNull(allowedConfigurations.get(Long.parseLong(stored.configurationVersionId())));
                var command=fixture.command(key);var expected=new FactsProviderProtocol().decode(ConfiguredClientFixtures.bytes(fixture.facts(command,stored.cutoff())),command,stored.cutoff(),ConfiguredClientFixtures.FACTS).acceptance(stored.cutoff(),accepted.configuration());
                var canonical=new CanonicalAcceptance();assertEquals(new CanonicalAcceptance.Document(stored.acceptanceJson(),stored.acceptanceHash()),canonical.acceptance(expected,stored.configurationVersionId(),stored.configurationRowVersion(),stored.configurationHash()));
                long id=Long.parseLong(stored.assessmentId()),snapshot=Long.parseLong(stored.configurationSnapshotId());assertEquals(1L,jdbc.queryForObject("SELECT COUNT(*) FROM D2_RISK.RISK_CONFIGURATION_SNAPSHOTS WHERE ASSESSMENT_ID=?",Long.class,id));
                var header=evidence.header(id);var children=evidence.children(id);var factors=jdbc.query(RiskFinalizationRepository.FACTORS,(rs,n)->OracleWorkPersistenceTest.values(rs),id);
                if(header.hash()==null){PriorHistoryCaptureCodec.partial(children,stored);assertTrue(factors.isEmpty());assertNotEquals("COMPLETED",stored.workState());}
                else {var frozen=new FrozenAssessmentLoader(accepts,work,evidence).loadStored(stored);
                    for(var source:List.of(frozen.input().payments(),frozen.input().supplier(),frozen.input().supplierTransitions()))assertTrue(source.sourceSnapshotId().startsWith("ue1-"));
                    if("COMPLETED".equals(stored.workState()))OracleFinalizationFixtureCleaner.verifyCompletion(parent,frozen,factors);else assertTrue(factors.isEmpty());
                }
                var capture=PriorHistoryCaptureCodec.find(children,stored);if(capture!=null){var saved=PriorHistoryCaptureCodec.decode(capture,stored);assertEquals(fixture.supplier,saved.evidence().supplierId());}
                if(!"COMPLETED".equals(stored.workState()))for(String field:List.of("TOTAL_RAW_SCORE","FINAL_SCORE","RISK_BAND","FINAL_FACTOR_COUNT","EVALUATED_AT","COMPLETED_AT"))assertNull(parent.get(field));
                var diagnostics=jdbc.query("SELECT * FROM D2_RISK.RISK_INPUT_EVIDENCE WHERE ASSESSMENT_ID=? AND ATTEMPT_NO>0",(rs,n)->OracleWorkPersistenceTest.values(rs),id);
                for(var diagnostic:diagnostics){assertEquals("ATTEMPT_DIAGNOSTIC",diagnostic.get("RECORD_CLASS"));assertEquals("RESULT",diagnostic.get("EVIDENCE_KEY"));assertEquals("risk-assessment-service",diagnostic.get("SOURCE_SERVICE"));assertEquals("risk-work-diagnostic-v1",diagnostic.get("EVIDENCE_FORMAT_VERSION"));
                    String body=(String)diagnostic.get("EVIDENCE_JSON");assertEquals(CanonicalAcceptance.hash(body),diagnostic.get("CONTENT_HASH"));var parsed=CanonicalAcceptance.read(body);assertEquals(Set.of("attempt","code","epoch","state"),parsed.keySet());assertEquals(WorkDiagnostic.valueOf((String)parsed.get("code")).state(),parsed.get("state"));}
                int count=children.size()+diagnostics.size();assertEquals((long)count,jdbc.queryForObject("SELECT COUNT(*) FROM D2_RISK.RISK_INPUT_EVIDENCE WHERE ASSESSMENT_ID=?",Long.class,id));
                rows.add(new Verified(id,snapshot,count,factors.size(),new Object[]{id,namespace,key,stored.acceptanceHash(),stored.configurationHash(),Long.parseLong(stored.rowVersion())}));
            }
            for(var row:rows){assertEquals(row.factors(),jdbc.update("DELETE FROM D2_RISK.RISK_ASSESSMENT_FACTORS WHERE ASSESSMENT_ID=? AND EXISTS (SELECT 1 FROM D2_RISK.RISK_ASSESSMENTS WHERE "+MARKER+")",prepend(row.marker(),row.id())));
                assertEquals(1,jdbc.update("UPDATE D2_RISK.RISK_ASSESSMENTS SET WORK_STATE='ACCEPTED',CONFIGURATION_SNAPSHOT_ID=NULL,FROZEN_INPUT_EVIDENCE_ID=NULL,INPUT_HASH=NULL,INPUT_FROZEN_AT=NULL,TOTAL_RAW_SCORE=NULL,FINAL_SCORE=NULL,RISK_BAND=NULL,FINAL_FACTOR_COUNT=NULL,EVALUATED_AT=NULL,COMPLETED_AT=NULL,LEASE_TOKEN=NULL,LEASE_EXPIRES_AT=NULL,LAST_ERROR_CODE=NULL,LAST_ERROR_MESSAGE=NULL WHERE "+MARKER,row.marker()));
                assertEquals(row.evidence(),jdbc.update("DELETE FROM D2_RISK.RISK_INPUT_EVIDENCE WHERE ASSESSMENT_ID=? AND EXISTS (SELECT 1 FROM D2_RISK.RISK_ASSESSMENTS WHERE "+MARKER+")",prepend(row.marker(),row.id())));
                assertEquals(1,jdbc.update("DELETE FROM D2_RISK.RISK_CONFIGURATION_SNAPSHOTS WHERE ASSESSMENT_ID=? AND SNAPSHOT_ID=? AND EXISTS (SELECT 1 FROM D2_RISK.RISK_ASSESSMENTS WHERE "+MARKER+")",prepend(row.marker(),row.id(),row.snapshot())));
                assertEquals(1,jdbc.update("DELETE FROM D2_RISK.RISK_ASSESSMENTS WHERE WORK_STATE='ACCEPTED' AND CONFIGURATION_SNAPSHOT_ID IS NULL AND FROZEN_INPUT_EVIDENCE_ID IS NULL AND "+MARKER,row.marker()));
            }
        });assertEquals(0L,jdbc.queryForObject("SELECT COUNT(*) FROM D2_RISK.RISK_ASSESSMENTS WHERE SUBMISSION_SOURCE=?",Long.class,namespace));
    }
    static Object[] prepend(Object[] marker,Object...head){var result=Arrays.copyOf(head,head.length+marker.length);System.arraycopy(marker,0,result,head.length,marker.length);return result;}
}
