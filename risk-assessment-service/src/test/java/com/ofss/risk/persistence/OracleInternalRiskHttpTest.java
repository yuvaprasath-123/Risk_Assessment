package com.ofss.risk.persistence;

import com.ofss.risk.RiskAssessmentApplication;
import com.ofss.internal.*;
import com.ofss.internal.verification.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.*;
import org.springframework.jdbc.support.JdbcTransactionManager;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in real HTTP/Oracle with reused guarded finalization fixtures and an EPHEMERAL TEST issuer. */
@EnabledIfEnvironmentVariable(named="D2_VERIFY_INTERNAL_HTTP_ORACLE_WRITES",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class OracleInternalRiskHttpTest {
    static final String AUDIENCE="risk-assessment-service",ROOT="/internal/v1/risk-assessments/";
    static final String GRANTS="{\"approval-service\":[\"RISK_ASSESSMENT_READ\",\"RISK_FACTORS_READ\",\"RISK_CONFIGURATION_READ\"],\"summary-service\":[\"RISK_ASSESSMENT_READ\"]}";
    final OracleRiskReadHttpTest fixture=new OracleRiskReadHttpTest();
    final EphemeralCredentials credentials=new EphemeralCredentials();
    EphemeralReceiver enabled,disabled;InternalReadHttp http;boolean safe=true,fixtureStarted;Map<String,Object> before;
    static void localTarget(String url){if(url!=null&&!url.isBlank()&&!url.equals("jdbc:oracle:thin:@//localhost:1521/FREEPDB1"))throw new IllegalArgumentException("Internal HTTP verification requires the fixed local Oracle endpoint");}
    @BeforeAll void prepare(){
        localTarget(System.getenv("RISK_DB_URL"));
        var source=OracleAcceptanceConnection.externalDataSource();fixture.jdbc=fixture.diagnostics.jdbc(source);fixture.manager=new JdbcTransactionManager(source);
        assertEquals(0L,fixture.count());System.out.println("Risk internal HTTP EPHEMERAL TEST ISSUER fixture namespace: "+fixture.fixtures.namespace);
        fixtureStarted=true;fixture.completed=fixture.create("success",true);fixture.pending=fixture.create("insufficient",false);before=fixture.state();
        http=new InternalReadHttp(()->safe=false);enabled=EphemeralReceiver.start(RiskAssessmentApplication.class,credentials,AUDIENCE,GRANTS,true,database());
    }
    Map<String,Object> database(){String password=System.getenv("RISK_DB_PASSWORD");if(password==null||password.isBlank())throw new AssertionError("Supply RISK_DB_PASSWORD externally");
        return Map.of("RISK_DB_URL","jdbc:oracle:thin:@//localhost:1521/FREEPDB1","RISK_DB_USERNAME","D2_RISK","RISK_DB_PASSWORD",password);}
    String token(String identity,String permission){return credentials.service(AUDIENCE,identity,List.of(permission));}
    String route(String suffix){return ROOT+fixture.completed.accepted().assessmentId()+suffix;}
    static String permission(String suffix){return suffix.equals("/factors")?ServicePermissions.RISK_FACTORS:suffix.equals("/configuration-snapshot")?ServicePermissions.RISK_CONFIGURATION:ServicePermissions.RISK_ASSESSMENT;}
    InternalReadHttp.Reply get(String path,String token){return http.get(enabled.base(),path,token);}
    static void response(InternalReadHttp.Reply reply,int status,String code){assertEquals(status,reply.status());if(code!=null){assertEquals(code,reply.body().get("code"));assertFalse(reply.body().containsKey("result"));}}
    @Test void allCompletedInternalReadsUsePersistedResultsWithoutPrivateFields(){
        for(String suffix:List.of("","/factors","/configuration-snapshot")){
            var reply=get(route(suffix),token("approval-service",permission(suffix)));response(reply,200,null);
            assertEquals(fixture.completed.accepted().assessmentId(),reply.body().get("assessmentId"));assertEquals("COMPLETED",reply.body().get("status"));
            Set<String> expected=suffix.equals("/factors")?Set.of("assessmentId","rowVersion","status","factors"):
                suffix.equals("/configuration-snapshot")?Set.of("assessmentId","rowVersion","status","snapshotId","configurationHash","evaluatorVersion","configuration"):
                Set.of("assessmentId","rowVersion","status","createdAt","cutoff","evaluatedAt","completedAt","configurationVersionId","configurationRowVersion","evaluatorVersion","result");
            assertTrue(expected.equals(reply.body().keySet()),"Only the allowlisted projection may be exposed");
            if(suffix.equals("/factors")){assertEquals(7,((List<?>)reply.body().get("factors")).size());
                for(var factor:(List<?>)reply.body().get("factors"))assertTrue(Set.of("ruleCode","evaluationOrder","outcome","configuredWeight","contribution","explanation").equals(((Map<?,?>)factor).keySet()),"Factor projection must not expose evidence or capabilities");}
            if(suffix.isEmpty()){assertEquals("HIGH",((Map<?,?>)reply.body().get("result")).get("band"));assertTrue(((String)reply.body().get("completedAt")).endsWith("Z"));}
            if(suffix.equals("/configuration-snapshot"))assertEquals(fixture.completed.accepted().configurationHash(),reply.body().get("configurationHash"));
        }
    }
    @Test void permissionAndIdentityGrantsAreIndependent(){
        response(get(route("/factors"),token("summary-service",ServicePermissions.RISK_ASSESSMENT)),403,"FORBIDDEN");
        response(get(route(""),token("approval-service",ServicePermissions.RISK_FACTORS)),403,"FORBIDDEN");
        response(get(route(""),token("unlisted-service",ServicePermissions.RISK_ASSESSMENT)),401,"UNAUTHORIZED");
        response(get(route("/factors"),token("summary-service",ServicePermissions.RISK_FACTORS)),401,"UNAUTHORIZED");
    }
    @Test void crossAudienceBadKeyExpiryAndMissingClaimsAreUnauthorized(){
        response(get(route(""),null),401,"UNAUTHORIZED");
        response(get(route(""),credentials.service("business-configuration-service","approval-service",List.of(ServicePermissions.RISK_ASSESSMENT))),401,"UNAUTHORIZED");
        for(String variation:List.of("wrong-key","unknown-kid","issuer","expired","missing-sub","missing-exp","missing-type","missing-permissions"))
            response(get(route(""),credentials.service(AUDIENCE,"approval-service",List.of(ServicePermissions.RISK_ASSESSMENT),variation)),401,"UNAUTHORIZED");
    }
    @Test void testUserAndServiceCredentialsCannotCrossDomains(){
        response(get(route(""),credentials.user()),401,"UNAUTHORIZED");
        String publicPath="/api/v1/risk-assessments/"+fixture.completed.accepted().assessmentId();
        response(get(publicPath,token("approval-service",ServicePermissions.RISK_ASSESSMENT)),401,"UNAUTHORIZED");response(get(publicPath,credentials.user()),200,null);
    }
    @Test void nonCompletedAssessmentNeverReachesApprovalAndRotationOverlapWorks(){
        for(String suffix:List.of("","/factors","/configuration-snapshot"))response(get(ROOT+fixture.pending.accepted().assessmentId()+suffix,token("approval-service",permission(suffix))),409,"ASSESSMENT_NOT_COMPLETED");
        response(get(route(""),credentials.service(AUDIENCE,"approval-service",List.of(ServicePermissions.RISK_ASSESSMENT),"next")),200,null);
        response(get(route("/input-evidence"),token("approval-service",ServicePermissions.RISK_ASSESSMENT)),403,"FORBIDDEN");
    }
    @Test void disabledModeKeepsInternalUnavailableAndPublicManagerReadsUsable(){
        disabled=EphemeralReceiver.start(RiskAssessmentApplication.class,credentials,AUDIENCE,GRANTS,false,database());
        for(String suffix:List.of("","/factors","/configuration-snapshot"))response(http.get(disabled.base(),route(suffix),token("approval-service",permission(suffix))),404,"INTERNAL_ACCESS_DISABLED");
        response(http.get(disabled.base(),"/api/v1/risk-assessments/"+fixture.completed.accepted().assessmentId(),credentials.user()),200,null);
    }
    @AfterEach void unchanged(){if(before!=null)assertTrue(before.equals(fixture.state()),"Every parent/snapshot/evidence/factor field must remain unchanged");}
    @AfterAll void cleanup(){
        safe=VerificationShutdown.closeAll(disabled,enabled,http)&&safe;
        if(fixtureStarted&&!safe)throw new AssertionError("Risk internal HTTP cleanup deferred; stop receivers/client and retain printed namespace");
        if(fixtureStarted){
            try{new OracleFinalizationFixtureCleaner(fixture.jdbc,fixture.manager).clean(fixture.fixtures.namespace,OracleRiskReadHttpTest.KEYS);assertEquals(0L,fixture.count());}
            catch(RuntimeException failure){throw new AssertionError("Risk internal HTTP cleanup failed/refused; retain exact namespace; details withheld");}
            System.out.println("Risk internal HTTP receivers/client terminated; guarded cleanup remaining parents=0; namespace: "+fixture.fixtures.namespace);
        }
    }
}
