package com.ofss.risk.http.internal;

import com.ofss.risk.http.error.*;
import com.ofss.risk.http.security.*;
import com.ofss.risk.submission.RiskSubmissionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Internal authentication enabled, submission independently disabled; no write services are wired. */
@WebMvcTest(SubmissionController.class)
@Import({InternalSecurityConfiguration.class,SecurityConfiguration.class,JwtVerifier.class,ErrorResponseWriter.class,ApiExceptionHandler.class})
class SubmissionDisabledApiTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){SubmissionApiTest.properties(r);}
    @Autowired MockMvc mvc;@Autowired ApplicationContext context;
    @Test void allowedServiceReceivesClearDisabledErrorWithoutWriteServices()throws Exception {
        assertTrue(context.getBeansOfType(RiskSubmissionService.class).isEmpty());var token=new SubmissionApiTest().credential();
        mvc.perform(post(SubmissionApiTest.ROOT).header("Authorization","Bearer "+token).contentType("application/json").content(SubmissionApiTest.BODY))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SUBMISSION_DISABLED"));
        mvc.perform(get("/internal/v1/risk-assessment-submissions/status").header("Authorization","Bearer "+token).param("submissionSource","payment.v1").param("submissionKey","s1"))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("SUBMISSION_DISABLED"));
    }
}
