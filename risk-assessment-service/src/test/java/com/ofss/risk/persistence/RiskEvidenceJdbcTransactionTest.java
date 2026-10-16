package com.ofss.risk.persistence;

import java.io.StringReader;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Actual JDBC repository/template/transaction manager, mocked JDBC driver. Not Oracle integration proof. */
class RiskEvidenceJdbcTransactionTest {
    final DataSource ds=mock(DataSource.class); final Connection connection=mock(Connection.class);
    final PreparedStatement lock=mock(PreparedStatement.class),read=mock(PreparedStatement.class),header=mock(PreparedStatement.class),
        childrenQuery=mock(PreparedStatement.class),insert=mock(PreparedStatement.class),associate=mock(PreparedStatement.class),reuse=mock(PreparedStatement.class);
    final Map<Integer,Object> insertBindings=new HashMap<>(),associationBindings=new HashMap<>();
    final Map<Clob,String> texts=new IdentityHashMap<>(); final List<Clob> allocated=new ArrayList<>();
    final List<RiskEvidenceRepository.Stored> children=new ArrayList<>();
    final AtomicReference<RiskEvidenceRepository.Header> frozen=new AtomicReference<>(new RiskEvidenceRepository.Header(null,null,null));
    final AtomicLong generated=new AtomicLong(9007199254740993L); final AtomicBoolean associated=new AtomicBoolean();
    RiskEvidenceRepository repository; RiskEvidenceService service;
    @BeforeEach void setup() throws Exception {
        when(ds.getConnection()).thenReturn(connection); when(connection.getAutoCommit()).thenReturn(true);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        when(connection.prepareStatement(RiskWorkRepository.LOCK)).thenReturn(lock);
        when(connection.prepareStatement(RiskWorkRepository.READ)).thenReturn(read);
        when(connection.prepareStatement(RiskEvidenceRepository.HEADER)).thenReturn(header);
        when(connection.prepareStatement(RiskEvidenceRepository.CHILDREN)).thenReturn(childrenQuery);
        when(connection.prepareStatement(eq(RiskEvidenceRepository.INSERT),any(String[].class))).thenReturn(insert);
        when(connection.prepareStatement(RiskEvidenceRepository.ASSOCIATE)).thenReturn(associate);
        when(connection.prepareStatement(RiskEvidenceRepository.REUSE)).thenReturn(reuse);
        bindings(insert,insertBindings); bindings(associate,associationBindings);
        when(connection.createClob()).thenAnswer(c->{ var clob=mock(Clob.class); allocated.add(clob);
            when(clob.setString(eq(1L),anyString())).thenAnswer(call->{ String json=call.getArgument(1); texts.put(clob,json); return json.length(); }); return clob; });
        when(lock.executeQuery()).thenAnswer(c->workRow()); when(read.executeQuery()).thenAnswer(c->workRow());
        when(header.executeQuery()).thenAnswer(c->headerRow()); when(childrenQuery.executeQuery()).thenAnswer(c->childRows());
        when(insert.executeUpdate()).thenAnswer(c->{
            long id=generated.getAndIncrement(); children.add(new RiskEvidenceRepository.Stored(id,(String)insertBindings.get(2),(String)insertBindings.get(3),(String)insertBindings.get(4),
                new EvidenceFreezeRequest.Source((String)insertBindings.get(5),(String)insertBindings.get(6),(String)insertBindings.get(8)),(String)insertBindings.get(7),
                (String)insertBindings.get(9),(String)insertBindings.get(10),instant(insertBindings.get(11)),instant(insertBindings.get(12)),instant(insertBindings.get(13)),
                (Integer)insertBindings.get(14),(String)insertBindings.get(15),texts.get((Clob)insertBindings.get(16)),(String)insertBindings.get(17),instant(insertBindings.get(18)))); return 1;
        });
        when(insert.getGeneratedKeys()).thenAnswer(c->{ var rs=mock(ResultSet.class); var meta=mock(ResultSetMetaData.class);
            when(rs.next()).thenReturn(true,false); when(rs.getMetaData()).thenReturn(meta); when(meta.getColumnCount()).thenReturn(1);
            when(rs.getObject(1)).thenReturn(BigDecimal.valueOf(generated.get()-1)); return rs; });
        when(associate.executeUpdate()).thenAnswer(c->{ associated.set(true); frozen.set(new RiskEvidenceRepository.Header((String)associationBindings.get(1),
            (Long)associationBindings.get(2),instant(associationBindings.get(3)))); return 1; });
        when(reuse.executeUpdate()).thenReturn(1);
        var jdbc=new JdbcTemplate(ds); jdbc.setExceptionTranslator(new SQLExceptionSubclassTranslator()); repository=new RiskEvidenceRepository(jdbc);
        var accepted=mock(RiskAcceptanceRepository.class); when(accepted.find(FreezeFixtures.acceptance().identity())).thenReturn(Optional.of(FreezeFixtures.stored(1)));
        service=new RiskEvidenceService(new RiskWorkRepository(jdbc),accepted,repository,new JdbcTransactionManager(ds),Clock.fixed(FreezeFixtures.NOW,ZoneOffset.UTC));
    }
    static Instant instant(Object value) { return value==null?null:((OffsetDateTime)value).toInstant(); }
    void bindings(PreparedStatement ps,Map<Integer,Object> values) throws Exception {
        doAnswer(c->{ values.put(c.getArgument(0),c.getArgument(1)); return null; }).when(ps).setString(anyInt(),nullable(String.class));
        doAnswer(c->{ values.put(c.getArgument(0),c.getArgument(1)); return null; }).when(ps).setLong(anyInt(),anyLong());
        doAnswer(c->{ values.put(c.getArgument(0),c.getArgument(1)); return null; }).when(ps).setInt(anyInt(),anyInt());
        doAnswer(c->{ values.put(c.getArgument(0),c.getArgument(1)); return null; }).when(ps).setObject(anyInt(),any(),anyInt());
        doAnswer(c->{ values.put(c.getArgument(0),null); return null; }).when(ps).setNull(anyInt(),anyInt());
        doAnswer(c->{ values.put(c.getArgument(0),c.getArgument(1)); return null; }).when(ps).setClob(anyInt(),any(Clob.class));
    }
    ResultSet workRow() throws Exception {
        var row=FreezeFixtures.row(); var rs=mock(ResultSet.class); when(rs.next()).thenReturn(true,false);
        for(var entry:Map.of("ASSESSMENT_ID",17L,"CONFIGURATION_SNAPSHOT_ID",23L,"LEASE_EPOCH",1L,"ATTEMPT_COUNT",1L,"ROW_VERSION",associated.get()?2L:1L).entrySet())
            when(rs.getObject(entry.getKey())).thenReturn(BigDecimal.valueOf(entry.getValue()));
        when(rs.getString("WORK_STATE")).thenReturn("IN_PROGRESS"); when(rs.getString("LEASE_TOKEN")).thenReturn(row.token());
        when(rs.getObject("LEASE_EXPIRES_AT",OffsetDateTime.class)).thenReturn(row.expiry().atOffset(ZoneOffset.UTC));
        when(rs.getObject("CREATED_AT",OffsetDateTime.class)).thenReturn(row.created().atOffset(ZoneOffset.UTC));
        when(rs.getObject("EVIDENCE_AS_OF",OffsetDateTime.class)).thenReturn(row.cutoff().atOffset(ZoneOffset.UTC)); return rs;
    }
    ResultSet headerRow() throws Exception {
        var rs=mock(ResultSet.class); when(rs.next()).thenReturn(true,false); var value=frozen.get();
        when(rs.getString("INPUT_HASH")).thenReturn(value.hash()); when(rs.getObject("FROZEN_INPUT_EVIDENCE_ID")).thenReturn(value.manifest()==null?null:BigDecimal.valueOf(value.manifest()));
        when(rs.getObject("INPUT_FROZEN_AT",OffsetDateTime.class)).thenReturn(value.frozenAt()==null?null:value.frozenAt().atOffset(ZoneOffset.ofHoursMinutes(5,30))); return rs;
    }
    ResultSet childRows() throws Exception {
        var rs=mock(ResultSet.class); var copied=List.copyOf(children); var index=new AtomicInteger(-1);
        when(rs.next()).thenAnswer(c->index.incrementAndGet()<copied.size());
        when(rs.getObject(anyString())).thenAnswer(c->{ var r=copied.get(index.get()); return switch((String)c.getArgument(0)) {
            case "ASSESSMENT_ID" -> BigDecimal.valueOf(17); case "ATTEMPT_NO" -> BigDecimal.ZERO;
            case "EVIDENCE_ID" -> BigDecimal.valueOf(r.id()); case "RECORD_COUNT" -> r.count()==null?null:BigDecimal.valueOf(r.count()); default -> null; }; });
        when(rs.getString(anyString())).thenAnswer(c->{ var r=copied.get(index.get()); return switch((String)c.getArgument(0)) {
            case "RECORD_CLASS" -> r.recordClass(); case "EVIDENCE_KEY" -> r.key(); case "EVIDENCE_KIND" -> r.kind(); case "SOURCE_SERVICE" -> r.source().service();
            case "SOURCE_RECORD_REFERENCE" -> r.source().recordReference(); case "SOURCE_REVISION" -> r.source().revision(); case "SOURCE_SNAPSHOT_REFERENCE" -> r.snapshot();
            case "EVIDENCE_FORMAT_VERSION" -> r.format(); case "HASH_FORMAT_VERSION" -> r.hashFormat(); case "AVAILABILITY" -> r.availability(); case "CONTENT_HASH" -> r.hash(); default -> null; }; });
        when(rs.getObject(anyString(),eq(OffsetDateTime.class))).thenAnswer(c->{ var r=copied.get(index.get()); Instant time=switch((String)c.getArgument(0)) {
            case "AS_OF_AT" -> r.cutoff(); case "WINDOW_START_AT" -> r.coveredFrom(); case "WINDOW_END_AT" -> r.windowEnd(); case "CAPTURED_AT" -> r.captured(); default -> null; };
            return time==null?null:time.atOffset(ZoneOffset.ofHoursMinutes(5,30)); });
        when(rs.getCharacterStream("EVIDENCE_JSON")).thenAnswer(c->new StringReader(copied.get(index.get()).json())); return rs;
    }
    EvidenceFreezeRequest request() { return FreezeFixtures.request(FreezeFixtures.row().lease(),FreezeFixtures.input()); }
    void failure(FreezeFailure.Code code,Runnable action) {
        var e=assertThrows(FreezeFailure.class,action::run); assertEquals(code,e.code()); assertNull(e.getCause()); assertFalse(e.getMessage().contains("private"));
    }
    void rollback() throws Exception { verify(connection).rollback(); verify(connection,never()).commit(); verify(ds).getConnection(); }
    @Test void insertsSixLocatorClobsRetrievesExactIdentitiesAssociatesReadsBackAndCommits() throws Exception {
        var result=service.freeze(request()); assertEquals("9007199254740998",result.manifestId()); assertEquals("2",result.lease().rowVersion());
        verify(insert,times(6)).executeUpdate(); verify(insert,times(6)).getGeneratedKeys();
        for(var clob:allocated) verify(clob).free(); verify(insert,times(6)).setClob(eq(16),any(Clob.class));
        verify(connection,times(6)).prepareStatement(RiskEvidenceRepository.INSERT,new String[]{"EVIDENCE_ID"});
        verify(insert,times(6)).setObject(18,FreezeFixtures.NOW.atOffset(ZoneOffset.UTC),Types.TIMESTAMP_WITH_TIMEZONE);
        verify(insert,times(2)).setNull(12,Types.TIMESTAMP_WITH_TIMEZONE);
        verify(associate).setString(6,FreezeFixtures.row().token()); verify(associate).setLong(7,1); verify(associate).setInt(8,1); verify(associate).setLong(9,1);
        verify(associate).setObject(10,FreezeFixtures.row().expiry().atOffset(ZoneOffset.UTC),Types.TIMESTAMP_WITH_TIMEZONE);
        verify(connection).commit(); verify(connection,never()).rollback(); assertTrue(result.canonicalInputJson().contains("supplier-ಕನ್ನಡ😀"));
    }
    @ParameterizedTest @ValueSource(ints={2,6}) void childInsertFailureRollsBackPreviouslyInsertedRows(int at) throws Exception {
        var count=new AtomicInteger();
        doAnswer(c->{ if(count.incrementAndGet()==at) throw new SQLException("private evidence payload"); return 1; }).when(insert).executeUpdate();
        failure(FreezeFailure.Code.EVIDENCE_PERSISTENCE_UNAVAILABLE,()->service.freeze(request()));
        verify(insert,times(at)).executeUpdate(); verify(associate,never()).executeUpdate(); rollback();
        assertEquals(at,allocated.size()); for(var clob:allocated) verify(clob).free();
    }
    @ParameterizedTest @ValueSource(strings={"fraction","zero","overflow","missing"}) void invalidGeneratedIdentityFailsAfterInsertAndRollsBack(String defect) throws Exception {
        var keys=mock(ResultSet.class); var meta=mock(ResultSetMetaData.class); when(keys.getMetaData()).thenReturn(meta); when(meta.getColumnCount()).thenReturn(1);
        when(keys.next()).thenReturn(!defect.equals("missing"),false);
        when(keys.getObject(1)).thenReturn(new BigDecimal(switch(defect) { case "fraction" -> "1.1"; case "zero" -> "0"; default -> "9223372036854775808"; }));
        doReturn(keys).when(insert).getGeneratedKeys();
        failure(FreezeFailure.Code.EVIDENCE_PERSISTENCE_UNAVAILABLE,()->service.freeze(request()));
        verify(insert).executeUpdate(); verify(associate,never()).executeUpdate(); verify(allocated.getFirst()).free(); rollback();
    }
    @Test void finalExpiryGuardFailureRollsBackAllSixChildren() throws Exception {
        doReturn(0).when(associate).executeUpdate();
        assertEquals(WorkFailure.Code.LEASE_REJECTED,assertThrows(WorkFailure.class,()->service.freeze(request())).code());
        verify(insert,times(6)).executeUpdate(); verify(associate).executeUpdate(); rollback();
    }
    @Test void failedParentReadbackRollsBackAssociationAndChildren() throws Exception {
        doThrow(new SQLException("private parent readback")).when(read).executeQuery();
        failure(FreezeFailure.Code.EVIDENCE_PERSISTENCE_UNAVAILABLE,()->service.freeze(request()));
        verify(associate).executeUpdate(); verify(insert,times(6)).executeUpdate(); rollback();
    }
    @Test void missingParentReadbackIsPersistenceFailureAndRollsBackAssociationAndChildren() throws Exception {
        var missing=mock(ResultSet.class); when(missing.next()).thenReturn(false); doReturn(missing).when(read).executeQuery();
        failure(FreezeFailure.Code.EVIDENCE_PERSISTENCE_UNAVAILABLE,()->service.freeze(request()));
        verify(associate).executeUpdate(); verify(insert,times(6)).executeUpdate(); rollback();
    }
    @Test void corruptedClobReadbackRollsBackAssociationAndChildren() throws Exception {
        when(childrenQuery.executeQuery()).thenAnswer(c->{
            if(children.isEmpty()) return childRows(); var rs=childRows();
            doAnswer(call->new StringReader("{\"changed\":true}")).when(rs).getCharacterStream("EVIDENCE_JSON"); return rs;
        });
        failure(FreezeFailure.Code.EVIDENCE_PERSISTENCE_UNAVAILABLE,()->service.freeze(request())); verify(associate).executeUpdate(); rollback();
    }
    @Test void noTransactionCannotReadInsertOrAssociateEvidence() {
        assertThrows(IllegalStateException.class,()->repository.header(17)); assertThrows(IllegalStateException.class,()->repository.children(17));
        assertThrows(IllegalStateException.class,()->repository.insert(17,null)); verifyNoInteractions(ds);
    }
    @Test void locatorRemainsAliveUntilGeneratedKeysAreReadAndStatementIsClosed() throws Exception {
        service.freeze(request()); var order=inOrder(insert,allocated.getFirst());
        order.verify(insert).setClob(16,allocated.getFirst()); order.verify(insert).executeUpdate();
        order.verify(insert).getGeneratedKeys(); order.verify(insert).close(); order.verify(allocated.getFirst()).free();
    }
    @Test void bindingFailureClosesStatementFreesLocatorAndRollsBackBeforeExecution() throws Exception {
        doThrow(new SQLException("synthetic binding failure")).when(insert).setClob(eq(16),any(Clob.class));
        failure(FreezeFailure.Code.EVIDENCE_PERSISTENCE_UNAVAILABLE,()->service.freeze(request()));
        verify(insert).close(); verify(allocated.getFirst()).free(); verify(insert,never()).executeUpdate(); rollback();
    }
    @Test void sqlOnlyAppendsAttemptZeroRowsAndAssociatesWithoutScoresOrLifecycleChanges() {
        assertTrue(RiskEvidenceRepository.INSERT.contains("VALUES (?, ?, 0,"));
        String set=RiskEvidenceRepository.ASSOCIATE.substring(0,RiskEvidenceRepository.ASSOCIATE.indexOf("WHERE"));
        for(String forbidden:List.of("ACCEPTANCE_","CONFIGURATION_","SCORE","RISK_BAND","WORK_STATE","ATTEMPT_COUNT","LEASE_EPOCH")) assertFalse(set.contains(forbidden));
        for(String fence:List.of("LEASE_TOKEN=?","LEASE_EPOCH=?","ATTEMPT_COUNT=?","ROW_VERSION=?","LEASE_EXPIRES_AT=?","LEASE_EXPIRES_AT>SYSTIMESTAMP"))
            assertTrue(RiskEvidenceRepository.FENCE.contains(fence));
        assertTrue(RiskEvidenceRepository.CHILDREN.contains("ATTEMPT_NO=0"));
    }
}
