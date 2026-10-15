package com.ofss.risk;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** HTTP read host only. Worker/persistence write configuration is not component-scanned. */
@SpringBootApplication(scanBasePackages="com.ofss.risk.http")
public class RiskAssessmentApplication {
    public static void main(String[] args) { SpringApplication.run(RiskAssessmentApplication.class,args); }
}
