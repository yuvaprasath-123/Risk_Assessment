package com.ofss.configuration.version;

import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ConfigurationVersionRepository {
    static final String LIST_QUERY = """
            SELECT VERSION_ID, VERSION_NUMBER, VERSION_LABEL, PUBLICATION_STATE,
                   IS_DEMONSTRATION, CREATED_AT, PUBLISHED_AT
            FROM D2_CONFIGURATION.CONFIGURATION_VERSIONS
            ORDER BY VERSION_NUMBER ASC, VERSION_ID ASC
            """;

    private final JdbcTemplate jdbc;

    public ConfigurationVersionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ConfigurationVersion> findAll() {
        return jdbc.query(LIST_QUERY, (row, index) -> new ConfigurationVersion(
                row.getLong("VERSION_ID"), row.getInt("VERSION_NUMBER"),
                row.getString("VERSION_LABEL"), row.getString("PUBLICATION_STATE"),
                row.getInt("IS_DEMONSTRATION") == 1,
                row.getObject("CREATED_AT", OffsetDateTime.class),
                row.getObject("PUBLISHED_AT", OffsetDateTime.class)));
    }
}
