package com.moagwa.crawler;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({CrawlerProperties.class, InstagramCrawlerProperties.class})
public class CrawlerConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
