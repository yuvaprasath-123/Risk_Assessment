package com.ofss.risk.persistence;

/** Sanitized errors. Lease failures retain the existing WorkFailure contract. */
public final class FreezeFailure extends RuntimeException {
    public enum Code { EVIDENCE_MISMATCH, EVIDENCE_NOT_READY, FROZEN_EVIDENCE_CONFLICT, EVIDENCE_PERSISTENCE_UNAVAILABLE }
    private final Code code;
    FreezeFailure(Code code) { super(code.name()); this.code=code; }
    public Code code() { return code; }
}
