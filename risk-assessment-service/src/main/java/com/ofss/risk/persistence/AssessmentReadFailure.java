package com.ofss.risk.persistence;

public final class AssessmentReadFailure extends RuntimeException {
    public enum Code { INVALID_ASSESSMENT_ID, ASSESSMENT_NOT_FOUND, ASSESSMENT_UNAVAILABLE }
    private final Code code;
    public AssessmentReadFailure(Code code) { super(code.name()); this.code=code; }
    public Code code() { return code; }
}
