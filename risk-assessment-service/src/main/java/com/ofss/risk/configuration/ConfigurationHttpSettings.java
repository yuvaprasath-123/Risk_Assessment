package com.ofss.risk.configuration;

import java.net.URI;
import java.time.Duration;
import java.util.*;

/** Explicit trusted origin and finite transport bounds; never contains credentials. */
public record ConfigurationHttpSettings(URI origin,Duration connectTimeout,Duration requestTimeout,int maxResponseBytes) {
    public ConfigurationHttpSettings {
        try {
            if(origin==null || origin.getHost()==null || origin.getRawUserInfo()!=null || origin.getRawQuery()!=null || origin.getRawFragment()!=null
                || !(origin.getRawPath().isEmpty() || origin.getRawPath().equals("/")) || origin.getPort()==0 || origin.getPort()>65535)throw new IllegalArgumentException();
            boolean tls="https".equals(origin.getScheme());
            boolean local="http".equals(origin.getScheme())&&Set.of("localhost","127.0.0.1","[::1]","::1").contains(origin.getHost().toLowerCase(Locale.ROOT));
            if(!tls&&!local || origin.getPort() < -1)throw new IllegalArgumentException();
            duration(connectTimeout);duration(requestTimeout);
            if(maxResponseBytes<1 || maxResponseBytes>4*1024*1024)throw new IllegalArgumentException();
        }catch(RuntimeException e){throw invalid();}
    }
    private static void duration(Duration value){if(value==null || value.compareTo(Duration.ofMillis(1))<0 || value.compareTo(Duration.ofSeconds(30))>0)throw invalid();}
    public static IllegalStateException invalid(){return new IllegalStateException("Complete valid external configuration HTTP adapter settings are required");}
    @Override public String toString(){return "Configuration HTTP adapter settings [values withheld]";}
}
