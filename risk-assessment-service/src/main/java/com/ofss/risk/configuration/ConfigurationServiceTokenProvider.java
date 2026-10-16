package com.ofss.risk.configuration;

/** Externally provisioned service JWT only. No issuance, user-token propagation or signing-key access. */
@FunctionalInterface
public interface ConfigurationServiceTokenProvider {
    String token();
}
