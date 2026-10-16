package com.ofss.risk.persistence;

/** Only fixed sanitized diagnostics are accepted, never Throwable messages or upstream payloads. */
public enum WorkDiagnostic {
    TRANSIENT_FAILURE("RETRYABLE_FAILURE", "Work could not be completed; retry is permitted."),
    INTERVENTION_REQUIRED("BLOCKED", "Work requires explicit intervention before it can proceed.");
    private final String state, message;
    WorkDiagnostic(String state, String message) { this.state = state; this.message = message; }
    public String state() { return state; }
    public String message() { return message; }
}
