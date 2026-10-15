package com.ofss.risk.persistence;

import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** SELECT-only coherent persisted-result projection; no evaluator or worker operations. */
public class AssessmentReadRepository {
    static final String METADATA="""
        SELECT ROW_VERSION,WORK_STATE,TOTAL_RAW_SCORE,FINAL_SCORE,RISK_BAND,FINAL_FACTOR_COUNT,EVALUATED_AT,COMPLETED_AT
        FROM D2_RISK.RISK_ASSESSMENTS WHERE ASSESSMENT_ID=?
        """;
    private final JdbcTemplate jdbc; private final RiskAcceptanceRepository acceptance; private final RiskWorkRepository work;
    private final RiskEvidenceRepository evidence; private final RiskFinalizationRepository results; private final FrozenAssessmentLoader loader;
    public AssessmentReadRepository(JdbcTemplate jdbc) {
        this(jdbc,new RiskAcceptanceRepository(jdbc),new RiskWorkRepository(jdbc),new RiskEvidenceRepository(jdbc),new RiskFinalizationRepository(jdbc));
    }
    AssessmentReadRepository(JdbcTemplate jdbc,RiskAcceptanceRepository acceptance,RiskWorkRepository work,
            RiskEvidenceRepository evidence,RiskFinalizationRepository results) {
        this.jdbc=jdbc; this.acceptance=acceptance; this.work=work; this.evidence=evidence; this.results=results;
        loader=new FrozenAssessmentLoader(acceptance,work,evidence);
    }
    void beginSnapshot() { results.beginSnapshot(); }
    @SuppressWarnings("unchecked") AssessmentReadView read(long id) {
        if(!TransactionSynchronizationManager.isActualTransactionActive() || !TransactionSynchronizationManager.isCurrentTransactionReadOnly()
            || !TransactionSynchronizationManager.hasResource(jdbc.getDataSource())) throw unavailable();
        var accepted=acceptance.findById(id).orElseThrow(()->new AssessmentReadFailure(AssessmentReadFailure.Code.ASSESSMENT_NOT_FOUND));
        var config=StoredConfigurationDecoder.read(accepted.configurationJson());
        var parent=work.read(id);
        if(!accepted.rowVersion().equals(Long.toString(parent.revision())) || !accepted.workState().equals(parent.state())
            || !accepted.configurationSnapshotId().equals(Long.toString(parent.snapshot()))) throw unavailable();
        var header=evidence.header(id); FrozenAssessmentLoader.Snapshot frozen=null;
        if(header.hash()!=null) frozen=loader.loadStored(accepted);
        else if(!evidence.children(id).isEmpty()) throw unavailable();
        var metadata=jdbc.query(METADATA,ps->ps.setLong(1,id),(rs,n)-> {
            if(PersistenceValues.number(rs.getObject("ROW_VERSION"),false)!=parent.revision() || !parent.state().equals(rs.getString("WORK_STATE"))) throw unavailable();
            if(!parent.state().equals("COMPLETED")) for(String name:List.of("TOTAL_RAW_SCORE","FINAL_SCORE","RISK_BAND","FINAL_FACTOR_COUNT","EVALUATED_AT","COMPLETED_AT"))
                if(rs.getObject(name)!=null) throw unavailable();
            return true;
        });
        if(metadata.size()!=1) throw unavailable();
        AssessmentReadView.Score score=null; List<AssessmentReadView.Factor> factors=null; String evaluated=null,completed=null;
        if(parent.state().equals("COMPLETED")) {
            if(frozen==null) throw unavailable(); var saved=results.readPersisted(frozen); var result=saved.result();
            score=new AssessmentReadView.Score(result.rawTotal().longValueExact(),result.finalScore(),config.maxFinalScore(),result.riskBand());
            factors=result.factors().stream().map(f->new AssessmentReadView.Factor(f.ruleCode().name(),config.rules().stream().filter(r->r.code()==f.ruleCode()).findFirst().orElseThrow().evaluationOrder(),
                f.outcome().name(),f.configuredWeight(),f.contribution(),f.explanation())).toList();
            evaluated=saved.evaluatedAt().toString(); completed=saved.completedAt().toString();
        } else if(results.hasFactors(id)) throw unavailable();
        var body=CanonicalAcceptance.read(accepted.configurationJson());
        return new AssessmentReadView(accepted.assessmentId(),accepted.rowVersion(),accepted.workState(),accepted.createdAt().toString(),accepted.cutoff().toString(),
            evaluated,completed,accepted.configurationVersionId(),accepted.configurationRowVersion(),(String)CanonicalAcceptance.read(accepted.acceptanceJson()).get("evaluatorVersion"),
            score,factors,accepted.configurationSnapshotId(),accepted.configurationHash(),(Map<String,Object>)body.get("configuration"));
    }
    private static AssessmentReadFailure unavailable() { return new AssessmentReadFailure(AssessmentReadFailure.Code.ASSESSMENT_UNAVAILABLE); }
}
