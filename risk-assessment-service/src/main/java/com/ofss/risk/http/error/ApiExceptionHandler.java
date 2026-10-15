package com.ofss.risk.http.error;

import com.ofss.risk.persistence.AssessmentReadFailure;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(com.ofss.risk.http.internal.AssessmentNotCompletedException.class)
    public ResponseEntity<ApiError> notCompleted(HttpServletRequest request) {
        return ResponseEntity.status(409).body(ApiError.of(409,"ASSESSMENT_NOT_COMPLETED","A completed risk assessment is required",request.getRequestURI()));
    }
    @ExceptionHandler(AssessmentReadFailure.class) public ResponseEntity<ApiError> assessment(AssessmentReadFailure failure,HttpServletRequest request) {
        int status=switch(failure.code()) {case INVALID_ASSESSMENT_ID->400;case ASSESSMENT_NOT_FOUND->404;case ASSESSMENT_UNAVAILABLE->503;};
        String message=switch(failure.code()) {case INVALID_ASSESSMENT_ID->"A positive assessment ID within Java Long range is required";
            case ASSESSMENT_NOT_FOUND->"Risk assessment was not found";case ASSESSMENT_UNAVAILABLE->"Risk assessment data is temporarily unavailable";};
        return ResponseEntity.status(status).body(ApiError.of(status,failure.code().name(),message,request.getRequestURI()));
    }
    @ExceptionHandler(Exception.class) public ResponseEntity<ApiError> unavailable(HttpServletRequest request) {
        return ResponseEntity.status(503).body(ApiError.of(503,"ASSESSMENT_UNAVAILABLE","Risk assessment data is temporarily unavailable",request.getRequestURI()));
    }
}
