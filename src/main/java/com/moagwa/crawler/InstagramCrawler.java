package com.moagwa.crawler;

import com.moagwa.crawler.InstagramAccountCatalog.InstagramAccount;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Collects Instagram notices entirely on this server through a logged-in Playwright browser. */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "crawler.instagram", name = "enabled", havingValue = "true")
class InstagramCrawler {

    private final InstagramAccountCatalog accounts;
    private final InstagramLinkCollector links;
    private final InstagramCrawlerProperties properties;
    private final InstagramPostDetailsCollector details;
    private final InstagramImageStorage imageStorage;
    private final InstagramPersistence persistence;

    @Scheduled(fixedDelayString = "${crawler.instagram.interval:P1D}",
            initialDelayString = "${crawler.instagram.initial-delay:PT1M}")
    void crawl() {
        int created = 0;
        for (InstagramAccount account : accounts.accounts()) {
            try {
                var collected = links.collect(List.of(account), properties.resultsLimit());
                var fresh = persistence.newPostLinks(account, collected.get(account));
                // 날짜 기준 수집은 과거 게시물 적재이므로 새 공지 알림 제외
                boolean notify = properties.since() == null;
                int accountCreated = 0, skipped = 0;
                for (int offset = 0; offset < fresh.size(); offset += 150) {
                    var batch = fresh.subList(offset, Math.min(offset + 150, fresh.size()));
                    // 수집 시각으로 대체하지 않음. 저장되지 않은 게시물은 다음 실행에서 재시도됨
                    var posts = details.collect(batch).stream().filter(post -> post.publishedAt() != null).toList();
                    skipped += batch.size() - posts.size();
                    var storedImages = imageStorage.storeImages(posts);
                    var storedPosts = posts.stream().map(post -> withStoredImages(post, storedImages))
                            .filter(post -> !post.imageUrls().isEmpty()).toList();
                    accountCreated += persistence.importPosts(account, storedPosts, notify);
                }
                if (fresh.isEmpty()) persistence.importPosts(account, List.of(), notify);
                if (skipped > 0) {
                    persistence.markFailure(account, skipped + " Instagram posts could not be collected");
                }
                created += accountCreated;
                log.info("Instagram account finished: @{}, {} matching links, {} new notices",
                        account.username(), collected.get(account).size(), accountCreated);
            } catch (Exception e) {
                log.error("Instagram account failed: @{}", account.username(), e);
                persistence.markFailure(account, "Instagram account crawl failed: " + e.getClass().getSimpleName());
                if (e.getMessage() != null && e.getMessage().contains("INSTAGRAM_RATE_LIMIT")) {
                    log.warn("Instagram crawl stopped by request limit after {} new notices", created);
                    return;
                }
            }
        }
        log.info("Local Instagram crawl finished: {} accounts, {} new notices", accounts.accounts().size(), created);
    }

    private static InstagramPostDetailsCollector.InstagramPost withStoredImages(
            InstagramPostDetailsCollector.InstagramPost post, Map<String, List<String>> storedImages) {
        List<String> localUrls = storedImages.get(post.externalId());
        if (localUrls == null) {
            return post;
        }
        return new InstagramPostDetailsCollector.InstagramPost(post.username(), post.externalId(), post.title(), post.url(), post.publishedAt(),
                post.caption(), localUrls);
    }
}
