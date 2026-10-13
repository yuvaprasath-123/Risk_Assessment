package com.ofss.configuration.version;

import java.util.List;
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
}
