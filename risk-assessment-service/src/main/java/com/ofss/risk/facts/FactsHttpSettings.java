package com.ofss.risk.facts;

import com.ofss.risk.configuration.ConfigurationHttpSettings;
import java.net.URI;
import java.time.Duration;

/** Trusted origin, independent credentials and finite phase bounds. Reuses the existing origin/TLS rules. */
public record FactsHttpSettings(URI origin,String providerServiceId,Duration connectTimeout,
        Duration headerTimeout,Duration bodyTimeout,int maxResponseBytes) {
    public FactsHttpSettings {
        try {
            new ConfigurationHttpSettings(origin,connectTimeout,headerTimeout,maxResponseBytes);
            new ConfigurationHttpSettings(origin,connectTimeout,bodyTimeout,maxResponseBytes);
            if(providerServiceId==null||!providerServiceId.matches("[a-z][a-z0-9-]{0,63}"))throw invalid();
        }catch(RuntimeException exception){throw invalid();}
    }
    public static IllegalStateException invalid(){return new IllegalStateException("Complete valid external Facts HTTP adapter settings are required");}
    @Override public String toString(){return "Facts HTTP adapter settings [values withheld]";}
}
