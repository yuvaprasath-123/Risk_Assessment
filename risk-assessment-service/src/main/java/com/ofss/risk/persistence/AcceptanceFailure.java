package com.ofss.risk.persistence;

/** Public boundary errors never retain JDBC diagnostics, identifiers or supplied values. */
public final class AcceptanceFailure extends RuntimeException {
    public enum Code { CONFIGURATION_REQUIRED, SUBMISSION_CONFLICT, RISK_PERSISTENCE_UNAVAILABLE }
    private final Code code;
    private AcceptanceFailure(Code code, String message) { super(message); this.code = code; }
    public Code code() { return code; }
    static AcceptanceFailure required() { return new AcceptanceFailure(Code.CONFIGURATION_REQUIRED, "Configuration is required for first acceptance"); }
    static AcceptanceFailure conflict() { return new AcceptanceFailure(Code.SUBMISSION_CONFLICT, "Submission was accepted with different immutable facts or configuration"); }
    static AcceptanceFailure unavailable() { return new AcceptanceFailure(Code.RISK_PERSISTENCE_UNAVAILABLE, "Risk persistence is temporarily unavailable"); }
}
