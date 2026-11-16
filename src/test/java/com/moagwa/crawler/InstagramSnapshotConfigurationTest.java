package com.moagwa.crawler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moagwa.crawler.domain.CrawlSourceRepository;
import com.moagwa.crawler.domain.CrawledNoticeRepository;
import com.moagwa.organization.OrganizationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class InstagramSnapshotConfigurationTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(InstagramNoticeImporter.class)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(OrganizationRepository.class, () -> mock(OrganizationRepository.class))
            .withBean(CrawlSourceRepository.class, () -> mock(CrawlSourceRepository.class))
            .withBean(CrawledNoticeRepository.class, () -> mock(CrawledNoticeRepository.class));

    @Test void disabledUnlessExplicitlyEnabled() {
        context.run(c -> assertThat(c).doesNotHaveBean(InstagramNoticeImporter.class));
        context.withPropertyValues("moagwa.instagram-snapshot-import.enabled=false")
                .run(c -> assertThat(c).doesNotHaveBean(InstagramNoticeImporter.class));
    }

    @Test void canBeEnabledWithProductionProfileWithoutEnablingLocalProfile() {
        context.withInitializer(c -> c.getEnvironment().setActiveProfiles("prod"))
                .withPropertyValues("moagwa.instagram-snapshot-import.enabled=true")
                .run(c -> {
                    assertThat(c).hasSingleBean(InstagramNoticeImporter.class);
                    assertThat(c.getEnvironment().getActiveProfiles()).containsExactly("prod");
                });
    }
}
