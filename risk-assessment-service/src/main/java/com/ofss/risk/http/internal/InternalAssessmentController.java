package com.ofss.risk.http.internal;

import com.ofss.risk.http.AssessmentReadController;
import com.ofss.risk.persistence.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1/risk-assessments")
public class InternalAssessmentController {
    private final AssessmentReadService service;
    public InternalAssessmentController(AssessmentReadService service) { this.service=service; }
    private AssessmentReadView completed(String id) {
        var view=service.get(id);
        if(!"COMPLETED".equals(view.status())) throw new AssessmentNotCompletedException();
        return view;
    }
    @GetMapping("/{assessmentId}") public AssessmentReadController.Summary summary(@PathVariable String assessmentId) {
        var v=completed(assessmentId);return new AssessmentReadController.Summary(v.assessmentId(),v.rowVersion(),v.status(),v.createdAt(),v.cutoff(),v.evaluatedAt(),v.completedAt(),
            v.configurationVersionId(),v.configurationRowVersion(),v.evaluatorVersion(),v.result());
    }
    @GetMapping("/{assessmentId}/factors") public AssessmentReadController.Factors factors(@PathVariable String assessmentId) {
        var v=completed(assessmentId);return new AssessmentReadController.Factors(v.assessmentId(),v.rowVersion(),v.status(),v.factors());
    }
    @GetMapping("/{assessmentId}/configuration-snapshot") public AssessmentReadController.ConfigurationSnapshot snapshot(@PathVariable String assessmentId) {
        var v=completed(assessmentId);return new AssessmentReadController.ConfigurationSnapshot(v.assessmentId(),v.rowVersion(),v.status(),v.snapshotId(),v.configurationHash(),v.evaluatorVersion(),v.configuration());
    }
}
