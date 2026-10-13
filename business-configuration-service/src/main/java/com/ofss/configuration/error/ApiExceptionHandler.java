package com.ofss.configuration.error;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<ApiError> databaseUnavailable(HttpServletRequest request) {
        return ResponseEntity.status(503).body(ApiError.of(503, "CONFIGURATION_UNAVAILABLE",
                "Configuration data is temporarily unavailable", request.getRequestURI()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpectedFailure(HttpServletRequest request) {
        return ResponseEntity.internalServerError().body(ApiError.of(500, "INTERNAL_ERROR",
                "The request could not be completed", request.getRequestURI()));
    }
}
