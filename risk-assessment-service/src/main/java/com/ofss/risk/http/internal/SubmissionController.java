package com.ofss.risk.http.internal;

import com.ofss.internal.ServicePrincipal;
import com.ofss.risk.submission.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class SubmissionController {
    private final ObjectProvider<RiskSubmissionService> services;
    public SubmissionController(ObjectProvider<RiskSubmissionService> services){this.services=services;}
    private RiskSubmissionService service(){var service=services.getIfAvailable();if(service==null)throw new SubmissionFailure(SubmissionFailure.Code.SUBMISSION_DISABLED);return service;}
    @PostMapping(path="/internal/v1/risk-assessments",consumes=MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<RiskSubmissionService.Status> submit(@AuthenticationPrincipal ServicePrincipal principal,@RequestBody String body) {
        if(body.length()>16384)throw new SubmissionFailure(SubmissionFailure.Code.INVALID_SUBMISSION);
        var response=service().submit(principal.serviceId(),SubmissionCommand.decode(body));
        var builder=ResponseEntity.status("COMPLETED".equals(response.status())?200:202).cacheControl(CacheControl.noStore());
        if(response.retryAllowed())builder.header("Retry-After","2");return builder.body(response);
    }
    @GetMapping("/internal/v1/risk-assessment-submissions/status")
    public ResponseEntity<RiskSubmissionService.Status> status(@AuthenticationPrincipal ServicePrincipal principal,
            @RequestParam String submissionSource,@RequestParam String submissionKey,jakarta.servlet.http.HttpServletRequest request) {
        var parameters=request.getParameterMap();
        if(!parameters.keySet().equals(java.util.Set.of("submissionSource","submissionKey"))
            || parameters.values().stream().anyMatch(values->values.length!=1))throw new SubmissionFailure(SubmissionFailure.Code.INVALID_SUBMISSION);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service().status(principal.serviceId(),submissionSource,submissionKey));
    }
}
