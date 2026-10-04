package com.moagwa.crawler;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "crawler")
public record CrawlerProperties(
        boolean enabled,
        Duration interval,
        int concurrentLimit,
        Duration requestTimeout,
        int maxRetries,
        Duration retryDelay,
        int detailRetryBatch) {

    public CrawlerProperties {
        if (interval == null) interval = Duration.ofMinutes(10);
        if (concurrentLimit <= 0) concurrentLimit = 5;
        if (requestTimeout == null) requestTimeout = Duration.ofSeconds(10);
        if (maxRetries <= 0) maxRetries = 3;
        if (retryDelay == null) retryDelay = Duration.ofSeconds(2);
        if (detailRetryBatch <= 0) detailRetryBatch = 20;
    }
}
