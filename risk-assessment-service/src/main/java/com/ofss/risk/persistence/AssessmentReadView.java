package com.ofss.risk.persistence;

import java.util.*;

/** Explicit public projection: never serialize acceptance/input objects or persistence diagnostics. */
public record AssessmentReadView(String assessmentId,String rowVersion,String status,String createdAt,String cutoff,
        String evaluatedAt,String completedAt,String configurationVersionId,String configurationRowVersion,String evaluatorVersion,
        Score result,List<Factor> factors,String snapshotId,String configurationHash,Map<String,Object> configuration) {
    public AssessmentReadView { factors=factors==null?null:List.copyOf(factors); configuration=Collections.unmodifiableMap(new TreeMap<>(configuration)); }
    public record Score(long rawScore,int finalScore,int maxFinalScore,String band) {}
    public record Factor(String ruleCode,int evaluationOrder,String outcome,int configuredWeight,int contribution,String explanation) {}
}
