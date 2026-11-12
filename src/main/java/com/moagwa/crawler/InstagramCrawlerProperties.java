package com.moagwa.crawler;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Runtime settings for the local Playwright Instagram crawler. */
@ConfigurationProperties(prefix = "crawler.instagram")
public record InstagramCrawlerProperties(
        boolean enabled,
        Duration interval,
        Duration initialDelay,
        int resultsLimit,
        String nodeCommand,
        String profileLinksScript,
        String postDetailsScript,
        Duration commandTimeout) {

    public InstagramCrawlerProperties {
        if (interval == null || interval.isNegative() || interval.isZero()) interval = Duration.ofDays(1);
        if (initialDelay == null || initialDelay.isNegative()) initialDelay = Duration.ofMinutes(1);
        if (resultsLimit <= 0) resultsLimit = 3;
        if (resultsLimit > 20) resultsLimit = 20;
        if (nodeCommand == null || nodeCommand.isBlank()) nodeCommand = "node";
        if (profileLinksScript == null || profileLinksScript.isBlank()) {
            profileLinksScript = "instagram-browser-scraper/src/collect-profile-links.js";
        }
        if (postDetailsScript == null || postDetailsScript.isBlank()) {
            postDetailsScript = "instagram-browser-scraper/src/collect-post-details.js";
        }
        if (commandTimeout == null || commandTimeout.isNegative() || commandTimeout.isZero()) {
            commandTimeout = Duration.ofMinutes(3);
        }
    }
}
