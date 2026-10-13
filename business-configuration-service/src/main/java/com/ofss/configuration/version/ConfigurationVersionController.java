package com.ofss.configuration.version;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ConfigurationVersionController {
    public static final String PATH = "/api/v1/configuration/versions";
    private final ConfigurationVersionService service;

    public ConfigurationVersionController(ConfigurationVersionService service) {
        this.service = service;
    }

    @GetMapping(PATH)
    public List<ConfigurationVersionResponse> listVersions() {
        return service.listVersions();
    }
}
