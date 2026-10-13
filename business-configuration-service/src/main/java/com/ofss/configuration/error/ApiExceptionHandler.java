package com.ofss.configuration.error;

import jakarta.servlet.http.HttpServletRequest;
import com.ofss.configuration.version.InvalidDraftRequestException;
import com.ofss.configuration.version.VersionAllocationConflictException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler({InvalidDraftRequestException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<ApiError> invalidDraft(HttpServletRequest request) {
        return ResponseEntity.badRequest().body(ApiError.of(400, "INVALID_REQUEST",
                "Invalid draft configuration request", request.getRequestURI()));
    }

    @ExceptionHandler(VersionAllocationConflictException.class)
    public ResponseEntity<ApiError> allocationConflict(HttpServletRequest request) {
        return ResponseEntity.status(409).body(ApiError.of(409, "VERSION_ALLOCATION_CONFLICT",
                "A configuration version could not be allocated; refresh and try again",
                request.getRequestURI()));
    }

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
