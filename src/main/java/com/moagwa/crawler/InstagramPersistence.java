package com.moagwa.crawler;

import com.moagwa.crawler.InstagramPostDetailsCollector.InstagramPost;
import com.moagwa.crawler.InstagramAccountCatalog.InstagramAccount;
import com.moagwa.crawler.domain.CrawlSource;
import com.moagwa.crawler.domain.CrawlSourceRepository;
import com.moagwa.crawler.domain.CrawledNotice;
import com.moagwa.crawler.domain.CrawledNoticeRepository;
import com.moagwa.organization.Organization;
import com.moagwa.organization.OrganizationRepository;
import com.moagwa.organization.OrganizationType;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Database operations for one Instagram account; network I/O stays outside this transaction. */
@Component
@RequiredArgsConstructor
class InstagramPersistence {

    private final OrganizationRepository organizations;
    private final CrawlSourceRepository sources;
    private final CrawledNoticeRepository notices;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Transactional(readOnly = true)
    List<InstagramLinkCollector.InstagramPostLink> newPostLinks(InstagramAccount account,
                                                                  List<InstagramLinkCollector.InstagramPostLink> links) {
        Map<String, InstagramLinkCollector.InstagramPostLink> uniqueLinks = uniqueLinks(links);
        if (uniqueLinks.isEmpty()) {
            return List.of();
        }
        return sources.findByCode("instagram_student_council_" + account.username())
                .map(source -> {
                    java.util.Set<String> existing = notices.findExistingExternalIds(source.getId(),
                            List.copyOf(uniqueLinks.keySet()));
                    return uniqueLinks.values().stream()
                            .filter(link -> !existing.contains(link.externalId()))
                            .toList();
                })
                .orElseGet(() -> List.copyOf(uniqueLinks.values()));
    }

    @Transactional
    int importPosts(InstagramAccount account, List<InstagramPost> posts) {
        CrawlSource source = sourceFor(account);
        boolean firstCrawl = source.isFirstCrawl();
        Map<String, InstagramPost> uniquePosts = uniquePosts(posts);
        Map<String, CrawledNotice> existing = notices.findBySourceIdAndExternalIdIn(source.getId(),
                        List.copyOf(uniquePosts.keySet()))
                .stream()
                .collect(java.util.stream.Collectors.toMap(CrawledNotice::getExternalId, notice -> notice));
        Instant now = clock.instant();
        int created = 0;

        List<InstagramPost> fresh = uniquePosts.values().stream()
                .filter(post -> !existing.containsKey(post.externalId()))
                .sorted(Comparator.comparing(post -> post.publishedAt() == null ? Instant.EPOCH : post.publishedAt()))
                .toList();
        for (InstagramPost post : fresh) {
            CrawledNotice notice = new CrawledNotice(source, post.externalId(), post.title(), post.url(), false,
                    post.publishedAt() == null ? now : post.publishedAt(), now);
            notice.applyContent(null, post.caption());
            notice.assignImages(post.imageUrls());
            CrawledNotice saved = notices.save(notice);
            created++;
            if (!firstCrawl) {
                events.publishEvent(new CrawledNoticeCreatedEvent(saved.getId(), source.getId(),
                        source.getOrganization().getId(), saved.getTitle()));
            }
        }
        source.markSuccess(now, posts.isEmpty());
        return created;
    }

    private static Map<String, InstagramPost> uniquePosts(List<InstagramPost> posts) {
        Map<String, InstagramPost> uniquePosts = new LinkedHashMap<>();
        for (InstagramPost post : posts) {
            uniquePosts.merge(post.externalId(), post, InstagramPersistence::newerPost);
        }
        return uniquePosts;
    }

    private static Map<String, InstagramLinkCollector.InstagramPostLink> uniqueLinks(
            List<InstagramLinkCollector.InstagramPostLink> links) {
        Map<String, InstagramLinkCollector.InstagramPostLink> uniqueLinks = new LinkedHashMap<>();
        for (InstagramLinkCollector.InstagramPostLink link : links) {
            uniqueLinks.putIfAbsent(link.externalId(), link);
        }
        return uniqueLinks;
    }

    private static InstagramPost newerPost(InstagramPost first, InstagramPost second) {
        Instant firstPublished = first.publishedAt() == null ? Instant.EPOCH : first.publishedAt();
        Instant secondPublished = second.publishedAt() == null ? Instant.EPOCH : second.publishedAt();
        return secondPublished.isAfter(firstPublished) ? second : first;
    }

    @Transactional
    void markFailure(InstagramAccount account, String error) {
        sourceFor(account).markFailure(error);
    }

    private CrawlSource sourceFor(InstagramAccount account) {
        String code = "instagram_student_council_" + account.username();
        return sources.findByCode(code).orElseGet(() -> {
            Organization organization = organizations.findByName(account.organizationName() + " 학생회")
                    .orElseGet(() -> createOrganization(account));
            CrawlSource source = new CrawlSource(code, organization, account.profileUrl());
            // The local Instagram crawler owns these sources. Keep the HTML-board scheduler away from profile URLs.
            source.disable();
            return sources.save(source);
        });
    }

    private Organization createOrganization(InstagramAccount account) {
        Organization organization = new Organization(account.organizationName() + " 학생회",
                OrganizationType.STUDENT_GOVERNMENT);
        organization.markVerified();
        organization.updateInfo(account.organizationName() + " 학과학생회 Instagram 공지", null, account.profileUrl());
        return organizations.save(organization);
    }
}
