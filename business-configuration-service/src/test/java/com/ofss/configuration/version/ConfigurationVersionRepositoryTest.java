package com.ofss.configuration.version;

import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ConfigurationVersionRepositoryTest {
    @Test
    @SuppressWarnings("unchecked")
    void queryMapsOracleTimestampTypesAndUsesDeterministicOrder() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(eq(ConfigurationVersionRepository.LIST_QUERY), any(RowMapper.class)))
                .thenReturn(List.of());
        assertThat(new ConfigurationVersionRepository(jdbc).findAll()).isEmpty();
        var mapper = ArgumentCaptor.forClass(RowMapper.class);
        verify(jdbc).query(eq(ConfigurationVersionRepository.LIST_QUERY), mapper.capture());
        assertThat(ConfigurationVersionRepository.LIST_QUERY)
                .contains("FROM D2_CONFIGURATION.CONFIGURATION_VERSIONS")
                .contains("ORDER BY VERSION_NUMBER ASC, VERSION_ID ASC");
        ResultSet row = mock(ResultSet.class);
        OffsetDateTime created = OffsetDateTime.parse("2026-10-13T10:00:00+05:30");
        when(row.getLong("VERSION_ID")).thenReturn(Long.MAX_VALUE);
        when(row.getInt("VERSION_NUMBER")).thenReturn(1);
        when(row.getString("VERSION_LABEL")).thenReturn("Draft");
        when(row.getString("PUBLICATION_STATE")).thenReturn("DRAFT");
        when(row.getInt("IS_DEMONSTRATION")).thenReturn(1);
        when(row.getObject("CREATED_AT", OffsetDateTime.class)).thenReturn(created);
        ConfigurationVersion result = (ConfigurationVersion) mapper.getValue().mapRow(row, 0);
        assertThat(result.versionId()).isEqualTo(Long.MAX_VALUE);
        assertThat(result.isDemonstration()).isTrue();
        assertThat(result.createdAt()).isEqualTo(created);
        assertThat(result.publishedAt()).isNull();
        verifyNoMoreInteractions(jdbc);
    }
}
