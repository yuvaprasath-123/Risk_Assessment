package com.ofss.risk.persistence;

import java.io.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Append-only attempt-0 inputs; never touches diagnostics, snapshots or scoring columns. */
public class RiskEvidenceRepository {
    static final String HEADER="SELECT INPUT_HASH, FROZEN_INPUT_EVIDENCE_ID, INPUT_FROZEN_AT FROM D2_RISK.RISK_ASSESSMENTS WHERE ASSESSMENT_ID=?";
    static final String CHILDREN="SELECT * FROM D2_RISK.RISK_INPUT_EVIDENCE WHERE ASSESSMENT_ID=? AND ATTEMPT_NO=0 ORDER BY EVIDENCE_KEY";
    static final String INSERT="""
        INSERT INTO D2_RISK.RISK_INPUT_EVIDENCE
          (ASSESSMENT_ID,RECORD_CLASS,ATTEMPT_NO,EVIDENCE_KEY,EVIDENCE_KIND,SOURCE_SERVICE,
           SOURCE_RECORD_REFERENCE,SOURCE_SNAPSHOT_REFERENCE,SOURCE_REVISION,EVIDENCE_FORMAT_VERSION,
           HASH_FORMAT_VERSION,AS_OF_AT,WINDOW_START_AT,WINDOW_END_AT,RECORD_COUNT,AVAILABILITY,
           EVIDENCE_JSON,CONTENT_HASH,CAPTURED_AT)
        VALUES (?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;
    static final String FENCE="""
        WHERE ASSESSMENT_ID=? AND WORK_STATE='IN_PROGRESS' AND LEASE_TOKEN=? AND LEASE_EPOCH=?
          AND ATTEMPT_COUNT=? AND ROW_VERSION=? AND LEASE_EXPIRES_AT=? AND LEASE_EXPIRES_AT>SYSTIMESTAMP
        """;
    static final String ASSOCIATE="""
        UPDATE D2_RISK.RISK_ASSESSMENTS SET INPUT_HASH=?, FROZEN_INPUT_EVIDENCE_ID=?, INPUT_FROZEN_AT=?, ROW_VERSION=?
        """+FENCE+" AND INPUT_HASH IS NULL AND FROZEN_INPUT_EVIDENCE_ID IS NULL AND INPUT_FROZEN_AT IS NULL";
    static final String REUSE="UPDATE D2_RISK.RISK_ASSESSMENTS SET ROW_VERSION=ROW_VERSION "+FENCE+
        " AND INPUT_HASH=? AND FROZEN_INPUT_EVIDENCE_ID=? AND INPUT_FROZEN_AT=?";
    record Header(String hash,Long manifest,Instant frozenAt) {}
    record Stored(long id,String recordClass,String key,String kind,EvidenceFreezeRequest.Source source,String snapshot,
                  String format,String hashFormat,Instant cutoff,Instant coveredFrom,Instant windowEnd,Integer count,
                  String availability,String json,String hash,Instant captured) {
        Stored withId(long id) { return new Stored(id,recordClass,key,kind,source,snapshot,format,hashFormat,cutoff,coveredFrom,windowEnd,count,availability,json,hash,captured); }
    }
    private final JdbcTemplate jdbc;
    public RiskEvidenceRepository(JdbcTemplate jdbc) { this.jdbc=Objects.requireNonNull(jdbc); }
    Header header(long id) {
        readBound(); var rows=jdbc.query(HEADER,ps->ps.setLong(1,id),(rs,n)-> {
            Object manifest=rs.getObject("FROZEN_INPUT_EVIDENCE_ID");
            var value=new Header(rs.getString("INPUT_HASH"),manifest==null?null:PersistenceValues.number(manifest,true),time(rs,"INPUT_FROZEN_AT"));
            if(value.hash()==null ? value.manifest()!=null || value.frozenAt()!=null : value.manifest()==null || value.frozenAt()==null
                || !value.hash().matches("[0-9a-f]{64}")) throw invalid();
            return value;
        });
        if(rows.size()!=1) throw invalid(); return rows.getFirst();
    }
    List<Stored> children(long id) {
        readBound(); return jdbc.query(CHILDREN,ps->ps.setLong(1,id),(rs,n)-> {
            if(PersistenceValues.number(rs.getObject("ASSESSMENT_ID"),true)!=id
                || PersistenceValues.number(rs.getObject("ATTEMPT_NO"),false)!=0) throw invalid();
            Object count=rs.getObject("RECORD_COUNT");
            long numericCount=count==null?0:PersistenceValues.number(count,false); if(numericCount>Integer.MAX_VALUE) throw invalid();
            var value=new Stored(PersistenceValues.number(rs.getObject("EVIDENCE_ID"),true),rs.getString("RECORD_CLASS"),
                rs.getString("EVIDENCE_KEY"),rs.getString("EVIDENCE_KIND"),new EvidenceFreezeRequest.Source(rs.getString("SOURCE_SERVICE"),
                rs.getString("SOURCE_RECORD_REFERENCE"),rs.getString("SOURCE_REVISION")),rs.getString("SOURCE_SNAPSHOT_REFERENCE"),
                rs.getString("EVIDENCE_FORMAT_VERSION"),rs.getString("HASH_FORMAT_VERSION"),time(rs,"AS_OF_AT"),
                time(rs,"WINDOW_START_AT"),time(rs,"WINDOW_END_AT"),count==null?null:(int)numericCount,
                rs.getString("AVAILABILITY"),clob(rs),rs.getString("CONTENT_HASH"),time(rs,"CAPTURED_AT"));
            if(!CanonicalAcceptance.hash(value.json()).equals(value.hash()) || value.cutoff()==null || value.captured()==null) throw invalid();
            return value;
        });
    }
    Stored insert(long parent,Stored row) {
        bound(); Clob[] allocated=new Clob[1];
        try {
            long id=jdbc.execute((PreparedStatementCreator) connection -> {
                var ps=connection.prepareStatement(INSERT,new String[]{"EVIDENCE_ID"});
                try {
                    ps.setLong(1,parent); ps.setString(2,row.recordClass()); ps.setString(3,row.key()); ps.setString(4,row.kind());
                    ps.setString(5,row.source().service()); text(ps,6,row.source().recordReference()); text(ps,7,row.snapshot()); text(ps,8,row.source().revision());
                    ps.setString(9,row.format()); ps.setString(10,row.hashFormat()); time(ps,11,row.cutoff()); time(ps,12,row.coveredFrom()); time(ps,13,row.windowEnd());
                    if(row.count()==null) ps.setNull(14,Types.NUMERIC); else ps.setInt(14,row.count());
                    ps.setString(15,row.availability());
                    allocated[0]=connection.createClob();
                    if(allocated[0]==null || allocated[0].setString(1,row.json())!=row.json().length()) throw invalid();
                    ps.setClob(16,allocated[0]); ps.setString(17,row.hash()); time(ps,18,row.captured()); return ps;
                } catch(SQLException | RuntimeException e) { ps.close(); throw e; }
            },ps -> {
                if(ps.executeUpdate()!=1) throw invalid();
                try(var keys=ps.getGeneratedKeys()) {
                    if(keys==null || !keys.next() || keys.getMetaData().getColumnCount()!=1) throw invalid();
                    long key=PersistenceValues.number(keys.getObject(1),true); if(keys.next()) throw invalid(); return key;
                }
            });
            return row.withId(id);
        } finally {
            if(allocated[0]!=null) try { allocated[0].free(); }
            catch(SQLException e) { throw new UncategorizedSQLException("Evidence CLOB cleanup",null,e); }
        }
    }
    void associate(RiskWorkRepository.Row parent,Header header,long revision) {
        bound(); int count=jdbc.update(ASSOCIATE,ps -> {
            ps.setString(1,header.hash()); ps.setLong(2,header.manifest()); time(ps,3,header.frozenAt()); ps.setLong(4,revision);
            fence(ps,5,parent);
        });
        if(count!=1) throw new WorkFailure(WorkFailure.Code.LEASE_REJECTED);
    }
    void reuse(RiskWorkRepository.Row parent,Header header) {
        bound(); int count=jdbc.update(REUSE,ps -> {
            fence(ps,1,parent); ps.setString(7,header.hash()); ps.setLong(8,header.manifest()); time(ps,9,header.frozenAt());
        });
        if(count!=1) throw new WorkFailure(WorkFailure.Code.LEASE_REJECTED);
    }
    private static void fence(PreparedStatement ps,int start,RiskWorkRepository.Row row) throws SQLException {
        ps.setLong(start,row.id()); ps.setString(start+1,row.token()); ps.setLong(start+2,row.epoch());
        ps.setInt(start+3,row.attempt()); ps.setLong(start+4,row.revision()); time(ps,start+5,row.expiry());
    }
    private void bound() {
        if(!TransactionSynchronizationManager.isActualTransactionActive() || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
            || !TransactionSynchronizationManager.hasResource(jdbc.getDataSource())) throw new IllegalStateException("Evidence requires its bound writable transaction");
    }
    private void readBound() {
        if(!TransactionSynchronizationManager.isActualTransactionActive() || !TransactionSynchronizationManager.hasResource(jdbc.getDataSource()))
            throw new IllegalStateException("Evidence reads require their bound transaction");
    }
    private static Instant time(ResultSet rs,String column) throws SQLException {
        var value=rs.getObject(column,OffsetDateTime.class); if(value==null) return null;
        var instant=value.toInstant(); PersistenceValues.timestamp(instant); return instant;
    }
    private static void time(PreparedStatement ps,int column,Instant value) throws SQLException {
        if(value==null) ps.setNull(column,Types.TIMESTAMP_WITH_TIMEZONE); else ps.setObject(column,value.atOffset(ZoneOffset.UTC),Types.TIMESTAMP_WITH_TIMEZONE);
    }
    private static void text(PreparedStatement ps,int column,String value) throws SQLException {
        if(value==null) ps.setNull(column,Types.VARCHAR); else ps.setString(column,value);
    }
    private static String clob(ResultSet rs) throws SQLException {
        try(var reader=rs.getCharacterStream("EVIDENCE_JSON")) {
            if(reader==null) throw invalid(); var text=new StringWriter(); reader.transferTo(text); return text.toString();
        } catch(IOException e) { throw invalid(); }
    }
    private static DataRetrievalFailureException invalid() { return new DataRetrievalFailureException("Invalid frozen evidence"); }
}
