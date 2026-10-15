package com.ofss.risk.submission;

/** Fixed public codes only; never retains dependency messages or request bodies. */
public final class SubmissionFailure extends RuntimeException {
    public enum Code { INVALID_SUBMISSION, SOURCE_FORBIDDEN, SUBMISSION_NOT_FOUND, SUBMISSION_CONFLICT,
        SUBMISSION_DISABLED, ADAPTER_UNAVAILABLE, DEPENDENCY_UNAVAILABLE, CUTOFF_NOT_REPRODUCIBLE, SUBMISSION_UNAVAILABLE }
    private final Code code;
    public SubmissionFailure(Code code) { super(code.name()); this.code=code; }
    public Code code() { return code; }
}
