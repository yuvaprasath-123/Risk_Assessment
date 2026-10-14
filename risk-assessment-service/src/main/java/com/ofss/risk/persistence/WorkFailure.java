package com.ofss.risk.persistence;

/** Public errors contain neither SQL causes nor worker-supplied diagnostic text. */
public final class WorkFailure extends RuntimeException {
    public enum Code { WORK_NOT_FOUND, WORK_CONFLICT, LEASE_REJECTED, WORK_COUNTER_EXHAUSTED, WORK_PERSISTENCE_UNAVAILABLE }
    private final Code code;
    WorkFailure(Code code) { super(code.name()); this.code = code; }
    public Code code() { return code; }
}
