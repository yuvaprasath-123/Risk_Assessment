package com.ofss.risk.persistence;

import com.ofss.risk.engine.RiskResult;
import java.time.Instant;

public record CompletedAssessment(String assessmentId,String rowVersion,String evaluatorVersion,String acceptanceHash,
    String configurationHash,String inputHash,Instant evaluatedAt,Instant completedAt,RiskResult result) {}
