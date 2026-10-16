package com.ofss.risk.persistence;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class SubmissionCollisionTest {
    SQLException known() { return new SQLException("ORA-00001: unique constraint (D2_RISK.UK_RSK_SUBMISSION) violated", "23000", 1); }
    @ParameterizedTest @ValueSource(strings = {"ORA-00001: no identity", "ORA-00001: unique constraint (D2_RISK.PK_RSK_ASM) violated; detail (D2_RISK.UK_RSK_SUBMISSION)",
            "ORA-00001: unique constraint (\"d2_risk\".\"UK_RSK_SUBMISSION\") violated"})
    void unknownOrMisleadingIdentityIsNotRecognized(String message) { assertFalse(SubmissionCollision.matches(new SQLException(message, "23000", 1))); }
    @Test void errorCodeIsRequired() { assertFalse(SubmissionCollision.matches(new SQLException(known().getMessage(), "23000", 2290))); }
    @Test void mixedConstraintChainsFailClosedAndCyclesTerminate() {
        var e = known(); e.setNextException(new SQLException("ORA-00001: unique constraint (D2_RISK.PK_RSK_ASM) violated", "23000", 1));
        assertFalse(SubmissionCollision.matches(e));
        var cyclic = known(); var wrapped = new SQLException("wrapper"); cyclic.initCause(wrapped); wrapped.initCause(cyclic);
        assertTrue(SubmissionCollision.matches(cyclic));
    }
}
