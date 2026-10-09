package com.moagwa.crawler;

import com.moagwa.crawler.InstagramAccountCatalog.InstagramAccount;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
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
        List<InstagramAccount> configured = accounts.accounts();
        try {
            Map<InstagramAccount, List<InstagramLinkCollector.InstagramPostLink>> linksByAccount = links.collect(
                    configured, properties.resultsLimit());
            Map<InstagramAccount, List<InstagramLinkCollector.InstagramPostLink>> newLinksByAccount = configured.stream()
                    .collect(Collectors.toMap(account -> account,
                            account -> persistence.newPostLinks(account, linksByAccount.get(account))));
            List<InstagramLinkCollector.InstagramPostLink> newLinks = newLinksByAccount.values().stream()
                    .flatMap(List::stream)
                    .toList();
            Map<String, InstagramPostDetailsCollector.InstagramPost> detailsByExternalId = details.collect(newLinks)
                    .stream().collect(Collectors.toMap(InstagramPostDetailsCollector.InstagramPost::externalId,
                            Function.identity(), (first, ignored) -> first));
            List<InstagramPostDetailsCollector.InstagramPost> detailedPosts = newLinks.stream()
                    .map(link -> detailsByExternalId.get(link.externalId()))
                    .filter(java.util.Objects::nonNull)
                    .toList();
            Map<String, List<String>> storedImages = imageStorage.storeImages(detailedPosts);
            Map<String, InstagramPostDetailsCollector.InstagramPost> storedPostsByExternalId = detailedPosts.stream()
                    .map(post -> withStoredImages(post, storedImages))
                    .filter(post -> !post.imageUrls().isEmpty())
                    .collect(Collectors.toMap(InstagramPostDetailsCollector.InstagramPost::externalId,
                            Function.identity(), (first, ignored) -> first));
            int created = 0;
            for (InstagramAccount account : configured) {
                created += persistence.importPosts(account, newLinksByAccount.get(account).stream()
                        .map(link -> storedPostsByExternalId.get(link.externalId()))
                        .filter(java.util.Objects::nonNull)
                        .toList());
            }
            log.info("Local Instagram crawl finished: {} accounts, {} new notices", configured.size(), created);
        } catch (Exception e) {
            String message = "Local Instagram crawl failed";
            log.error(message, e);
            configured.forEach(account -> persistence.markFailure(account, message));
        }
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
