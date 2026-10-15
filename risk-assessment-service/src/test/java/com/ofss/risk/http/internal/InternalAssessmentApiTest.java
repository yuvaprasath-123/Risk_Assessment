package com.ofss.risk.http.internal;

import com.ofss.risk.http.AssessmentReadController;
import com.ofss.risk.http.error.*;
import com.ofss.risk.http.security.*;
import com.ofss.risk.persistence.*;
import com.ofss.internal.*;
import io.jsonwebtoken.*;
import java.security.KeyPair;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({InternalAssessmentController.class,AssessmentReadController.class})
@Import({InternalSecurityConfiguration.class,SecurityConfiguration.class,JwtVerifier.class,ErrorResponseWriter.class,ApiExceptionHandler.class})
class InternalAssessmentApiTest {
    static final KeyPair KEY=Jwts.SIG.RS256.keyPair().build(),OTHER=Jwts.SIG.RS256.keyPair().build();
    static final String USER_KEY=HexFormat.of().formatHex(Jwts.SIG.HS512.key().build().getEncoded());
    static final String ROOT="/internal/v1/risk-assessments/9007199254740993",ID="9007199254740993";
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("security.jwt.secret",()->USER_KEY);r.add("internal-access.enabled",()->true);r.add("internal-access.issuer",()->"ephemeral-internal-issuer");r.add("internal-access.audience",()->"risk-assessment-service");
        r.add("internal-access.public-keys",()->"{\"current\":\""+Base64.getEncoder().encodeToString(KEY.getPublic().getEncoded())+"\"}");
        r.add("internal-access.service-permissions",()->"{\"approval-service\":[\"RISK_ASSESSMENT_READ\",\"RISK_FACTORS_READ\",\"RISK_CONFIGURATION_READ\"],\"summary-service\":[\"RISK_ASSESSMENT_READ\"]}");
    }
    @Autowired MockMvc mvc;
    @MockitoBean AssessmentReadService service;
    JwtBuilder builder(List<String> permissions) {return Jwts.builder().header().type(InternalServiceVerifier.TYPE).keyId("current").and().issuer("ephemeral-internal-issuer").audience().add("risk-assessment-service").and()
        .subject("approval-service").claim("principalType","SERVICE").claim("permissions",permissions).expiration(Date.from(Instant.now().plusSeconds(300)));}
    String token(String permission) {return builder(List.of(permission)).signWith(KEY.getPrivate(),Jwts.SIG.RS256).compact();}
    String user(boolean marker) {var b=Jwts.builder().subject("manager").claim("userId",42).claim("roles",List.of("FINANCE_MANAGER")).expiration(Date.from(Instant.now().plusSeconds(300)));
        if(marker)b.header().type(InternalServiceVerifier.TYPE).and();return b.signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(USER_KEY.getBytes(java.nio.charset.StandardCharsets.UTF_8))).compact();}
    static String permission(String route) {return route.equals("/factors")?ServicePermissions.RISK_FACTORS:route.equals("/configuration-snapshot")?ServicePermissions.RISK_CONFIGURATION:ServicePermissions.RISK_ASSESSMENT;}
    static AssessmentReadView view(String state) {
        String time="2026-10-15T08:09:10.123456789Z";boolean complete=state.equals("COMPLETED");
        var factors=Arrays.stream(com.ofss.risk.contract.RuleCode.values()).map(c->new AssessmentReadView.Factor(c.name(),c.ordinal()+1,"TRIGGERED",Integer.MAX_VALUE,Integer.MAX_VALUE,"Configured factor matched")).toList();
        return new AssessmentReadView(ID,"9223372036854775807",state,time,time,complete?time:null,complete?time:null,"7","9","risk-scoring-v1",
            complete?new AssessmentReadView.Score(15032385529L,100,100,"HIGH"):null,complete?factors:null,"9007199254740995","a".repeat(64),
            Map.of("versionId","7","rowVersion","9","approvalBoundaries",Map.of("2","1234567890123.45"),"meanMultiplier","1.0000000000000001"));
    }
    @ParameterizedTest @ValueSource(strings={"","/factors","/configuration-snapshot"})
    void completedProjectionsReuseVerifiedReadsWithoutPrivateEvidence(String route) throws Exception {
        when(service.get(ID)).thenReturn(view("COMPLETED"));
        String body=mvc.perform(get(ROOT+route).header("Authorization","bearer "+token(permission(route)))).andExpect(status().isOk())
            .andExpect(jsonPath("$.assessmentId").value(ID)).andExpect(jsonPath("$.rowVersion").value("9223372036854775807"))
            .andExpect(jsonPath("$.status").value("COMPLETED")).andReturn().getResponse().getContentAsString();
        for(String field:List.of("leaseToken","diagnostic","completionFenceHash","matchedEvidenceIds","observations","acceptanceJson"))org.assertj.core.api.Assertions.assertThat(body).doesNotContain(field);
        verify(service).get(ID);
    }
    @ParameterizedTest @ValueSource(strings={"","/factors","/configuration-snapshot"})
    void oneReadPermissionCannotGrantOtherReads(String route) throws Exception {
        String wrong=route.equals("/factors")?ServicePermissions.RISK_ASSESSMENT:ServicePermissions.RISK_FACTORS;
        mvc.perform(get(ROOT+route).header("Authorization","Bearer "+token(wrong))).andExpect(status().isForbidden());verifyNoInteractions(service);
    }
    @ParameterizedTest @ValueSource(strings={"ACCEPTED","IN_PROGRESS","RETRYABLE_FAILURE","BLOCKED"})
    void nonCompletedResultsCannotReachApproval(String state) throws Exception {
        when(service.get(ID)).thenReturn(view(state));
        for(String route:List.of("","/factors","/configuration-snapshot"))mvc.perform(get(ROOT+route).header("Authorization","Bearer "+token(permission(route))))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ASSESSMENT_NOT_COMPLETED")).andExpect(jsonPath("$.result").doesNotExist());
    }
    @ParameterizedTest @ValueSource(strings={"missing","invalid","wrong-key","issuer","audience","identity","expired","user"})
    void serviceVerificationRejectsInvalidCredentialsBeforeReads(String failure) throws Exception {
        var request=get(ROOT);var b=builder(List.of(ServicePermissions.RISK_ASSESSMENT));
        if(failure.equals("issuer"))b.issuer("wrong");if(failure.equals("audience"))b.audience().clear().add("business-configuration-service").and();
        if(failure.equals("identity"))b.subject("unlisted");if(failure.equals("expired"))b.expiration(Date.from(Instant.now().minusSeconds(30)));
        if(!failure.equals("missing"))request.header("Authorization","Bearer "+(failure.equals("invalid")?"private-invalid-token":failure.equals("user")?user(false):b.signWith(failure.equals("wrong-key")?OTHER.getPrivate():KEY.getPrivate(),Jwts.SIG.RS256).compact()));
        mvc.perform(request).andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate",containsString("Bearer")))
            .andExpect(content().string(not(containsString("private-invalid-token"))));verifyNoInteractions(service);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void serviceTokenOrReservedTypeCannotImpersonateManager(boolean marker) throws Exception {
        mvc.perform(get("/api/v1/risk-assessments/"+ID).header("Authorization","Bearer "+(marker?user(true):token(ServicePermissions.RISK_ASSESSMENT))))
            .andExpect(status().isUnauthorized());verifyNoInteractions(service);
    }
    @Test void noMutationOrRawEvidenceRouteIsAvailable() throws Exception {
        String credential=token(ServicePermissions.RISK_ASSESSMENT);
        mvc.perform(post(ROOT).header("Authorization","Bearer "+credential).contentType("application/json").content("{}" )).andExpect(status().isForbidden());
        mvc.perform(get(ROOT+"/input-evidence").header("Authorization","Bearer "+credential)).andExpect(status().isForbidden());verifyNoInteractions(service);
    }
    @Test void verifiedServiceStillReceivesSanitizedPersistenceFailures() throws Exception {
        when(service.get(ID)).thenThrow(new AssessmentReadFailure(AssessmentReadFailure.Code.ASSESSMENT_UNAVAILABLE));
        mvc.perform(get(ROOT).header("Authorization","Bearer "+token(ServicePermissions.RISK_ASSESSMENT))).andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("ASSESSMENT_UNAVAILABLE"));
    }
}
