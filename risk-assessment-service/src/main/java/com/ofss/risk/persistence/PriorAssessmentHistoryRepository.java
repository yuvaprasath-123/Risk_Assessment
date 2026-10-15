package com.ofss.risk.persistence;

import com.ofss.risk.contract.RuleCode;
import com.ofss.risk.engine.RiskInput.PriorAssessment;
import com.ofss.risk.submission.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** SELECT-only bounded candidate scan, followed by the existing full persisted-result verifier in the SAME snapshot. */
public class PriorAssessmentHistoryRepository {
    static final String CANDIDATES="""
        SELECT * FROM (
          SELECT ASSESSMENT_ID,SUBMISSION_SOURCE,SUBMISSION_KEY,SUPPLIER_ID,CURRENCY_CODE,WORK_STATE,COMPLETED_AT
          FROM D2_RISK.RISK_ASSESSMENTS
          WHERE SUPPLIER_ID=? AND WORK_STATE='COMPLETED' AND COMPLETED_AT>=? AND COMPLETED_AT<?
          ORDER BY COMPLETED_AT,SUBMISSION_SOURCE,SUBMISSION_KEY,ASSESSMENT_ID
        ) WHERE ROWNUM<=?
        """;
    record Candidate(long id,SubmissionIdentity identity,String supplier,String currency,Instant completed) {}
    record Verified(Candidate candidate,String originalSubmissionId,PriorAssessment prior,String resultHash) {}
    private final JdbcTemplate jdbc;private final RiskAcceptanceRepository acceptance;private final RiskWorkRepository work;
    private final AssessmentReadRepository reads;
    public PriorAssessmentHistoryRepository(JdbcTemplate jdbc){this(jdbc,new RiskAcceptanceRepository(jdbc),new RiskWorkRepository(jdbc),new AssessmentReadRepository(jdbc));}
    PriorAssessmentHistoryRepository(JdbcTemplate jdbc,RiskAcceptanceRepository acceptance,RiskWorkRepository work,AssessmentReadRepository reads){
        this.jdbc=jdbc;this.acceptance=acceptance;this.work=work;this.reads=reads;
    }
    void beginSnapshot(){new RiskFinalizationRepository(jdbc).beginSnapshot();}
    int captureAttempt(AcceptanceRequest request){
        bound();var stored=acceptance.find(request.identity()).orElseThrow(PriorAssessmentHistoryRepository::invalid);
        var canonical=new CanonicalAcceptance();
        if(!canonical.configuration(request.configuration()).hash().equals(stored.configurationHash())
            ||!canonical.acceptance(request,stored.configurationVersionId(),stored.configurationRowVersion(),stored.configurationHash()).hash().equals(stored.acceptanceHash()))throw invalid();
        var row=work.read(PersistenceValues.id(stored.assessmentId()));
        if(!"IN_PROGRESS".equals(row.state())||!row.state().equals(stored.workState())||row.attempt()<1
            ||!stored.rowVersion().equals(Long.toString(row.revision()))||!stored.configurationSnapshotId().equals(Long.toString(row.snapshot())))throw invalid();
        return row.attempt();
    }
    List<Candidate> candidates(String supplier,Instant from,Instant cutoff,int capacity){
        bound();return jdbc.query(CANDIDATES,ps->{ps.setLong(1,PersistenceValues.id(supplier));time(ps,2,from);time(ps,3,cutoff);ps.setInt(4,Math.addExact(capacity,1));},(rs,n)->{
            if(!"COMPLETED".equals(rs.getString("WORK_STATE")))throw invalid();
            var t=rs.getObject("COMPLETED_AT",OffsetDateTime.class);if(t==null)throw invalid();PersistenceValues.timestamp(t.toInstant());
            return new Candidate(PersistenceValues.number(rs.getObject("ASSESSMENT_ID"),true),new SubmissionIdentity(rs.getString("SUBMISSION_SOURCE"),rs.getString("SUBMISSION_KEY")),
                Long.toString(PersistenceValues.number(rs.getObject("SUPPLIER_ID"),true)),rs.getString("CURRENCY_CODE"),t.toInstant());
        });
    }
    Verified verify(Candidate row){
        bound();var view=reads.read(row.id()); // Verifies configuration/input hashes, manifest, exactly seven factors and totals; does not rescore.
        var stored=acceptance.findById(row.id()).orElseThrow(PriorAssessmentHistoryRepository::invalid);
        var current=(Map<?,?>)CanonicalAcceptance.read(stored.acceptanceJson()).get("current");
        if(!Long.toString(row.id()).equals(stored.assessmentId())||!stored.assessmentId().equals(view.assessmentId())||!stored.rowVersion().equals(view.rowVersion())
            ||!stored.configurationSnapshotId().equals(view.snapshotId())||!row.identity().equals(stored.identity())||!"COMPLETED".equals(view.status())||view.result()==null||view.factors()==null||view.factors().size()!=7
            ||!row.completed().equals(Instant.parse(view.completedAt()))||!row.supplier().equals(current.get("supplierId"))||!row.currency().equals(current.get("currencyCode")))throw invalid();
        String original=(String)current.get("submissionId");PersistenceValues.text(original,Integer.MAX_VALUE);
        var triggered=EnumSet.noneOf(RuleCode.class);
        for(var f:view.factors())if(f.outcome().equals("TRIGGERED")&&!f.ruleCode().equals("PREVIOUS_RISK_FLAGS"))triggered.add(RuleCode.valueOf(f.ruleCode()));
        var prior=new PriorAssessment("d2-risk-assessment:"+row.id(),submissionReference(row.identity()),row.supplier(),row.completed(),triggered);
        String fingerprint=CanonicalEvidence.document(CanonicalEvidence.map("assessmentId",view.assessmentId(),"rowVersion",view.rowVersion(),
            "configurationHash",view.configurationHash(),"completedAt",row.completed(),"factors",view.factors())).hash();
        return new Verified(row,original,prior,fingerprint);
    }
    static String submissionReference(SubmissionIdentity identity){return "d2-submission-v1:"+CanonicalEvidence.document(identity).hash();}
    private void bound(){if(!TransactionSynchronizationManager.isActualTransactionActive()||!TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        ||!TransactionSynchronizationManager.hasResource(jdbc.getDataSource()))throw invalid();}
    private static void time(PreparedStatement ps,int index,Instant value)throws SQLException{ps.setObject(index,value.atOffset(ZoneOffset.UTC),Types.TIMESTAMP_WITH_TIMEZONE);}
    static SubmissionFailure invalid(){return new SubmissionFailure(SubmissionFailure.Code.DEPENDENCY_UNAVAILABLE);}
}
