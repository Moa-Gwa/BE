package com.moagwa.crawler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moagwa.crawler.domain.CrawlSource;
import com.moagwa.crawler.domain.CrawlSourceRepository;
import com.moagwa.crawler.domain.CrawledNotice;
import com.moagwa.crawler.domain.CrawledNoticeRepository;
import com.moagwa.organization.Organization;
import com.moagwa.organization.OrganizationRepository;
import com.moagwa.organization.OrganizationType;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Imports the checked-in Instagram snapshot as student-government notices.
 * Department website notices remain attached to their DEPARTMENT organizations.
 */
@Slf4j
@Component
@Profile("local")
@Order(100)
@RequiredArgsConstructor
class InstagramNoticeImporter implements CommandLineRunner {

    private static final String DATA_FILE = "db/cuk_instagram_notices_sept2026.json";
    private static final String IMAGE_MANIFEST = "static/instagram/manifest.json";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final ObjectMapper objectMapper;
    private final OrganizationRepository organizations;
    private final CrawlSourceRepository sources;
    private final CrawledNoticeRepository notices;

    @Override
    @Transactional
    public void run(String... args) throws IOException {
        List<Account> accounts;
        try (InputStream input = new ClassPathResource(DATA_FILE).getInputStream()) {
            accounts = objectMapper.readValue(input,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, Account.class));
        }

        Map<String, Integer> imageCounts;
        try (InputStream input = new ClassPathResource(IMAGE_MANIFEST).getInputStream()) {
            imageCounts = objectMapper.readValue(input, new TypeReference<>() {});
        }

        int created = 0;
        for (Account account : accounts) {
            Organization council = organizations.findByName(account.org() + " 학생회")
                    .orElseGet(() -> createCouncil(account));
            String sourceCode = "instagram_student_council_" + account.instagram();
            CrawlSource source = sources.findByCode(sourceCode).orElseGet(() -> createSource(sourceCode, council, account));

            for (Post post : account.posts()) {
                List<String> images = IntStream.range(0, imageCounts.getOrDefault(post.externalId(), 0))
                        .mapToObj(i -> "/instagram/" + post.externalId() + "_" + i + ".jpg").toList();
                if (notices.findExistingExternalIds(source.getId(), List.of(post.externalId())).contains(post.externalId())) {
                    notices.findBySourceAndExternalId(source.getId(), post.externalId()).ifPresent(existing -> {
                        if (!existing.getImageUrls().equals(images)) {
                            existing.assignImages(images);
                        }
                        if (!java.util.Objects.equals(existing.getCategory(), post.category())) {
                            existing.changeCategory(post.category());
                        }
                        if (!java.util.Objects.equals(existing.getContentText(), post.contentText())) {
                            existing.applyContent(null, post.contentText());
                        }
                    });
                    continue;
                }
                Instant publishedAt = LocalDate.parse(post.publishedDate()).atStartOfDay(KST).toInstant();
                CrawledNotice notice = new CrawledNotice(source, post.externalId(), post.title(), post.url(),
                        post.pinned(), publishedAt, publishedAt);
                notice.applyContent(null, post.contentText());
                notice.assignImages(images);
                notice.assignCategory(post.category());
                notices.save(notice);
                created++;
            }
        }
        log.info("Instagram student-council snapshot imported: {} new notices", created);
    }

    private Organization createCouncil(Account account) {
        Organization organization = new Organization(account.org() + " 학생회", OrganizationType.STUDENT_GOVERNMENT);
        organization.markVerified();
        organization.updateInfo(account.org() + " 학과학생회 Instagram 공지", null, account.profileUrl());
        return organizations.save(organization);
    }

    private CrawlSource createSource(String code, Organization council, Account account) {
        CrawlSource source = new CrawlSource(code, council, account.profileUrl());
        source.disable();
        return sources.save(source);
    }

    record Account(String org, String instagram, String profileUrl, List<Post> posts) {}

    record Post(String externalId, String title, String url, String publishedDate, boolean pinned,
                String contentText, String category) {}
}
