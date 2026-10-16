package com.ofss.risk.persistence;

import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

/** No application context, initializer or service startup. Connection only when an opted-in test asks for it. */
final class OracleAcceptanceConnection {
    static DataSource externalDataSource() {
        String username = environment("RISK_DB_USERNAME"), password = environment("RISK_DB_PASSWORD");
        assertTrue("D2_RISK".equals(username), "Write verification requires D2_RISK");
        String url = System.getenv("RISK_DB_URL");
        if (url == null || url.isBlank()) url = "jdbc:oracle:thin:@//localhost:1521/FREEPDB1";
        var source = new DriverManagerDataSource(url, username, password);
        try (var c = source.getConnection(); var s = c.createStatement(); var rows = s.executeQuery(OracleReadOnlyVerificationTest.IDENTITY)) {
            assertTrue(rows.next(), "Missing Oracle identity");
            assertTrue("FREEPDB1".equals(rows.getString(1)) && "D2_RISK".equals(rows.getString(2))
                    && "D2_RISK".equals(rows.getString(3)), "Unexpected Oracle owner/container; no writes allowed");
        } catch (java.sql.SQLException failure) { throw new AssertionError("Oracle connection guard failed; no write tests started"); }
        return source;
    }
    private static String environment(String name) {
        String value = System.getenv(name);
        assertTrue(value != null && !value.isBlank(), "Supply " + name + " externally"); return value;
    }
    private OracleAcceptanceConnection() {}
}
