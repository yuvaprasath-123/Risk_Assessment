package com.ofss.risk.http;

import com.ofss.risk.persistence.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(AssessmentReadController.PATH)
public class AssessmentReadController {
    public static final String PATH="/api/v1/risk-assessments";
    private final AssessmentReadService service;
    public AssessmentReadController(AssessmentReadService service) { this.service=service; }
    public record Summary(String assessmentId,String rowVersion,String status,String createdAt,String cutoff,String evaluatedAt,String completedAt,
                          String configurationVersionId,String configurationRowVersion,String evaluatorVersion,AssessmentReadView.Score result) {}
    public record Contribution(String ruleCode,String outcome,int configuredWeight,int contribution) {}
    public record BreakdownResult(long rawScore,int finalScore,int maxFinalScore,String band,List<Contribution> contributions) {}
    public record Breakdown(String assessmentId,String rowVersion,String status,BreakdownResult result) {}
    public record Factors(String assessmentId,String rowVersion,String status,List<AssessmentReadView.Factor> factors) {}
    public record ConfigurationSnapshot(String assessmentId,String rowVersion,String status,String snapshotId,String configurationHash,String evaluatorVersion,Map<String,Object> configuration) {}
    @GetMapping("/{assessmentId}") public Summary summary(@PathVariable("assessmentId") String id) {
        var v=service.get(id); return new Summary(v.assessmentId(),v.rowVersion(),v.status(),v.createdAt(),v.cutoff(),v.evaluatedAt(),v.completedAt(),
            v.configurationVersionId(),v.configurationRowVersion(),v.evaluatorVersion(),v.result());
    }
    @GetMapping("/{assessmentId}/score-breakdown") public Breakdown breakdown(@PathVariable("assessmentId") String id) {
        var v=service.get(id); var s=v.result();
        return new Breakdown(v.assessmentId(),v.rowVersion(),v.status(),s==null?null:new BreakdownResult(s.rawScore(),s.finalScore(),s.maxFinalScore(),s.band(),
            v.factors().stream().map(f->new Contribution(f.ruleCode(),f.outcome(),f.configuredWeight(),f.contribution())).toList()));
    }
    @GetMapping("/{assessmentId}/factors") public Factors factors(@PathVariable("assessmentId") String id) {
        var v=service.get(id); return new Factors(v.assessmentId(),v.rowVersion(),v.status(),v.factors());
    }
    @GetMapping("/{assessmentId}/configuration-snapshot") public ConfigurationSnapshot snapshot(@PathVariable("assessmentId") String id) {
        var v=service.get(id); return new ConfigurationSnapshot(v.assessmentId(),v.rowVersion(),v.status(),v.snapshotId(),v.configurationHash(),v.evaluatorVersion(),v.configuration());
    }
}
