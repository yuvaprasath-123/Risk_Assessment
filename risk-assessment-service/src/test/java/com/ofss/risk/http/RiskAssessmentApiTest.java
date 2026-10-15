package com.ofss.risk.http;

import com.ofss.risk.http.error.*;
import com.ofss.risk.http.security.*;
import com.ofss.risk.persistence.*;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.math.*;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.stream.*;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AssessmentReadController.class)
@Import({SecurityConfiguration.class,JwtVerifier.class,ErrorResponseWriter.class,ApiExceptionHandler.class})
class RiskAssessmentApiTest {
    static final String ID="9007199254740993", ROOT=AssessmentReadController.PATH+"/"+ID;
    static final String CHALLENGE="Bearer realm=\"risk-assessment-service\"";
    static final String SECRET=random(); static final SecretKey KEY=Keys.hmacShaKeyFor(SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    @Autowired MockMvc mvc; @MockitoBean AssessmentReadService service;
    static String random() {byte[] bytes=new byte[32]; new SecureRandom().nextBytes(bytes); return HexFormat.of().formatHex(bytes);}
    @DynamicPropertySource static void settings(DynamicPropertyRegistry p) {p.add("security.jwt.secret",()->SECRET);}
    static String token(Object id,String subject,Object roles,Instant expiry,SecretKey key) {
        var b=Jwts.builder(); if(subject!=null)b.subject(subject); if(id!=null)b.claim("userId",id); if(roles!=null)b.claim("roles",roles);
        if(expiry!=null)b.expiration(Date.from(expiry)); return b.signWith(key).compact();
    }
    static String manager() {return token(1L,"manager",List.of("FINANCE_MANAGER"),Instant.now().plusSeconds(300),KEY);}
    static Stream<String> routes() {return Stream.of("","/score-breakdown","/factors","/configuration-snapshot");}
    static Stream<Arguments> managerCases() {return routes().flatMap(route->Stream.of("Bearer","bearer","BEARER").map(s->Arguments.of(route,s)));}
    static Stream<Arguments> roleCases() {return routes().flatMap(route->Stream.of("PAYMENT_REQUESTER","FINANCE_APPROVER","ROLE_FINANCE_MANAGER").map(r->Arguments.of(route,r)));}
    static AssessmentReadView view(String state) {
        boolean completed=state.equals("COMPLETED"); String stamp="2026-10-15T10:00:00.123456789Z";
        var factors=Arrays.stream(com.ofss.risk.contract.RuleCode.values()).map(c->new AssessmentReadView.Factor(c.name(),c.ordinal()+1,"TRIGGERED",Integer.MAX_VALUE,Integer.MAX_VALUE,"Configured factor matched")).toList();
        return new AssessmentReadView(ID,"9223372036854775807",state,stamp,stamp,completed?stamp:null,completed?stamp:null,ID,"9","risk-scoring-v1",
            completed?new AssessmentReadView.Score(15032385529L,100,100,"HIGH"):null,completed?factors:null,"9007199254740995","a".repeat(64),
            Map.of("versionId",ID,"rowVersion","9","approvalBoundaries",Map.of("2","1234567890123.45"),"meanMultiplier","1.0000000000000001"));
    }
    @ParameterizedTest @MethodSource("managerCases") void managerCanReadAllRoutesWithAnyBearerCase(String route,String scheme) throws Exception {
        when(service.get(ID)).thenReturn(view("COMPLETED"));
        String body=mvc.perform(get(ROOT+route).header("Authorization",scheme+" "+manager())).andExpect(status().isOk())
            .andExpect(jsonPath("$.assessmentId").value(ID)).andExpect(jsonPath("$.rowVersion").value("9223372036854775807"))
            .andExpect(jsonPath("$.status").value("COMPLETED")).andExpect(header().doesNotExist("WWW-Authenticate")).andReturn().getResponse().getContentAsString();
        for(String forbidden:List.of("leaseToken","LEASE_TOKEN","completionFenceHash","inputSnapshot","matchedEvidenceIds","observations","acceptanceJson","diagnostic")) assertFalse(body.contains(forbidden));
        verify(service).get(ID);
    }
    @ParameterizedTest @MethodSource("roleCases") void otherRolesAreForbiddenBeforeReading(String route,String role) throws Exception {
        mvc.perform(get(ROOT+route).header("Authorization","Bearer "+token(1,"user",List.of(role),Instant.now().plusSeconds(300),KEY)))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN")).andExpect(header().doesNotExist("WWW-Authenticate")); verifyNoInteractions(service);
    }
    @ParameterizedTest @MethodSource("routes") void missingAuthenticationChallengesEveryRoute(String route) throws Exception {
        mvc.perform(get(ROOT+route)).andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate",CHALLENGE))
            .andExpect(jsonPath("$.message").value("Authentication is required")); verifyNoInteractions(service);
    }
    @ParameterizedTest @ValueSource(strings={"Basic","Digest","BearerX","Bearer",""}) void unsupportedOrEmptySchemeIsMissingCredentials(String scheme) throws Exception {
        String authorization=scheme.equals("Bearer")?"Bearer ":scheme+" "+manager();
        mvc.perform(get(ROOT).header("Authorization",authorization)).andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate",CHALLENGE)); verifyNoInteractions(service);
    }
    @ParameterizedTest @ValueSource(strings={"Bearer invalid","bearer invalid","BEARER invalid","Bearer invalid extra","Bearer\tinvalid"}) void malformedCredentialsAreRejected(String authorization) throws Exception {
        mvc.perform(get(ROOT).header("Authorization",authorization)).andExpect(status().isUnauthorized()); verifyNoInteractions(service);
    }
    @Test void invalidAndExpiredTokensHaveSanitizedInvalidTokenChallenges() throws Exception {
        for(String jwt:List.of(token(1,"manager",List.of("FINANCE_MANAGER"),Instant.now().minusSeconds(5),KEY),
            token(1,"manager",List.of("FINANCE_MANAGER"),Instant.now().plusSeconds(300),Jwts.SIG.HS512.key().build()),"invalid")) {
            mvc.perform(get(ROOT).header("Authorization","Bearer "+jwt)).andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate",CHALLENGE+", error=\"invalid_token\""))
                .andExpect(jsonPath("$.message").value("Valid authentication is required"));
        } verifyNoInteractions(service);
    }
    static Stream<Arguments> badClaims() {return Stream.of(Arguments.of(null,"manager",List.of("FINANCE_MANAGER")),Arguments.of(0,"manager",List.of("FINANCE_MANAGER")),
        Arguments.of(-1,"manager",List.of("FINANCE_MANAGER")),Arguments.of(new BigDecimal("1.0000000000000001"),"manager",List.of("FINANCE_MANAGER")),
        Arguments.of(new BigInteger("9223372036854775808"),"manager",List.of("FINANCE_MANAGER")),Arguments.of("1.5","manager",List.of("FINANCE_MANAGER")),
        Arguments.of(true,"manager",List.of("FINANCE_MANAGER")),Arguments.of(1," ",List.of("FINANCE_MANAGER")),Arguments.of(1,null,List.of("FINANCE_MANAGER")),
        Arguments.of(1,"manager","FINANCE_MANAGER"),Arguments.of(1,"manager",List.of(1)),Arguments.of(1,"manager",List.of(" ")));}
    @ParameterizedTest @MethodSource("badClaims") void strictClaimsAreRequired(Object id,String subject,Object roles) throws Exception {
        mvc.perform(get(ROOT).header("Authorization","Bearer "+token(id,subject,roles,Instant.now().plusSeconds(300),KEY)))
            .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate",CHALLENGE+", error=\"invalid_token\"")); verifyNoInteractions(service);
    }
    @Test void missingExpiryAndUnsignedTokenAreUnauthorized() throws Exception {
        for(String jwt:List.of(token(1,"manager",List.of("FINANCE_MANAGER"),null,KEY),Jwts.builder().subject("manager").claim("userId",1).claim("roles",List.of("FINANCE_MANAGER")).compact()))
            mvc.perform(get(ROOT).header("Authorization","Bearer "+jwt)).andExpect(status().isUnauthorized()); verifyNoInteractions(service);
    }
    @Test void noRolesIsForbiddenAndIntegralStringLongIdentityIsAccepted() throws Exception {
        mvc.perform(get(ROOT).header("Authorization","Bearer "+token(1,"manager",null,Instant.now().plusSeconds(300),KEY))).andExpect(status().isForbidden());
        when(service.get(ID)).thenReturn(view("COMPLETED"));
        mvc.perform(get(ROOT).header("Authorization","bearer   "+token("9223372036854775807","manager",List.of("FINANCE_MANAGER"),Instant.now().plusSeconds(300),KEY))).andExpect(status().isOk());
    }
    @Test void resultAndSnapshotSerializationRetainExactNumbersStringsAndUtcNanoseconds() throws Exception {
        when(service.get(ID)).thenReturn(view("COMPLETED"));
        mvc.perform(get(ROOT).header("Authorization","Bearer "+manager())).andExpect(status().isOk()).andExpect(jsonPath("$.result.rawScore").value(15032385529L))
            .andExpect(jsonPath("$.completedAt").value("2026-10-15T10:00:00.123456789Z"));
        mvc.perform(get(ROOT+"/score-breakdown").header("Authorization","Bearer "+manager())).andExpect(jsonPath("$.result.contributions.length()").value(7));
        mvc.perform(get(ROOT+"/factors").header("Authorization","Bearer "+manager())).andExpect(jsonPath("$.factors.length()").value(7)).andExpect(jsonPath("$.factors[0].configuredWeight").value(Integer.MAX_VALUE));
        mvc.perform(get(ROOT+"/configuration-snapshot").header("Authorization","Bearer "+manager()))
            .andExpect(jsonPath("$.snapshotId").value("9007199254740995")).andExpect(jsonPath("$.configuration.approvalBoundaries['2']").value("1234567890123.45"))
            .andExpect(jsonPath("$.configuration.meanMultiplier").value("1.0000000000000001"));
    }
    @ParameterizedTest @ValueSource(strings={"ACCEPTED","IN_PROGRESS","RETRYABLE_FAILURE","BLOCKED"}) void unfinishedResponsesAreStatusWithNullResultsAndAvailableSnapshot(String state) throws Exception {
        when(service.get(ID)).thenReturn(view(state));
        mvc.perform(get(ROOT).header("Authorization","Bearer "+manager())).andExpect(status().isOk()).andExpect(jsonPath("$.status").value(state))
            .andExpect(jsonPath("$.result").value(nullValue())).andExpect(jsonPath("$.completedAt").value(nullValue()));
        mvc.perform(get(ROOT+"/score-breakdown").header("Authorization","Bearer "+manager())).andExpect(status().isOk()).andExpect(jsonPath("$.result").value(nullValue()));
        mvc.perform(get(ROOT+"/factors").header("Authorization","Bearer "+manager())).andExpect(status().isOk()).andExpect(jsonPath("$.factors").value(nullValue()));
        mvc.perform(get(ROOT+"/configuration-snapshot").header("Authorization","Bearer "+manager())).andExpect(status().isOk()).andExpect(jsonPath("$.configuration").exists());
    }
    @ParameterizedTest @EnumSource(AssessmentReadFailure.Code.class) void failuresMapToSanitizedStatuses(AssessmentReadFailure.Code code) throws Exception {
        when(service.get(ID)).thenThrow(new AssessmentReadFailure(code)); int expected=switch(code){case INVALID_ASSESSMENT_ID->400;case ASSESSMENT_NOT_FOUND->404;case ASSESSMENT_UNAVAILABLE->503;};
        mvc.perform(get(ROOT).header("Authorization","Bearer "+manager())).andExpect(status().is(expected)).andExpect(jsonPath("$.code").value(code.name())).andExpect(jsonPath("$.timestamp").isString());
    }
    @Test void unexpectedDataErrorsAreSanitizedAndDoNotBecomeAuthenticationFailures() throws Exception {
        when(service.get(ID)).thenThrow(new IllegalStateException("ORA-private payload"));
        mvc.perform(get(ROOT).header("Authorization","Bearer "+manager())).andExpect(status().isServiceUnavailable()).andExpect(header().doesNotExist("WWW-Authenticate"))
            .andExpect(content().string(not(containsString("ORA-"))));
    }
    @Test void writeAndUnspecifiedRoutesAreDenied() throws Exception {
        mvc.perform(post(AssessmentReadController.PATH).header("Authorization","Bearer "+manager())).andExpect(status().isForbidden());
        mvc.perform(get(ROOT+"/input-evidence").header("Authorization","Bearer "+manager())).andExpect(status().isForbidden()); verifyNoInteractions(service);
    }
}
