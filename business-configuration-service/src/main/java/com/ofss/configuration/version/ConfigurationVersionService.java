package com.ofss.configuration.version;

import java.util.List;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConfigurationVersionService {
    private final ConfigurationVersionRepository repository;

    public ConfigurationVersionService(ConfigurationVersionRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<ConfigurationVersionResponse> listVersions() {
        return repository.findAll().stream().map(ConfigurationVersionResponse::from).toList();
    }

    @Transactional
    public ConfigurationVersionResponse createDraft(CreateConfigurationVersionRequest request, long actorId) {
        long latest = repository.latestVersionNumber();
        if (latest >= Integer.MAX_VALUE) throw new DataRetrievalFailureException("Version numbers exhausted");
        var created = repository.insertDraft(request, Math.toIntExact(latest + 1), actorId,
                OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS));
        return ConfigurationVersionResponse.from(created);
    }
}
