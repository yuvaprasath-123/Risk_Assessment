package com.ofss.risk.http.internal;

import com.ofss.risk.http.AssessmentReadController;
import com.ofss.risk.http.error.*;
import com.ofss.risk.http.security.*;
import com.ofss.risk.persistence.AssessmentReadService;
import io.jsonwebtoken.Jwts;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({InternalAssessmentController.class,AssessmentReadController.class})
@Import({InternalSecurityConfiguration.class,SecurityConfiguration.class,JwtVerifier.class,ErrorResponseWriter.class,ApiExceptionHandler.class})
class InternalAssessmentDisabledTest {
    static final String KEY=HexFormat.of().formatHex(Jwts.SIG.HS512.key().build().getEncoded());
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("security.jwt.secret",()->KEY);r.add("internal-access.enabled",()->false);}
    @Autowired MockMvc mvc;
    @MockitoBean AssessmentReadService service;
    @Test void allInternalReadsAreUnavailableWithoutOpeningPersistence() throws Exception {
        for(String suffix:List.of("","/factors","/configuration-snapshot"))mvc.perform(get("/internal/v1/risk-assessments/7"+suffix))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("INTERNAL_ACCESS_DISABLED"));verifyNoInteractions(service);
    }
    @Test void existingManagerSummaryStillWorksWithNoServiceKeys() throws Exception {
        when(service.get("7")).thenReturn(InternalAssessmentApiTest.view("COMPLETED"));
        String token=Jwts.builder().subject("manager").claim("userId",42).claim("roles",List.of("FINANCE_MANAGER"))
            .expiration(Date.from(Instant.now().plusSeconds(300))).signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(KEY.getBytes(java.nio.charset.StandardCharsets.UTF_8))).compact();
        mvc.perform(get("/api/v1/risk-assessments/7").header("Authorization","Bearer "+token)).andExpect(status().isOk());verify(service).get("7");
    }
}
