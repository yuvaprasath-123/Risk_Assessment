package com.ofss.risk.persistence;

import com.ofss.risk.engine.*;
import java.io.StringReader;
import java.math.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.*;
import org.springframework.transaction.support.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real Spring transaction manager and finalization JDBC; mocked driver/immutable loader, not Oracle proof. */
class RiskFinalizationTransactionTest {
    final DataSource ds=mock(DataSource.class); final Connection connection=mock(Connection.class);
    final Statement snapshotStatement=mock(Statement.class);
    final PreparedStatement insert=mock(PreparedStatement.class),complete=mock(PreparedStatement.class),result=mock(PreparedStatement.class),
        factors=mock(PreparedStatement.class),existing=mock(PreparedStatement.class);
    final Map<Integer,Object> insertValues=new HashMap<>(),completeValues=new HashMap<>();
    final Map<Clob,String> texts=new IdentityHashMap<>(); final List<Clob> clobs=new ArrayList<>();
    final List<Map<Integer,Object>> factorRows=new ArrayList<>(); final List<Map<Integer,Object>> committedFactors=new ArrayList<>();
    final FrozenAssessmentLoader loader=mock(FrozenAssessmentLoader.class); final RiskWorkRepository work=mock(RiskWorkRepository.class);
    final AtomicReference<FrozenAssessmentLoader.Snapshot> parent=new AtomicReference<>(FinalizationFixtures.snapshot());
    final FinalizationRequest request=FinalizationFixtures.request(parent.get());
    final AtomicReference<Instant> now=new AtomicReference<>(FreezeFixtures.NOW);
    final AtomicInteger evaluations=new AtomicInteger();
    RiskFinalizationRepository repository; RiskFinalizationService service; JdbcTransactionManager manager;
    @BeforeEach void setup() throws Exception {
        when(ds.getConnection()).thenReturn(connection); when(connection.getAutoCommit()).thenReturn(true);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        when(connection.createStatement()).thenReturn(snapshotStatement);
        when(connection.prepareStatement(RiskFinalizationRepository.INSERT)).thenReturn(insert);
        when(connection.prepareStatement(RiskFinalizationRepository.COMPLETE)).thenReturn(complete);
        when(connection.prepareStatement(RiskFinalizationRepository.RESULT)).thenReturn(result);
        when(connection.prepareStatement(RiskFinalizationRepository.FACTORS)).thenReturn(factors);
        when(connection.prepareStatement("SELECT FACTOR_ID FROM D2_RISK.RISK_ASSESSMENT_FACTORS WHERE ASSESSMENT_ID=?")).thenReturn(existing);
        bindings(insert,insertValues); bindings(complete,completeValues);
        when(connection.createClob()).thenAnswer(c->{ var clob=mock(Clob.class); clobs.add(clob);
            when(clob.setString(eq(1L),anyString())).thenAnswer(call->{String json=call.getArgument(1); texts.put(clob,json); return json.length();}); return clob; });
        when(insert.executeUpdate()).thenAnswer(c->{ var row=new HashMap<>(insertValues); row.put(10,texts.get((Clob)insertValues.get(10))); factorRows.add(row); return 1; });
        when(complete.executeUpdate()).thenAnswer(c->{parent.set(FinalizationFixtures.completed(parent.get())); return 1;});
        when(existing.executeQuery()).thenAnswer(c->{var rs=mock(ResultSet.class); when(rs.next()).thenReturn(!factorRows.isEmpty(),false); return rs;});
        when(result.executeQuery()).thenAnswer(c->parentResult()); when(factors.executeQuery()).thenAnswer(c->factorResult());
        when(loader.load(any())).thenAnswer(c->parent.get()); when(work.lock(17)).thenAnswer(c->parent.get().work());
        doAnswer(c->{ if(!TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {committedFactors.clear(); committedFactors.addAll(factorRows);} return null; }).when(connection).commit();
        doAnswer(c->{ factorRows.clear(); factorRows.addAll(committedFactors); parent.set(FinalizationFixtures.snapshot()); return null; }).when(connection).rollback();
        var jdbc=new JdbcTemplate(ds); jdbc.setExceptionTranslator(new SQLExceptionSubclassTranslator()); repository=new RiskFinalizationRepository(jdbc);
        manager=new JdbcTransactionManager(ds); service=service(new RiskScoringEngine()::evaluate);
    }
    RiskFinalizationService service(PinnedRiskEvaluators.Evaluator evaluator) {
        var registry=new PinnedRiskEvaluators(Map.of(PinnedRiskEvaluators.CURRENT,(c,i)->{
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),"Engine must run outside a transaction");
            evaluations.incrementAndGet(); return evaluator.evaluate(c,i);
        }));
        Clock clock=new Clock() { public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId z){return this;} public Instant instant(){return now.get();} };
        return new RiskFinalizationService(loader,work,repository,registry,manager,clock);
    }
    void bindings(PreparedStatement ps,Map<Integer,Object> map) throws Exception {
        doAnswer(c->{map.put(c.getArgument(0),c.getArgument(1)); return null;}).when(ps).setString(anyInt(),nullable(String.class));
        doAnswer(c->{map.put(c.getArgument(0),c.getArgument(1)); return null;}).when(ps).setLong(anyInt(),anyLong());
        doAnswer(c->{map.put(c.getArgument(0),c.getArgument(1)); return null;}).when(ps).setInt(anyInt(),anyInt());
        doAnswer(c->{map.put(c.getArgument(0),c.getArgument(1)); return null;}).when(ps).setObject(anyInt(),any(),anyInt());
        doAnswer(c->{map.put(c.getArgument(0),c.getArgument(1)); return null;}).when(ps).setClob(anyInt(),any(Clob.class));
        doAnswer(c->{map.put(c.getArgument(0),null); return null;}).when(ps).setNull(anyInt(),anyInt());
    }
    ResultSet parentResult() throws Exception {
        var rs=mock(ResultSet.class); when(rs.next()).thenReturn(true,false);
        when(rs.getObject(anyString())).thenAnswer(c->switch((String)c.getArgument(0)) {
            case "TOTAL_RAW_SCORE" -> BigDecimal.valueOf((Long)completeValues.get(1)); case "FINAL_SCORE" -> BigDecimal.valueOf((Integer)completeValues.get(2));
            case "FINAL_FACTOR_COUNT" -> BigDecimal.valueOf(7); default -> null; });
        when(rs.getString("RISK_BAND")).thenAnswer(c->completeValues.get(3));
        when(rs.getObject(anyString(),eq(OffsetDateTime.class))).thenAnswer(c->completeValues.get(((String)c.getArgument(0)).equals("EVALUATED_AT")?4:5)); return rs;
    }
    ResultSet factorResult() throws Exception {
        var rs=mock(ResultSet.class); var index=new AtomicInteger(-1); var rows=List.copyOf(factorRows);
        when(rs.next()).thenAnswer(c->index.incrementAndGet()<rows.size());
        when(rs.getObject(anyString())).thenAnswer(c->{var row=rows.get(index.get()); Object value=switch((String)c.getArgument(0)) {
            case "ASSESSMENT_ID" -> row.get(1); case "RULE_CONFIG_SOURCE_ID" -> null; case "FACTOR_ORDER" -> row.get(3);
            case "CONFIGURED_WEIGHT" -> row.get(5); case "SCORE_CONTRIBUTION" -> row.get(6); case "PRIMARY_EVIDENCE_ID" -> row.get(7); default -> null;};
            return value==null?null:new BigDecimal(value.toString()); });
        when(rs.getString(anyString())).thenAnswer(c->{var row=rows.get(index.get()); return switch((String)c.getArgument(0)) {
            case "RULE_CODE" -> row.get(2); case "EVALUATION_OUTCOME" -> row.get(4); case "EXPLANATION" -> row.get(8); case "RESULT_FORMAT_VERSION" -> row.get(9); default -> null; }; });
        when(rs.getObject("EVALUATED_AT",OffsetDateTime.class)).thenAnswer(c->rows.get(index.get()).get(11));
        when(rs.getCharacterStream("DETAILS_JSON")).thenAnswer(c->new StringReader((String)rows.get(index.get()).get(10))); return rs;
    }
    FinalizationFailure fails(FinalizationFailure.Code code) {
        var e=assertThrows(FinalizationFailure.class,()->service.finalizeAssessment(request)); assertEquals(code,e.code()); assertNull(e.getCause()); return e;
    }
    void noFinalCommit() throws Exception { assertTrue(committedFactors.isEmpty()); assertEquals("IN_PROGRESS",parent.get().work().state()); verify(connection,atLeastOnce()).rollback(); }
    @Test void sevenFactorsExactDetailsScoresAndCompletionCommitTogetherAndRetryDoesNotEvaluate() throws Exception {
        var saved=service.finalizeAssessment(request); assertEquals(135,saved.result().rawTotal().intValueExact()); assertEquals(100,saved.result().finalScore()); assertEquals("HIGH",saved.result().riskBand());
        assertEquals(7,committedFactors.size()); assertTrue(saved.result().factors().stream().allMatch(f->f.outcome()==RiskResult.Outcome.TRIGGERED));
        assertTrue(committedFactors.toString().contains("payment-ಕನ್ನಡ😀")); assertEquals(FreezeFixtures.NOW,saved.completedAt());
        assertEquals(123456789,saved.evaluatedAt().getNano()); assertEquals("2",saved.rowVersion());
        now.set(now.get().plusSeconds(500)); var retried=service.finalizeAssessment(request); assertEquals(saved,retried); assertEquals(1,evaluations.get());
        verify(insert,times(7)).executeUpdate(); verify(complete).executeUpdate();
        for(var clob:clobs) verify(clob).free(); verify(insert,times(7)).setClob(eq(10),any(Clob.class));
        verify(snapshotStatement,times(4)).executeUpdate("SET TRANSACTION READ ONLY");
    }
    @ParameterizedTest @ValueSource(ints={1,3,7}) void partialFactorInsertFailureRollsBackAllWrites(int at) throws Exception {
        AtomicInteger calls=new AtomicInteger(); doAnswer(c->{ if(calls.incrementAndGet()==at) throw new SQLException("private SQL payload");
            factorRows.add(new HashMap<>(insertValues)); return 1; }).when(insert).executeUpdate();
        fails(FinalizationFailure.Code.FINALIZATION_UNAVAILABLE); verify(insert,times(at)).executeUpdate(); verify(complete,never()).executeUpdate(); noFinalCommit();
    }
    @Test void oracleFinalExpiryGuardRollsBackAllSevenFactors() throws Exception {
        doReturn(0).when(complete).executeUpdate(); assertEquals(WorkFailure.Code.LEASE_REJECTED,assertThrows(WorkFailure.class,()->service.finalizeAssessment(request)).code());
        verify(insert,times(7)).executeUpdate(); noFinalCommit();
    }
    @Test void failedReadbackRollsBackCompletedParentAndFactors() throws Exception {
        doThrow(new SQLException("private result")).when(result).executeQuery(); fails(FinalizationFailure.Code.FINALIZATION_UNAVAILABLE);
        verify(complete).executeUpdate(); noFinalCommit();
    }
    @Test void corruptedFactorReadbackRollsBackAllWrites() throws Exception {
        when(factors.executeQuery()).thenAnswer(c->{var rs=factorResult(); doReturn("WRONG").when(rs).getString("RULE_CODE"); return rs;});
        fails(FinalizationFailure.Code.FINALIZATION_UNAVAILABLE); verify(complete).executeUpdate(); noFinalCommit();
    }
    @Test void expiredAfterEvaluationNeverInsertsFactors() throws Exception {
        service=service((c,i)->{var r=new RiskScoringEngine().evaluate(c,i); now.set(request.lease().expiresAt()); return r;});
        assertEquals(WorkFailure.Code.LEASE_REJECTED,assertThrows(WorkFailure.class,()->service.finalizeAssessment(request)).code()); verify(insert,never()).executeUpdate(); noFinalCommit();
    }
    @Test void expiryDuringFactorWritesRollsBackSevenChildren() throws Exception {
        doAnswer(c->{var row=new HashMap<>(insertValues); row.put(10,texts.get((Clob)insertValues.get(10))); factorRows.add(row); now.set(request.lease().expiresAt()); return 1;}).when(insert).executeUpdate();
        assertEquals(WorkFailure.Code.LEASE_REJECTED,assertThrows(WorkFailure.class,()->service.finalizeAssessment(request)).code()); verify(complete,never()).executeUpdate(); noFinalCommit();
    }
    @ParameterizedTest @ValueSource(strings={"token","epoch","attempt","revision","expiry"}) void supersededWorkerFailsBeforeEvaluation(String changed) {
        var s=parent.get(); var r=s.work(); var other=new RiskWorkRepository.Row(r.id(),r.snapshot(),r.state(),changed.equals("token")?"other":r.token(),
            r.epoch()+(changed.equals("epoch")?1:0),r.attempt()+(changed.equals("attempt")?1:0),r.revision()+(changed.equals("revision")?1:0),
            changed.equals("expiry")?r.expiry().plusNanos(1):r.expiry(),r.created(),r.cutoff(),null,null);
        parent.set(new FrozenAssessmentLoader.Snapshot(s.accepted(),other,s.header(),s.evidence(),s.config(),s.input(),s.evaluator()));
        assertEquals(WorkFailure.Code.LEASE_REJECTED,assertThrows(WorkFailure.class,()->service.finalizeAssessment(request)).code()); assertEquals(0,evaluations.get());
    }
    @Test void completedRetryWithDifferentTokenIsRejectedByStoredReceipt() throws Exception {
        service.finalizeAssessment(request); var l=request.lease(); var wrong=new WorkLease(l.assessmentId(),"different",l.epoch(),l.attempt(),l.rowVersion(),l.expiresAt());
        var retry=new FinalizationRequest(request.identity(),wrong,request.acceptanceHash(),request.configurationHash(),request.inputHash());
        assertEquals(WorkFailure.Code.LEASE_REJECTED,assertThrows(WorkFailure.class,()->service.finalizeAssessment(retry)).code()); assertEquals(1,evaluations.get()); verify(insert,times(7)).executeUpdate();
    }
    @Test void technicalEngineOutcomeCannotCompleteOrExposeScores() throws Exception {
        service=service((c,i)->new RiskResult(RiskResult.Status.TECHNICAL_FAILURE,i.current().submissionId(),c.versionId(),c.rowVersion(),c.contentHash(),i.cutoff(),null,null,null,List.of(),c,i));
        fails(FinalizationFailure.Code.EVALUATION_TECHNICAL_FAILURE); verify(insert,never()).executeUpdate(); verify(complete,never()).executeUpdate();
    }
    @Test void unresolvedPolicyCannotComplete() throws Exception {
        service=service((c,i)->new RiskResult(RiskResult.Status.POLICY_UNRESOLVED,i.current().submissionId(),c.versionId(),c.rowVersion(),c.contentHash(),i.cutoff(),null,null,null,List.of(),c,i));
        fails(FinalizationFailure.Code.POLICY_UNRESOLVED); verify(insert,never()).executeUpdate();
    }
    @Test void engineExceptionIsSanitizedAndHasNoFinalWrites() throws Exception {
        service=service((c,i)->{throw new IllegalStateException("private evidence details");});
        assertFalse(fails(FinalizationFailure.Code.FINALIZATION_UNAVAILABLE).getMessage().contains("private")); verify(insert,never()).executeUpdate();
    }
    @Test void callerTransactionIsSuspendedWhileEvaluating() {
        new TransactionTemplate(manager).execute(status->{ assertNotNull(service.finalizeAssessment(request)); return null; }); assertEquals(1,evaluations.get());
    }
    @Test void unfinishedOrphanFactorsAreNotOverwritten() throws Exception {
        factorRows.add(Map.of(1,17L)); fails(FinalizationFailure.Code.FINALIZATION_UNAVAILABLE); verify(insert,never()).executeUpdate(); noFinalCommit();
    }
    @Test void jdbcFinalGuardIncludesEveryFenceAndFrozenAssociation() {
        for(String text:List.of("LEASE_TOKEN=?","LEASE_EPOCH=?","ATTEMPT_COUNT=?","ROW_VERSION=?","LEASE_EXPIRES_AT=?","LEASE_EXPIRES_AT>SYSTIMESTAMP",
            "ACCEPTANCE_HASH=?","CONFIGURATION_HASH=?","INPUT_HASH=?","FROZEN_INPUT_EVIDENCE_ID=?","CONFIGURATION_SNAPSHOT_ID=?","INPUT_FROZEN_AT=?")) assertTrue(RiskFinalizationRepository.COMPLETE.contains(text));
    }
    @Test void actualSnapshotDecoderAndEngineConnectToRealTransactionAndFinalizationRepository() {
        var accepted=mock(RiskAcceptanceRepository.class); var evidence=mock(RiskEvidenceRepository.class);
        when(accepted.find(request.identity())).thenAnswer(c->Optional.of(parent.get().accepted())); when(work.read(17)).thenAnswer(c->parent.get().work());
        when(evidence.header(17)).thenAnswer(c->parent.get().header()); when(evidence.children(17)).thenAnswer(c->parent.get().evidence());
        service=new RiskFinalizationService(accepted,work,evidence,repository,PinnedRiskEvaluators.current(),manager,Clock.fixed(FreezeFixtures.NOW,ZoneOffset.UTC));
        var saved=service.finalizeAssessment(request); assertEquals(135,saved.result().rawTotal().intValueExact()); assertEquals(7,committedFactors.size());
        assertEquals(saved,service.finalizeAssessment(request));
    }
    @Test void validInsufficientHistoryPersistsWithZeroContributionAndEnabledManifestReference() {
        parent.set(FinalizationFixtures.snapshot(FinalizationFixtures.config(),FinalizationFixtures.input(false)));
        var saved=service.finalizeAssessment(FinalizationFixtures.request(parent.get())); assertEquals(0,saved.result().finalScore());
        var row=committedFactors.stream().filter(r->r.get(2).equals("UNUSUAL_AMOUNT")).findFirst().orElseThrow();
        assertEquals("INSUFFICIENT_HISTORY",row.get(4)); assertEquals(0,row.get(6)); assertEquals(105L,row.get(7));
    }
    @Test void disabledRulesPersistNullPrimaryEvidenceAndNoReferences() {
        var c=FinalizationFixtures.config(); var disabled=new RiskConfiguration(c.versionId(),c.rowVersion(),c.contentHash(),"INR",100,c.rules().stream()
            .map(r->new RiskConfiguration.Rule(r.code(),false,r.weight(),r.evaluationOrder(),r.parameterSchemaVersion(),r.parameters())).toList(),c.bands(),c.approvalBoundaries());
        parent.set(FinalizationFixtures.snapshot(disabled,FinalizationFixtures.input(false)));
        service.finalizeAssessment(FinalizationFixtures.request(parent.get()));
        for(var row:committedFactors) { assertEquals("DISABLED",row.get(4)); assertNull(row.get(7)); assertTrue(((String)row.get(10)).contains("\"evidenceReferences\":[]")); }
    }
    @Test void authorityLostDuringReadbackRollsBackCompletedParent() throws Exception {
        when(factors.executeQuery()).thenAnswer(c->{var rs=factorResult(); now.set(request.lease().expiresAt()); return rs;});
        assertEquals(WorkFailure.Code.LEASE_REJECTED,assertThrows(WorkFailure.class,()->service.finalizeAssessment(request)).code()); verify(complete).executeUpdate(); noFinalCommit();
    }
    @Test void numericRowVersionOverflowCannotInsertAnyFactors() throws Exception {
        var s=parent.get(); var r=s.work(); var row=new RiskWorkRepository.Row(r.id(),r.snapshot(),r.state(),r.token(),r.epoch(),r.attempt(),Long.MAX_VALUE,r.expiry(),r.created(),r.cutoff(),null,null);
        var updated=new FrozenAssessmentLoader.Snapshot(s.accepted(),row,s.header(),s.evidence(),s.config(),s.input(),s.evaluator()); parent.set(updated);
        assertEquals(WorkFailure.Code.WORK_COUNTER_EXHAUSTED,assertThrows(WorkFailure.class,()->service.finalizeAssessment(FinalizationFixtures.request(updated))).code()); verify(insert,never()).executeUpdate();
    }
    @Test void clobBindingFailureFreesLocatorAndRollsBack() throws Exception {
        doThrow(new SQLException("private binding")).when(insert).setClob(eq(10),any(Clob.class)); fails(FinalizationFailure.Code.FINALIZATION_UNAVAILABLE);
        verify(insert).close(); verify(clobs.getFirst()).free(); verify(insert,never()).executeUpdate(); noFinalCommit();
    }
    @Test void noTransactionAndReadOnlyTransactionCannotInsertFactors() {
        assertThrows(IllegalStateException.class,()->repository.insert(parent.get(),null,"",FreezeFixtures.NOW));
        var tx=new TransactionTemplate(manager); tx.setReadOnly(true);
        tx.execute(status->{assertThrows(IllegalStateException.class,()->repository.insert(parent.get(),null,"",FreezeFixtures.NOW)); return null;});
    }
    @Test void takeoverDuringEvaluationRejectsOldWorkerWithoutAnyFactorWrites() throws Exception {
        service=service((c,i)->{
            var evaluated=new RiskScoringEngine().evaluate(c,i); var s=parent.get(); var r=s.work();
            var taken=new RiskWorkRepository.Row(r.id(),r.snapshot(),r.state(),"new-worker",r.epoch()+1,r.attempt()+1,r.revision()+1,r.expiry().plusSeconds(60),r.created(),r.cutoff(),null,null);
            parent.set(new FrozenAssessmentLoader.Snapshot(s.accepted(),taken,s.header(),s.evidence(),s.config(),s.input(),s.evaluator())); return evaluated;
        });
        assertEquals(WorkFailure.Code.LEASE_REJECTED,assertThrows(WorkFailure.class,()->service.finalizeAssessment(request)).code()); verify(insert,never()).executeUpdate();
    }
    @Test void changedImmutableInputBetweenPhasesCannotFinalize() throws Exception {
        service=service((c,i)->{
            var evaluated=new RiskScoringEngine().evaluate(c,i); var s=parent.get();
            parent.set(new FrozenAssessmentLoader.Snapshot(s.accepted(),s.work(),new RiskEvidenceRepository.Header("0".repeat(64),s.header().manifest(),s.header().frozenAt()),s.evidence(),s.config(),s.input(),s.evaluator())); return evaluated;
        });
        fails(FinalizationFailure.Code.FINALIZATION_CONFLICT); verify(insert,never()).executeUpdate(); noFinalCommit();
    }
    @Test void completedRetryRequiresNoEvaluatorArtifactAndNeverRescores() throws Exception {
        var saved=service.finalizeAssessment(request);
        service=new RiskFinalizationService(loader,work,repository,new PinnedRiskEvaluators(Map.of()),manager,Clock.fixed(request.lease().expiresAt().plusSeconds(100),ZoneOffset.UTC));
        assertEquals(saved,service.finalizeAssessment(request)); verify(insert,times(7)).executeUpdate(); assertEquals(1,evaluations.get());
    }
    @Test void invalidEngineCoverageIsRejectedBeforeParentLock() throws Exception {
        service=service((c,i)->{
            var r=new RiskScoringEngine().evaluate(c,i);
            return new RiskResult(r.status(),r.submissionId(),r.configurationVersionId(),r.configurationRowVersion(),r.configurationContentHash(),r.cutoff(),r.rawTotal(),r.finalScore(),r.riskBand(),r.factors().subList(0,6),c,i);
        });
        fails(FinalizationFailure.Code.INVALID_EVALUATION); verify(work,never()).lock(anyLong()); verify(insert,never()).executeUpdate();
    }
    @Test void readAuthorizedResultUsesStoredFactorsWithoutWorkerCapabilityOrWrites() throws Exception {
        var completed=service.finalizeAssessment(request); clearInvocations(insert,complete);
        var reads=new TransactionTemplate(manager); reads.setReadOnly(true);
        var actual=reads.execute(status->{repository.beginSnapshot(); return repository.readPersisted(parent.get());});
        assertEquals(completed,actual); assertEquals(1,evaluations.get()); verify(insert,never()).executeUpdate(); verify(complete,never()).executeUpdate();
    }
    @ParameterizedTest @ValueSource(strings={"receipt","details","contribution","missing-factor"}) void readAuthorizedCorruptResultsAreRejectedWithoutRescoring(String changed) throws Exception {
        service.finalizeAssessment(request); clearInvocations(insert,complete);
        var row=factorRows.getFirst();
        if(changed.equals("receipt")) row.put(10,((String)row.get(10)).replace(FinalizedFactors.receipt(request),"b".repeat(64)));
        if(changed.equals("details")) row.put(10,((String)row.get(10)).replace("risk-factor-v1","risk-factor-v2"));
        if(changed.equals("contribution")) row.put(6,0);
        if(changed.equals("missing-factor")) factorRows.removeLast();
        var reads=new TransactionTemplate(manager); reads.setReadOnly(true);
        assertThrows(RuntimeException.class,()->reads.execute(status->repository.readPersisted(parent.get())));
        assertEquals(1,evaluations.get()); verify(insert,never()).executeUpdate(); verify(complete,never()).executeUpdate();
    }
}
