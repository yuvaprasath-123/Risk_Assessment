package com.ofss.risk.http.internal;

import com.ofss.internal.*;
import com.ofss.risk.submission.*;
import com.ofss.risk.http.error.*;
import com.ofss.risk.http.security.*;
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
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;

@WebMvcTest(SubmissionController.class)
@Import({InternalSecurityConfiguration.class,SecurityConfiguration.class,JwtVerifier.class,ErrorResponseWriter.class,ApiExceptionHandler.class})
class SubmissionApiTest {
    static final KeyPair RSA=Jwts.SIG.RS256.keyPair().build();static final String HMAC=HexFormat.of().formatHex(Jwts.SIG.HS512.key().build().getEncoded());
    static final String ROOT="/internal/v1/risk-assessments";
    static final String BODY="{\"contractVersion\":\"risk-submission-v1\",\"submissionSource\":\"payment.v1\",\"submissionKey\":\"s1\",\"paymentReference\":\"9007199254740993\",\"validationReference\":\"PASS-1\"}";
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("security.jwt.secret",()->HMAC);r.add("internal-access.enabled",()->true);r.add("internal-access.issuer",()->"test-issuer");r.add("internal-access.audience",()->"risk-assessment-service");
        r.add("internal-access.public-keys",()->"{\"test\":\""+Base64.getEncoder().encodeToString(RSA.getPublic().getEncoded())+"\"}");
        r.add("internal-access.service-permissions",()->"{\"payment-service\":[\"RISK_ASSESSMENT_SUBMIT\"],\"approval-service\":[\"RISK_ASSESSMENT_READ\"]}");}
    @Autowired MockMvc mvc;@MockitoBean RiskSubmissionService service;
    String token(String identity,String permission){return Jwts.builder().header().type(InternalServiceVerifier.TYPE).keyId("test").and().issuer("test-issuer").audience().add("risk-assessment-service").and().subject(identity).claim("principalType","SERVICE").claim("permissions",List.of(permission)).expiration(Date.from(Instant.now().plusSeconds(120))).signWith(RSA.getPrivate(),Jwts.SIG.RS256).compact();}
    String credential(){return token("payment-service",ServicePermissions.RISK_SUBMIT);}
    RiskSubmissionService.Status response(String state){return new RiskSubmissionService.Status("9007199254740993","7",state,"2026-10-15T01:02:03.123456789Z","4","9",null,null,!state.equals("BLOCKED"));}
    @ParameterizedTest @ValueSource(strings={"ACCEPTED","IN_PROGRESS","RETRYABLE_FAILURE","BLOCKED"})
    void pendingStatesAre202WithoutFabricatedScore(String state)throws Exception{when(service.submit(eq("payment-service"),any())).thenReturn(response(state));
        mvc.perform(post(ROOT).header("Authorization","bearer "+credential()).contentType("application/json").content(BODY)).andExpect(status().isAccepted()).andExpect(jsonPath("$.assessmentId").value("9007199254740993"))
            .andExpect(jsonPath("$.status").value(state)).andExpect(jsonPath("$.result").isEmpty()).andExpect(content().string(not(containsString("lease"))));}
    @Test void completedIs200AndDoesNotInviteResubmission()throws Exception{when(service.submit(anyString(),any())).thenReturn(new RiskSubmissionService.Status("1","5","COMPLETED","2026-10-15T00:00:00Z","4","9","2026-10-15T00:00:01Z",new com.ofss.risk.persistence.AssessmentReadView.Score(120,100,100,"HIGH"),false));
        mvc.perform(post(ROOT).header("Authorization","Bearer "+credential()).contentType("application/json").content(BODY)).andExpect(status().isOk()).andExpect(header().doesNotExist("Retry-After")).andExpect(jsonPath("$.result.finalScore").value(100));}
    @Test void submitPermissionCannotReadOtherAssessments()throws Exception{mvc.perform(get(ROOT+"/1").header("Authorization","Bearer "+credential())).andExpect(status().isForbidden());verifyNoInteractions(service);}
    @Test void readerPermissionCannotSubmitOrLookupStatus()throws Exception{String token=token("approval-service",ServicePermissions.RISK_ASSESSMENT);
        mvc.perform(post(ROOT).header("Authorization","Bearer "+token).contentType("application/json").content(BODY)).andExpect(status().isForbidden());
        mvc.perform(get("/internal/v1/risk-assessment-submissions/status").param("submissionSource","payment.v1").param("submissionKey","s1").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());verifyNoInteractions(service);}
    @ParameterizedTest @ValueSource(strings={"missing","invalid","user"})
    void userCredentialsNeverSubmit(String type)throws Exception{var request=post(ROOT).contentType("application/json").content(BODY);
        if(type.equals("invalid"))request.header("Authorization","Bearer invalid");
        if(type.equals("user"))request.header("Authorization","Bearer "+Jwts.builder().subject("manager").claim("userId",42).claim("roles",List.of("FINANCE_MANAGER")).expiration(Date.from(Instant.now().plusSeconds(120))).signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(HMAC.getBytes(java.nio.charset.StandardCharsets.UTF_8))).compact());
        mvc.perform(request).andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate",containsString("Bearer")));verifyNoInteractions(service);}
    @ParameterizedTest @ValueSource(strings={"{}","null","[]","{\"score\":0}","{\"contractVersion\":\"risk-submission-v2\"}"})
    void malformedRequests400(String body)throws Exception{mvc.perform(post(ROOT).header("Authorization","Bearer "+credential()).contentType("application/json").content(body)).andExpect(status().isBadRequest());verifyNoInteractions(service);}
    @ParameterizedTest @ValueSource(strings={"SUBMISSION_CONFLICT","SOURCE_FORBIDDEN","ADAPTER_UNAVAILABLE"})
    void sanitizedErrorsHaveCorrectStatus(String name)throws Exception{var code=SubmissionFailure.Code.valueOf(name);when(service.submit(anyString(),any())).thenThrow(new SubmissionFailure(code));
        mvc.perform(post(ROOT).header("Authorization","Bearer "+credential()).contentType("application/json").content(BODY)).andExpect(status().is(name.equals("SUBMISSION_CONFLICT")?409:name.equals("SOURCE_FORBIDDEN")?403:503)).andExpect(jsonPath("$.code").value(name));}
    @Test void statusLookupUsesAuthenticatedIdentityAndNeedsBothKeys()throws Exception{when(service.status("payment-service","payment.v1","s1")).thenReturn(response("IN_PROGRESS"));
        mvc.perform(get("/internal/v1/risk-assessment-submissions/status").param("submissionSource","payment.v1").param("submissionKey","s1").header("Authorization","Bearer "+credential())).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("IN_PROGRESS"));
        mvc.perform(get("/internal/v1/risk-assessment-submissions/status").header("Authorization","Bearer "+credential())).andExpect(status().isBadRequest());verify(service).status("payment-service","payment.v1","s1");}
    @Test void statusRejectsAmbiguousAndUnknownParametersBeforeLookup()throws Exception{
        for(String parameter:List.of("submissionKey","submissionSource","unexpected")){
            mvc.perform(get("/internal/v1/risk-assessment-submissions/status").param("submissionSource","payment.v1").param("submissionKey","s1").param(parameter,"other")
                .header("Authorization","Bearer "+credential())).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SUBMISSION"));
        }
        verifyNoInteractions(service);
    }
}
