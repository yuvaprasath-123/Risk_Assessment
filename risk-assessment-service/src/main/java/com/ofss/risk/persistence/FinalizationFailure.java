package com.ofss.risk.persistence;

public final class FinalizationFailure extends RuntimeException {
    public enum Code { FROZEN_INPUT_REQUIRED, FINALIZATION_CONFLICT, EVALUATOR_UNAVAILABLE,
        EVALUATION_TECHNICAL_FAILURE, POLICY_UNRESOLVED, INVALID_EVALUATION, FINALIZATION_UNAVAILABLE }
    private final Code code;
    FinalizationFailure(Code code) { super(code.name()); this.code=code; }
    public Code code() { return code; }
}
