package com.ofss.risk.persistence;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class FrozenInputDecoderTest {
    String json() { return new CanonicalEvidence().input(FreezeFixtures.request(FreezeFixtures.row().lease(),FreezeFixtures.input()),FreezeFixtures.stored(1)).json(); }
    @Test void reconstructsExactTypedInputWithMoneyUnicodeNullsAndNanoseconds() {
        var input=FrozenInputDecoder.read(json()); assertEquals(FreezeFixtures.input(),input); assertNotSame(FreezeFixtures.input(),input);
        assertEquals(2,input.current().amount().scale()); assertEquals(123456789,input.cutoff().getNano());
    }
    @ParameterizedTest @ValueSource(strings={"version","numberMoney","roundedMoney","coercedBoolean","unknownField","duplicateSet"})
    void rejectsUnversionedCoercedOrAmbiguousInput(String defect) {
        String input=json();
        input=switch(defect) {
            case "version" -> input.replace("risk-input-v1","risk-input-v999");
            case "numberMoney" -> input.replace("\"9999999999999.99\"","9999999999999.99");
            case "roundedMoney" -> input.replace("9999999999999.99","1.001");
            case "coercedBoolean" -> input.replace("\"validated\":true","\"validated\":\"true\"");
            case "unknownField" -> input.replace("\"inputFormatVersion\"","\"unexpected\":null,\"inputFormatVersion\"");
            default -> input.replace("\"currentAndRetrySubmissionIds\":[","\"currentAndRetrySubmissionIds\":[\"opaque-engine-id\",");
        };
        String altered=input; assertThrows(IllegalArgumentException.class,()->FrozenInputDecoder.read(altered));
    }
}
