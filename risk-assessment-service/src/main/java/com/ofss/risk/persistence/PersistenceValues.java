package com.ofss.risk.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.dao.DataRetrievalFailureException;

final class PersistenceValues {
    private PersistenceValues() {}
    static void text(String value, int limit) {
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > limit)
            throw new IllegalArgumentException("Invalid required text or column length");
        unicode(value);
    }
    static void optionalText(String value, int limit) { if (value != null) text(value, limit); }
    static void unicode(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i)))
                    throw new IllegalArgumentException("Invalid Unicode text");
            } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("Invalid Unicode text");
        }
    }
    static long id(String value) {
        try {
            if (value == null || !value.matches("[0-9]+")) throw new IllegalArgumentException("Invalid external numeric ID");
            long id = Long.parseLong(value);
            positive(id);
            return id;
        } catch (NumberFormatException e) { throw new IllegalArgumentException("External ID exceeds Java Long"); }
    }
    static void positive(long value) { if (value < 1) throw new IllegalArgumentException("ID must be positive"); }
    static void timestamp(Instant value) {
        if (value == null || value.atOffset(ZoneOffset.UTC).getYear() < 1 || value.atOffset(ZoneOffset.UTC).getYear() > 9999)
            throw new IllegalArgumentException("Timestamp outside supported Oracle AD range");
    }
    static long number(Object value, boolean positive) {
        try {
            // JDBC NUMBER must be exact; floating-point and textual/ROWID keys are not accepted.
            long result = switch (value) {
                case BigDecimal d -> d.longValueExact();
                case java.math.BigInteger i -> i.longValueExact();
                case Long l -> l;
                case Integer i -> i.longValue();
                case Short s -> s.longValue();
                case Byte b -> b.longValue();
                default -> throw new ArithmeticException();
            };
            if (result < (positive ? 1 : 0)) throw new ArithmeticException();
            return result;
        } catch (ArithmeticException | NullPointerException e) {
            throw new DataRetrievalFailureException("Invalid persisted numeric identity");
        }
    }
}
