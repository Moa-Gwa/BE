package com.moagwa.crawler;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "crawler", name = "enabled", havingValue = "true")
class CrawlScheduler {

    private final CrawlerService crawler;

    @Scheduled(fixedDelayString = "${crawler.interval:PT10M}", initialDelayString = "${crawler.initial-delay:PT30S}")
    void run() {
        crawler.crawlAll();
    }
}
