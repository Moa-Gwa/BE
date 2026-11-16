package com.moagwa.crawler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moagwa.crawler.InstagramNoticeImporter.Account;
import com.moagwa.crawler.domain.CrawlSource;
import com.moagwa.crawler.domain.CrawlSourceRepository;
import com.moagwa.crawler.domain.CrawledNoticeRepository;
import com.moagwa.notification.PushRequested;
import com.moagwa.organization.OrganizationRepository;
import com.moagwa.organization.OrganizationType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "moagwa.instagram-snapshot-import.enabled=true")
@AutoConfigureMockMvc
@RecordApplicationEvents
@Transactional
class InstagramSnapshotImportTest {
    @Autowired InstagramNoticeImporter importer;
    @Autowired ObjectMapper mapper;
    @Autowired CrawlSourceRepository sources;
    @Autowired CrawledNoticeRepository notices;
    @Autowired OrganizationRepository organizations;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ApplicationEvents events;
    @Autowired ApplicationContext context;

    List<Account> accounts() throws Exception {
        try (var in = new ClassPathResource("db/cuk_instagram_notices_sept2026.json").getInputStream()) {
            return mapper.readValue(in, new TypeReference<>() {});
        }
    }
    Map<String, Integer> manifest() throws Exception {
        try (var in = new ClassPathResource("static/instagram/manifest.json").getInputStream()) {
            return mapper.readValue(in, new TypeReference<>() {});
        }
    }
    CrawlSource source(Account account) {
        return sources.findByCode("instagram_student_council_" + account.instagram()).orElseThrow();
    }

    @Test void startupImportsAllAccountsAndPostsWithoutLocalDevelopmentAccountsOrAlerts() throws Exception {
        for (Account account : accounts()) {
            var source = source(account);
            assertThat(source.isEnabled()).isFalse();
            assertThat(source.getOrganization().getType()).isEqualTo(OrganizationType.STUDENT_GOVERNMENT);
            assertThat(source.getOrganization().getExternalUrl()).isEqualTo(account.profileUrl());
            assertThat(notices.findExistingExternalIds(source.getId(), account.posts().stream().map(InstagramNoticeImporter.Post::externalId).toList()))
                    .containsAll(account.posts().stream().map(InstagramNoticeImporter.Post::externalId).toList());
        }
        assertThat(context.containsBean("devDataInitializer")).isFalse();
        assertThat(context.containsBean("marketDevData")).isFalse();
        assertThat(events.stream(CrawledNoticeCreatedEvent.class)).isEmpty();
        assertThat(events.stream(PushRequested.class)).isEmpty();
    }

    @Test void missingPostIsImportedAndExistingApisServeCouncilFeedDetailsAndPackagedImages() throws Exception {
        Account account = accounts().get(0);
        var post = account.posts().get(0);
        var source = source(account);
        var existing = notices.findBySourceAndExternalId(source.getId(), post.externalId()).orElseThrow();
        notices.delete(existing);
        notices.flush();
        long userCount = jdbc.queryForObject("SELECT count(*) FROM users", Long.class);
        long adminCount = jdbc.queryForObject("SELECT count(*) FROM admins", Long.class);
        importer.run();
        notices.flush();
        var saved = notices.findBySourceAndExternalId(source.getId(), post.externalId()).orElseThrow();
        assertThat(saved.getContentText()).isEqualTo(post.contentText());
        assertThat(saved.getCategory()).isEqualTo(post.category());
        assertThat(saved.getImageUrls()).isNotEmpty();
        mvc.perform(get("/api/organizations").param("type", "STUDENT_GOVERNMENT").param("q", account.org()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[*].id", hasItem(source.getOrganization().getId().intValue())));
        mvc.perform(get("/api/feed").param("organizationId", source.getOrganization().getId().toString()).param("orgType", "STUDENT_GOVERNMENT"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[*].thumbnail", hasItem(saved.getThumbnailUrl())));
        mvc.perform(get("/api/crawled-notices/" + saved.getId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("CRAWLED"))
                .andExpect(jsonPath("$.images[0]").value(saved.getImageUrls().get(0)));
        mvc.perform(get(saved.getImageUrls().get(0))).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.IMAGE_JPEG));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Long.class)).isEqualTo(userCount);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admins", Long.class)).isEqualTo(adminCount);
        assertThat(events.stream(CrawledNoticeCreatedEvent.class)).isEmpty();
        assertThat(events.stream(PushRequested.class)).isEmpty();
    }

    @Test void repeatedImportsPreserveExistingBodiesCategoriesImagesViewsAndSourceMetadata() throws Exception {
        Account account = accounts().get(0);
        var source = source(account);
        var post = notices.findBySourceAndExternalId(source.getId(), account.posts().get(0).externalId()).orElseThrow();
        post.applyContent(null, "운영에서 변경한 본문");
        post.changeCategory("EVENT");
        post.assignImages(List.of("/uploads/production.jpg"));
        notices.saveAndFlush(post);
        jdbc.update("UPDATE crawled_notices SET view_count=10 WHERE id=?", post.getId());
        jdbc.update("UPDATE organizations SET name='운영 학생회 '||id::text,description='운영 소개' WHERE id=?", source.getOrganization().getId());
        long orgCount = organizations.count(), sourceCount = sources.count(), noticeCount = notices.count();
        importer.run();
        importer.run();
        notices.flush();
        assertThat(organizations.count()).isEqualTo(orgCount);
        assertThat(sources.count()).isEqualTo(sourceCount);
        assertThat(notices.count()).isEqualTo(noticeCount);
        var saved = notices.findById(post.getId()).orElseThrow();
        assertThat(saved.getContentText()).isEqualTo("운영에서 변경한 본문");
        assertThat(saved.getCategory()).isEqualTo("EVENT");
        assertThat(saved.getImageUrls()).containsExactly("/uploads/production.jpg");
        assertThat(jdbc.queryForObject("SELECT view_count FROM crawled_notices WHERE id=?", Integer.class, post.getId())).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT description FROM organizations WHERE id=?", String.class, source.getOrganization().getId())).isEqualTo("운영 소개");
        assertThat(events.stream(CrawledNoticeCreatedEvent.class)).isEmpty();
        assertThat(events.stream(PushRequested.class)).isEmpty();
    }

    @Test void everyImageListedInTheSnapshotIsPackagedAndImageLessPostsAreAllowed() throws Exception {
        var manifest = manifest();
        for (Account account : accounts()) for (var post : account.posts()) {
            int count = manifest.getOrDefault(post.externalId(), 0);
            for (int i = 0; i < count; i++) {
                assertThat(new ClassPathResource("static/instagram/" + post.externalId() + "_" + i + ".jpg").exists()).isTrue();
            }
            if (count == 0) {
                var saved = notices.findBySourceAndExternalId(source(account).getId(), post.externalId()).orElseThrow();
                assertThat(saved.getImageUrls()).isEmpty();
                assertThat(saved.getThumbnailUrl()).isNull();
            }
        }
    }
}
