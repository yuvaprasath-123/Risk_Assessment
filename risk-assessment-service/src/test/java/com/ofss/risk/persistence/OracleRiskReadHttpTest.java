package com.ofss.risk.persistence;

import java.net.http.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in fixture WRITES plus real HTTP to a separately user-started application. No issuer token creation. */
@EnabledIfEnvironmentVariable(named="RISK_VERIFY_ORACLE_HTTP_WRITES",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class OracleRiskReadHttpTest {
    static final Set<String> KEYS=Set.of("success","insufficient");
    static final List<String> SUFFIXES=List.of("","/score-breakdown","/factors","/configuration-snapshot");
    static final String CHALLENGE="Bearer realm=\"risk-assessment-service\"";
    final OracleFinalizationFixtures fixtures=new OracleFinalizationFixtures();
    final OracleAcceptanceDiagnostics diagnostics=new OracleAcceptanceDiagnostics();
    JdbcTemplate jdbc; JdbcTransactionManager manager; HttpClient client; ExternalRiskReadHttp http;
    String managerToken,otherToken; boolean fixtureWritesStarted,cleanupAllowed=true; long missingId;
    record Fixture(AcceptanceRequest request,AcceptedAssessment accepted,CompletedAssessment completed) {long id(){return Long.parseLong(accepted.assessmentId());}}
    Fixture completed,pending; Map<String,Object> before;
    @BeforeAll void prepare() {
        var base=ExternalRiskReadHttp.base(System.getenv("RISK_HTTP_TEST_BASE_URL"));
        managerToken=ExternalRiskReadHttp.requiredToken(System.getenv("RISK_HTTP_TEST_MANAGER_TOKEN"));
        otherToken=ExternalRiskReadHttp.optionalToken(System.getenv("RISK_HTTP_TEST_OTHER_ROLE_TOKEN"));
        client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();
        http=new ExternalRiskReadHttp(client,base,()->cleanupAllowed=false);
        // Positive authentication/role preflight without a database lookup. Failure precedes ALL fixture writes.
        var preflight=http.get(ExternalRiskReadHttp.ROOT+"0",managerToken,"Bearer");
        assertEquals(400,preflight.status(),"Genuine manager-token preflight failed; no fixture writes attempted");
        assertEquals("INVALID_ASSESSMENT_ID",preflight.body().get("code"));
        if(otherToken!=null) assertEquals(403,http.get(ExternalRiskReadHttp.ROOT+"0",otherToken,"Bearer").status(),"Optional genuine token must authenticate without manager access");
        var source=OracleAcceptanceConnection.externalDataSource(); jdbc=diagnostics.jdbc(source); manager=new JdbcTransactionManager(source);
        assertEquals(0L,count());
        System.out.println("HTTP fixture namespace (reviewed finalization fixture format; retain for recovery): "+fixtures.namespace);
        fixtureWritesStarted=true;
        completed=create("success",true); pending=create("insufficient",false); before=state();
        missingId=Long.MAX_VALUE;
        while(jdbc.queryForObject("SELECT COUNT(*) FROM D2_RISK.RISK_ASSESSMENTS WHERE ASSESSMENT_ID=?",Long.class,missingId)!=0) {
            if(Long.MAX_VALUE-missingId>=10) throw new AssertionError("No safe absent assessment ID selected"); missingId--;
        }
    }
    Fixture create(String key,boolean finish) {
        var oracle=jdbc.queryForObject(OracleLeaseClock.SQL,(rs,n)->rs.getObject("LEASE_NOW",OffsetDateTime.class).toInstant(),new Object[0]);
        var created=oracle.minusSeconds(5).truncatedTo(ChronoUnit.SECONDS).plusNanos(123456890);
        var request=fixtures.acceptance(key,created);
        var accepted=new RiskAcceptanceService(new RiskAcceptanceRepository(jdbc),new CanonicalAcceptance(),manager,Clock.fixed(created,ZoneOffset.UTC)).accept(request);
        CompletedAssessment result=null;
        if(finish) {
            var work=new RiskWorkRepository(jdbc); var acceptance=new RiskAcceptanceRepository(jdbc); var evidence=new RiskEvidenceRepository(jdbc);
            var lease=new RiskWorkService(work,manager,new OracleLeaseClock(jdbc),Duration.ofMinutes(2)).claim(accepted.assessmentId(),accepted.rowVersion());
            var frozen=new RiskEvidenceService(work,acceptance,evidence,manager,new OracleLeaseClock(jdbc)).freeze(fixtures.evidence(lease,request));
            result=new RiskFinalizationService(acceptance,work,evidence,new RiskFinalizationRepository(jdbc),PinnedRiskEvaluators.current(),manager,new OracleLeaseClock(jdbc))
                .finalizeAssessment(new FinalizationRequest(request.identity(),frozen.lease(),accepted.acceptanceHash(),accepted.configurationHash(),frozen.inputHash()));
        }
        return new Fixture(request,accepted,result);
    }
    long count() {return jdbc.queryForObject("SELECT COUNT(*) FROM D2_RISK.RISK_ASSESSMENTS WHERE SUBMISSION_SOURCE=?",Long.class,fixtures.namespace);}
    Map<String,Object> state() {
        var result=new TreeMap<String,Object>();
        for(var f:List.of(completed,pending)) {
            var tables=new TreeMap<String,Object>();
            for(String table:List.of("RISK_ASSESSMENTS","RISK_CONFIGURATION_SNAPSHOTS","RISK_INPUT_EVIDENCE","RISK_ASSESSMENT_FACTORS")) {
                // Fixed allowlist, not caller-controlled SQL identifiers. Restrict every row to the exact namespace.
                String sql="SELECT T.* FROM D2_RISK."+table+" T WHERE T.ASSESSMENT_ID=? AND EXISTS (SELECT 1 FROM D2_RISK.RISK_ASSESSMENTS A WHERE A.ASSESSMENT_ID=T.ASSESSMENT_ID AND A.SUBMISSION_SOURCE=?) ORDER BY 1";
                tables.put(table,jdbc.query(sql,(rs,n)->OracleWorkPersistenceTest.values(rs),f.id(),fixtures.namespace));
            }
            result.put(f.request.identity().key(),tables);
        }
        return result;
    }
    @AfterEach void readsLeaveEveryStoredColumnUnchanged() {if(before!=null) {assertEquals(2L,count()); assertTrue(before.equals(state()),"HTTP reads must not change any fixture column");}}
    @AfterAll void cleanup() {
        boolean terminated=true;
        if(client!=null) {
            client.shutdown();
            try { if(!client.awaitTermination(Duration.ofSeconds(10))) {client.shutdownNow(); terminated=client.awaitTermination(Duration.ofSeconds(5));} }
            catch(InterruptedException e) {Thread.currentThread().interrupt(); terminated=false;}
        }
        managerToken=null; otherToken=null; diagnostics.print();
        if(!terminated) cleanupAllowed=false;
        System.out.println("HTTP client workers terminated="+terminated);
        if(!fixtureWritesStarted) {System.out.println("HTTP fixture writes did not start"); return;}
        if(!cleanupAllowed) {System.out.println("HTTP fixture cleanup deferred; stop outstanding requests/application before recovery; namespace: "+fixtures.namespace); return;}
        try {
            new OracleFinalizationFixtureCleaner(jdbc,manager).clean(fixtures.namespace,KEYS); assertEquals(0L,count());
            System.out.println("HTTP fixture cleanup completed; remaining parents=0; namespace: "+fixtures.namespace);
        } catch(RuntimeException e) {diagnostics.record("HTTP_FIXTURE_CLEANUP",e); diagnostics.print(); throw new AssertionError("HTTP fixture cleanup failed/refused; retain exact namespace");}
    }
    ExternalRiskReadHttp.Reply get(Fixture f,String suffix,String token,String scheme) {return http.get(ExternalRiskReadHttp.ROOT+f.accepted.assessmentId()+suffix,token,scheme);}
    static void keys(Map<String,Object> body,String... names) {assertEquals(new TreeSet<>(Arrays.asList(names)),new TreeSet<>(body.keySet()),"Unexpected public response fields");}
    static void error(ExternalRiskReadHttp.Reply r,int status,String code) {
        assertEquals(status,r.status()); keys(r.body(),"status","code","message","timestamp","path"); assertEquals(code,r.body().get("code"));
        assertTrue(r.body().get("timestamp") instanceof String t && t.endsWith("Z"));
        Instant.parse((String)r.body().get("timestamp"));
        var messages=Map.of("UNAUTHORIZED",Set.of("Authentication is required","Valid authentication is required"),"FORBIDDEN",Set.of("Access is denied"),
            "ASSESSMENT_NOT_FOUND",Set.of("Risk assessment was not found"),"INVALID_ASSESSMENT_ID",Set.of("A positive assessment ID within Java Long range is required"));
        assertTrue(messages.get(code).contains(r.body().get("message")),"Error message must remain sanitized");
    }
    @SuppressWarnings("unchecked") @Test void completedFixtureAllFourReadsUseGenuineIssuerTokenAndExactPublicContracts() {
        int route=0;
        for(String suffix:SUFFIXES) {
            var reply=get(completed,suffix,managerToken,List.of("Bearer","bearer","BEARER","Bearer").get(route++));
            assertEquals(200,reply.status()); var b=reply.body(); assertEquals(completed.accepted.assessmentId(),b.get("assessmentId"));
            assertEquals(completed.completed.rowVersion(),b.get("rowVersion")); assertEquals("COMPLETED",b.get("status"));
            if(suffix.isEmpty()) {
                keys(b,"assessmentId","rowVersion","status","createdAt","cutoff","evaluatedAt","completedAt","configurationVersionId","configurationRowVersion","evaluatorVersion","result");
                assertEquals(completed.request.cutoff().toString(),b.get("cutoff")); assertEquals(completed.completed.completedAt().toString(),b.get("completedAt"));
                assertEquals(completed.accepted.createdAt().toString(),b.get("createdAt")); assertEquals(completed.completed.evaluatedAt().toString(),b.get("evaluatedAt"));
                assertEquals(completed.accepted.configurationVersionId(),b.get("configurationVersionId")); assertEquals(completed.accepted.configurationRowVersion(),b.get("configurationRowVersion"));
                assertEquals(PinnedRiskEvaluators.CURRENT,b.get("evaluatorVersion"));
                var result=(Map<String,Object>)b.get("result"); keys(result,"rawScore","finalScore","maxFinalScore","band"); score(result);
            } else if(suffix.equals("/score-breakdown")) {
                keys(b,"assessmentId","rowVersion","status","result"); var result=(Map<String,Object>)b.get("result");
                keys(result,"rawScore","finalScore","maxFinalScore","band","contributions"); score(result);
                var rows=(List<Map<String,Object>>)result.get("contributions"); assertEquals(7,rows.size()); int sum=0;
                for(var row:rows) {keys(row,"ruleCode","outcome","configuredWeight","contribution"); assertEquals("TRIGGERED",row.get("outcome")); assertEquals(row.get("configuredWeight"),row.get("contribution")); sum+=Math.toIntExact(PersistenceValues.number(row.get("contribution"),false));} assertEquals(135,sum);
            } else if(suffix.equals("/factors")) {
                keys(b,"assessmentId","rowVersion","status","factors"); var rows=(List<Map<String,Object>>)b.get("factors"); assertEquals(7,rows.size());
                var codes=new HashSet<Object>(); int previous=0;
                for(var row:rows) {keys(row,"ruleCode","evaluationOrder","outcome","configuredWeight","contribution","explanation"); assertTrue(codes.add(row.get("ruleCode")));
                    int order=Math.toIntExact(PersistenceValues.number(row.get("evaluationOrder"),true)); assertTrue(order>previous); previous=order; assertEquals("TRIGGERED",row.get("outcome"));}
            } else snapshot(completed,b);
        }
    }
    static void score(Map<String,Object> result) {assertEquals(135L,PersistenceValues.number(result.get("rawScore"),false)); assertEquals(100,PersistenceValues.number(result.get("finalScore"),false)); assertEquals(100,PersistenceValues.number(result.get("maxFinalScore"),false)); assertEquals("HIGH",result.get("band"));}
    void snapshot(Fixture f,Map<String,Object> b) {
        keys(b,"assessmentId","rowVersion","status","snapshotId","configurationHash","evaluatorVersion","configuration");
        assertEquals(f.accepted.configurationSnapshotId(),b.get("snapshotId")); assertEquals(f.accepted.configurationHash(),b.get("configurationHash"));
        var expected=CanonicalAcceptance.read(new CanonicalAcceptance().configuration(f.request.configuration()).json()).get("configuration");
        assertTrue(expected.equals(b.get("configuration")),"Exact pinned configuration/decimal strings must match isolated fixture");
    }
    @Test void acceptedFixtureAllFourReadsHaveStatusNullResultAndPinnedConfiguration() {
        for(String suffix:SUFFIXES) {
            var reply=get(pending,suffix,managerToken,"Bearer"); assertEquals(200,reply.status()); var b=reply.body();
            assertEquals(pending.accepted.assessmentId(),b.get("assessmentId")); assertEquals("0",b.get("rowVersion")); assertEquals("ACCEPTED",b.get("status"));
            if(suffix.equals("/configuration-snapshot")) snapshot(pending,b);
            else {String field=suffix.equals("/factors")?"factors":"result"; assertTrue(b.containsKey(field)); assertNull(b.get(field));
                if(suffix.isEmpty()) {keys(b,"assessmentId","rowVersion","status","createdAt","cutoff","evaluatedAt","completedAt","configurationVersionId","configurationRowVersion","evaluatorVersion","result");assertNull(b.get("evaluatedAt")); assertNull(b.get("completedAt"));}
                else keys(b,"assessmentId","rowVersion","status",field);}
        }
    }
    @Test void missingAndInvalidAuthenticationUseSanitizedBearerChallengesOnAllRoutes() {
        for(String suffix:SUFFIXES) {
            var absent=get(completed,suffix,null,"Bearer"); error(absent,401,"UNAUTHORIZED"); assertEquals(CHALLENGE,absent.headers().firstValue("WWW-Authenticate").orElse(""));
            var invalid=get(completed,suffix,"invalid","Bearer"); error(invalid,401,"UNAUTHORIZED"); assertEquals(CHALLENGE+", error=\"invalid_token\"",invalid.headers().firstValue("WWW-Authenticate").orElse(""));
        }
    }
    @Test void genuineNonManagerTokenIsForbiddenWhenProvided() {
        Assumptions.assumeTrue(otherToken!=null,"Genuine wrong-role interoperability NOT VERIFIED: no optional issuer token supplied");
        for(String suffix:SUFFIXES) {var reply=get(completed,suffix,otherToken,"Bearer"); error(reply,403,"FORBIDDEN"); assertTrue(reply.headers().firstValue("WWW-Authenticate").isEmpty());}
    }
    @Test void absentIdIs404AndMalformedOrOverflowingIdsAre400OnEveryRoute() {
        for(String suffix:SUFFIXES) {
            error(http.get(ExternalRiskReadHttp.ROOT+missingId+suffix,managerToken,"Bearer"),404,"ASSESSMENT_NOT_FOUND");
            for(String id:List.of("0","-1","1.5","9223372036854775808")) error(http.get(ExternalRiskReadHttp.ROOT+id+suffix,managerToken,"Bearer"),400,"INVALID_ASSESSMENT_ID");
        }
    }
    @Test void unapprovedEvidenceAndExtraPathSegmentsAreDenied() {
        for(String suffix:List.of("/input-evidence","/factors/extra","/score-breakdown/extra","/configuration-snapshot/extra")) error(get(completed,suffix,managerToken,"Bearer"),403,"FORBIDDEN");
    }
}
