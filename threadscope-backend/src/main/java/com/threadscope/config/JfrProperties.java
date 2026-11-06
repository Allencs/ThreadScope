package com.threadscope.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "threadscope.jfr")
public record JfrProperties(
    @DefaultValue("512") int maxUploadMb,
    @DefaultValue("") String incomingDir,
    @DefaultValue("14") int retentionDays,
    @DefaultValue("5000") int virtualThreadDetailLimit,
    @DefaultValue("120") int historyBuckets,
    @DefaultValue("50") int hotspotLimit,
    @DefaultValue("1") int maxConcurrentParses
) {
    public Path incomingDirectory() {
        if (incomingDir != null && !incomingDir.isBlank()) {
            return Path.of(incomingDir);
        }
        return Path.of(System.getProperty("java.io.tmpdir"), "threadscope-jfr");
    }

    public long maxUploadBytes() {
        return maxUploadMb * 1024L * 1024L;
    }
}
