package com.ofss.risk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Manager/internal read host; submission workers are explicitly opt-in and have no scheduler. */
@SpringBootApplication(scanBasePackages="com.ofss.risk.http")
public class RiskAssessmentApplication {
    public static void main(String[] args) { SpringApplication.run(RiskAssessmentApplication.class,args); }
}
