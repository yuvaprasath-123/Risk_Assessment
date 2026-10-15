package com.ofss.risk.persistence;

import com.ofss.risk.contract.RuleCode;
import com.ofss.risk.engine.*;
import java.io.*;
import java.sql.*;
import java.time.*;
import java.math.BigInteger;
import java.util.*;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** All factor inserts and the final fenced parent update use the same bound transaction. */
public class RiskFinalizationRepository {
    static final String INSERT="""
        INSERT INTO D2_RISK.RISK_ASSESSMENT_FACTORS
        (ASSESSMENT_ID,RULE_CODE,RULE_CONFIG_SOURCE_ID,FACTOR_ORDER,EVALUATION_OUTCOME,CONFIGURED_WEIGHT,
         SCORE_CONTRIBUTION,PRIMARY_EVIDENCE_ID,EXPLANATION,RESULT_FORMAT_VERSION,DETAILS_JSON,EVALUATED_AT)
        VALUES (?,?,NULL,?,?,?,?,?,?,?,?,?)
        """;
    static final String FACTORS="SELECT * FROM D2_RISK.RISK_ASSESSMENT_FACTORS WHERE ASSESSMENT_ID=? ORDER BY FACTOR_ORDER,RULE_CODE";
    static final String COMPLETE="""
        UPDATE D2_RISK.RISK_ASSESSMENTS SET WORK_STATE='COMPLETED',TOTAL_RAW_SCORE=?,FINAL_SCORE=?,RISK_BAND=?,
        FINAL_FACTOR_COUNT=7,EVALUATED_AT=?,COMPLETED_AT=?,LEASE_TOKEN=NULL,LEASE_EXPIRES_AT=NULL,
        LAST_ERROR_CODE=NULL,LAST_ERROR_MESSAGE=NULL,ROW_VERSION=ROW_VERSION+1
        WHERE ASSESSMENT_ID=? AND WORK_STATE='IN_PROGRESS' AND LEASE_TOKEN=? AND LEASE_EPOCH=? AND ATTEMPT_COUNT=?
        AND ROW_VERSION=? AND LEASE_EXPIRES_AT=? AND LEASE_EXPIRES_AT>SYSTIMESTAMP
        AND ACCEPTANCE_HASH=? AND CONFIGURATION_HASH=? AND INPUT_HASH=? AND FROZEN_INPUT_EVIDENCE_ID=?
        AND CONFIGURATION_SNAPSHOT_ID=? AND INPUT_FROZEN_AT=?
        """;
    static final String RESULT="SELECT TOTAL_RAW_SCORE,FINAL_SCORE,RISK_BAND,FINAL_FACTOR_COUNT,EVALUATED_AT,COMPLETED_AT FROM D2_RISK.RISK_ASSESSMENTS WHERE ASSESSMENT_ID=? AND WORK_STATE='COMPLETED'";
    private final JdbcTemplate jdbc;
    public RiskFinalizationRepository(JdbcTemplate jdbc) { this.jdbc=Objects.requireNonNull(jdbc); }
    void beginSnapshot() {
        bound(false);
        if(!TransactionSynchronizationManager.isCurrentTransactionReadOnly()) throw new IllegalStateException("Read-only snapshot required");
        jdbc.execute((ConnectionCallback<Void>)c->{try(var statement=c.createStatement()) { statement.executeUpdate("SET TRANSACTION READ ONLY"); } return null;});
    }
    boolean hasFactors(long id) {
        bound(false); return !jdbc.query("SELECT FACTOR_ID FROM D2_RISK.RISK_ASSESSMENT_FACTORS WHERE ASSESSMENT_ID=?",ps->ps.setLong(1,id),(rs,n)->rs.getObject(1)).isEmpty();
    }
    void insert(FrozenAssessmentLoader.Snapshot s,RiskResult.Factor factor,String receipt,Instant evaluatedAt) {
        bound(true); Clob[] allocated=new Clob[1];
        String details=FinalizedFactors.details(factor,s,receipt);
        try {
            Integer count=jdbc.execute((PreparedStatementCreator)c->{
                var ps=c.prepareStatement(INSERT);
                try {
                    ps.setLong(1,s.work().id()); ps.setString(2,factor.ruleCode().name());
                    int order=s.config().rules().stream().filter(r->r.code()==factor.ruleCode()).findFirst().orElseThrow().evaluationOrder();
                    ps.setInt(3,order); ps.setString(4,factor.outcome().name()); ps.setInt(5,factor.configuredWeight()); ps.setInt(6,factor.contribution());
                    if(factor.outcome()==RiskResult.Outcome.DISABLED) ps.setNull(7,Types.NUMERIC); else ps.setLong(7,s.header().manifest());
                    ps.setString(8,factor.explanation()); ps.setString(9,FinalizedFactors.FORMAT);
                    allocated[0]=c.createClob();
                    if(allocated[0]==null || allocated[0].setString(1,details)!=details.length()) throw FrozenAssessmentLoader.unavailable();
                    ps.setClob(10,allocated[0]); time(ps,11,evaluatedAt); return ps;
                } catch(SQLException | RuntimeException e) { ps.close(); throw e; }
            },PreparedStatement::executeUpdate);
            if(count==null || count!=1) throw FrozenAssessmentLoader.unavailable();
        } finally {
            if(allocated[0]!=null) try { allocated[0].free(); } catch(SQLException e) { throw FrozenAssessmentLoader.unavailable(); }
        }
    }
    void complete(FrozenAssessmentLoader.Snapshot s,RiskResult result,Instant evaluatedAt,Instant completedAt) {
        bound(true); var row=s.work();
        if(row.revision()==Long.MAX_VALUE) throw new WorkFailure(WorkFailure.Code.WORK_COUNTER_EXHAUSTED);
        int count=jdbc.update(COMPLETE,ps->{
            ps.setLong(1,result.rawTotal().longValueExact()); ps.setInt(2,result.finalScore()); ps.setString(3,result.riskBand());
            time(ps,4,evaluatedAt); time(ps,5,completedAt); ps.setLong(6,row.id()); ps.setString(7,row.token()); ps.setLong(8,row.epoch());
            ps.setInt(9,row.attempt()); ps.setLong(10,row.revision()); time(ps,11,row.expiry());
            ps.setString(12,s.accepted().acceptanceHash()); ps.setString(13,s.accepted().configurationHash()); ps.setString(14,s.header().hash());
            ps.setLong(15,s.header().manifest()); ps.setLong(16,row.snapshot()); time(ps,17,s.header().frozenAt());
        });
        if(count!=1) throw new WorkFailure(WorkFailure.Code.LEASE_REJECTED);
    }
    CompletedAssessment read(FrozenAssessmentLoader.Snapshot s,FinalizationRequest request) {
        bound(false); var row=s.work(); var lease=request.lease(); String receipt=FinalizedFactors.receipt(request);
        if(!row.state().equals("COMPLETED") || row.token()!=null || row.expiry()!=null || row.errorCode()!=null || row.errorMessage()!=null
            || row.epoch()!=lease.epoch() || row.attempt()!=lease.attempt() || WorkLease.revision(lease.rowVersion())==Long.MAX_VALUE
            || row.revision()!=WorkLease.revision(lease.rowVersion())+1) throw new WorkFailure(WorkFailure.Code.LEASE_REJECTED);
        return readResult(s,receipt);
    }
    /** Read-authorized callers need no worker capability. Still verifies every stored factor and association. */
    CompletedAssessment readPersisted(FrozenAssessmentLoader.Snapshot s) {
        bound(false);
        var row=s.work();
        if(!row.state().equals("COMPLETED") || row.token()!=null || row.expiry()!=null || row.errorCode()!=null || row.errorMessage()!=null
            || row.epoch()<1 || row.attempt()<1 || row.revision()<1) throw FrozenAssessmentLoader.unavailable();
        return readResult(s,null);
    }
    private CompletedAssessment readResult(FrozenAssessmentLoader.Snapshot s,String expectedReceipt) {
        var row=s.work(); String[] storedReceipt={expectedReceipt};
        var parents=jdbc.query(RESULT,ps->ps.setLong(1,row.id()),(rs,n)->new Parent(
            PersistenceValues.number(rs.getObject("TOTAL_RAW_SCORE"),false),Math.toIntExact(PersistenceValues.number(rs.getObject("FINAL_SCORE"),false)),
            rs.getString("RISK_BAND"),Math.toIntExact(PersistenceValues.number(rs.getObject("FINAL_FACTOR_COUNT"),true)),
            time(rs,"EVALUATED_AT"),time(rs,"COMPLETED_AT")));
        if(parents.size()!=1) throw FrozenAssessmentLoader.unavailable(); var p=parents.getFirst();
        if(p.count()!=7 || p.evaluated().isBefore(s.header().frozenAt()) || p.completed().isBefore(p.evaluated())) throw FrozenAssessmentLoader.unavailable();
        var factors=jdbc.query(FACTORS,ps->ps.setLong(1,row.id()),(rs,n)->{
            if(PersistenceValues.number(rs.getObject("ASSESSMENT_ID"),true)!=row.id() || rs.getObject("RULE_CONFIG_SOURCE_ID")!=null
                || !FinalizedFactors.FORMAT.equals(rs.getString("RESULT_FORMAT_VERSION")) || !p.evaluated().equals(time(rs,"EVALUATED_AT"))) throw FrozenAssessmentLoader.unavailable();
            var code=RuleCode.valueOf(rs.getString("RULE_CODE")); var outcome=RiskResult.Outcome.valueOf(rs.getString("EVALUATION_OUTCOME"));
            int order=s.config().rules().stream().filter(r->r.code()==code).findFirst().orElseThrow().evaluationOrder();
            if(PersistenceValues.number(rs.getObject("FACTOR_ORDER"),true)!=order) throw FrozenAssessmentLoader.unavailable();
            Object primary=rs.getObject("PRIMARY_EVIDENCE_ID");
            if(outcome==RiskResult.Outcome.DISABLED ? primary!=null : primary==null || PersistenceValues.number(primary,true)!=s.header().manifest()) throw FrozenAssessmentLoader.unavailable();
            String json=clob(rs); var factor=FinalizedFactors.decode(code,outcome,
                Math.toIntExact(PersistenceValues.number(rs.getObject("CONFIGURED_WEIGHT"),false)),
                Math.toIntExact(PersistenceValues.number(rs.getObject("SCORE_CONTRIBUTION"),false)),rs.getString("EXPLANATION"),json);
            Object receiptValue=CanonicalAcceptance.read(json).get("completionFenceHash");
            if(!(receiptValue instanceof String receipt) || !receipt.matches("[0-9a-f]{64}")) throw FrozenAssessmentLoader.unavailable();
            if(storedReceipt[0]==null) storedReceipt[0]=receipt;
            if(!storedReceipt[0].equals(receipt)) {
                if(expectedReceipt!=null) throw new WorkFailure(WorkFailure.Code.LEASE_REJECTED);
                throw FrozenAssessmentLoader.unavailable();
            }
            if(!json.equals(FinalizedFactors.details(factor,s,receipt))) throw FrozenAssessmentLoader.unavailable(); return factor;
        });
        var result=new RiskResult(RiskResult.Status.COMPLETED,s.input().current().submissionId(),s.config().versionId(),s.config().rowVersion(),s.config().contentHash(),
            s.input().cutoff(),BigInteger.valueOf(p.raw()),p.score(),p.band(),factors,s.config(),s.input());
        FinalizedFactors.validate(result,s);
        return new CompletedAssessment(Long.toString(row.id()),Long.toString(row.revision()),s.evaluator(),s.accepted().acceptanceHash(),
            s.accepted().configurationHash(),s.header().hash(),p.evaluated(),p.completed(),result);
    }
    private record Parent(long raw,int score,String band,int count,Instant evaluated,Instant completed) {}
    private void bound(boolean write) {
        if(!TransactionSynchronizationManager.isActualTransactionActive() || !TransactionSynchronizationManager.hasResource(jdbc.getDataSource())
            || write && TransactionSynchronizationManager.isCurrentTransactionReadOnly()) throw new IllegalStateException("Bound finalization transaction required");
    }
    private static void time(PreparedStatement ps,int i,Instant time) throws SQLException { ps.setObject(i,time.atOffset(ZoneOffset.UTC),Types.TIMESTAMP_WITH_TIMEZONE); }
    private static Instant time(ResultSet rs,String name) throws SQLException {
        var t=rs.getObject(name,OffsetDateTime.class); if(t==null) throw FrozenAssessmentLoader.unavailable();
        PersistenceValues.timestamp(t.toInstant()); return t.toInstant();
    }
    private static String clob(ResultSet rs) throws SQLException {
        try(var reader=rs.getCharacterStream("DETAILS_JSON")) {
            if(reader==null) throw FrozenAssessmentLoader.unavailable(); var writer=new StringWriter(); reader.transferTo(writer); return writer.toString();
        } catch(IOException e) { throw FrozenAssessmentLoader.unavailable(); }
    }
}
