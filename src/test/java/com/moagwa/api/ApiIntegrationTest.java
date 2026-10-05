package com.moagwa.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moagwa.admin.Admin;
import com.moagwa.admin.AdminNoticeService;
import com.moagwa.admin.AdminRepository;
import com.moagwa.auth.JwtService;
import com.moagwa.crawler.domain.CrawlSource;
import com.moagwa.crawler.domain.CrawlSourceRepository;
import com.moagwa.crawler.domain.CrawledNotice;
import com.moagwa.crawler.domain.CrawledNoticeRepository;
import com.moagwa.organization.Organization;
import com.moagwa.organization.OrganizationRepository;
import com.moagwa.organization.OrganizationType;
import com.moagwa.user.User;
import com.moagwa.user.UserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class ApiIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtService jwt;
    @Autowired PasswordEncoder encoder;
    @Autowired OrganizationRepository organizations;
    @Autowired AdminRepository admins;
    @Autowired UserRepository users;
    @Autowired CrawlSourceRepository sources;
    @Autowired CrawledNoticeRepository crawled;
    @Autowired AdminNoticeService adminNotices;

    private Organization council;
    private Organization otherCouncil;
    private Admin admin;
    private Admin otherAdmin;
    private User student;
    private String tag;

    @BeforeEach
    void setUp() {
        tag = UUID.randomUUID().toString().substring(0, 8);
        council = organizations.save(new Organization("학생회-" + tag, OrganizationType.STUDENT_GOVERNMENT));
        otherCouncil = organizations.save(new Organization("다른학생회-" + tag, OrganizationType.STUDENT_GOVERNMENT));
        admin = admins.save(new Admin("a-" + tag, encoder.encode("pw-1234"), "관리자", council));
        otherAdmin = admins.save(new Admin("b-" + tag, encoder.encode("pw-1234"), "다른관리자", otherCouncil));
        student = users.save(new User("DEV", "s-" + tag, "학생" + tag));
    }

    private String adminToken(Admin a) {
        return "Bearer " + jwt.issueAdmin(a.getId(), a.getOrganization().getId());
    }

    private String studentToken() {
        return "Bearer " + jwt.issueStudent(student.getId());
    }

    private JsonNode createNotice(Admin a, Map<String, Object> overrides) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>(Map.of(
                "title", "공지 " + tag, "description", "본문입니다", "categoryIds", List.of(1), "publishMode", "NOW"));
        body.putAll(overrides);
        MvcResult r = mvc.perform(post("/api/admin/notices").header("Authorization", adminToken(a))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void adminLoginReturnsTokenAndRejectsWrongPassword() throws Exception {
        mvc.perform(post("/api/admin/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"a-" + tag + "\",\"password\":\"pw-1234\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.organization.name").value("학생회-" + tag));
        mvc.perform(post("/api/admin/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"a-" + tag + "\",\"password\":\"nope\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminEndpointsRequireAdminAndStudentsAreForbidden() throws Exception {
        mvc.perform(get("/api/admin/notices")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/notices").header("Authorization", studentToken())).andExpect(status().isForbidden());
        mvc.perform(get("/api/me/profile").header("Authorization", adminToken(admin))).andExpect(status().isForbidden());
    }

    @Test
    void publishedNoticeAppearsInFeedAndDetailAndDraftDoesNot() throws Exception {
        JsonNode published = createNotice(admin, Map.of("title", "게시공지 " + tag));
        createNotice(admin, Map.of("title", "초안공지 " + tag, "publishMode", "DRAFT"));

        mvc.perform(get("/api/feed").param("q", tag))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].title").value("게시공지 " + tag))
                .andExpect(jsonPath("$.items[0].source").value("MANUAL"));
        mvc.perform(get("/api/notices/" + published.get("id").asLong()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.description").value("본문입니다"));
        mvc.perform(get("/api/notices/" + published.get("id").asLong() + "x")).andExpect(status().isBadRequest());
    }

    @Test
    void adminCannotTouchAnotherOrganizationsNotice() throws Exception {
        long id = createNotice(admin, Map.of()).get("id").asLong();

        mvc.perform(get("/api/admin/notices/" + id).header("Authorization", adminToken(otherAdmin)))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/admin/notices/" + id).header("Authorization", adminToken(otherAdmin)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/admin/notices/" + id + "/close").header("Authorization", adminToken(otherAdmin)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/admin/notices").header("Authorization", adminToken(otherAdmin)))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void closingAndDeletingUpdatesWhatStudentsSee() throws Exception {
        long id = createNotice(admin, Map.of("title", "마감대상 " + tag)).get("id").asLong();

        mvc.perform(post("/api/admin/notices/" + id + "/close").header("Authorization", adminToken(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.displayStatus").value("CLOSED"));
        mvc.perform(get("/api/feed").param("q", tag).param("status", "CLOSED"))
                .andExpect(jsonPath("$.items.length()").value(1));

        mvc.perform(delete("/api/admin/notices/" + id).header("Authorization", adminToken(admin)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/feed").param("q", tag)).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/notices/" + id)).andExpect(status().isNotFound());
    }

    @Test
    void validationRejectsLongTitleBadLinkAndBackwardsEventTimes() throws Exception {
        String longTitle = "가".repeat(41);
        mvc.perform(post("/api/admin/notices").header("Authorization", adminToken(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("title", longTitle, "description", "x",
                                "categoryIds", List.of(1), "publishMode", "NOW"))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/notices").header("Authorization", adminToken(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("title", "t", "description", "x",
                                "categoryIds", List.of(1), "externalLink", "javascript:alert(1)"))))
                .andExpect(status().isBadRequest());
        Instant now = Instant.now();
        mvc.perform(post("/api/admin/notices").header("Authorization", adminToken(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("title", "t", "description", "x",
                                "categoryIds", List.of(1), "eventStartAt", now.plus(Duration.ofDays(2)).toString(),
                                "eventEndAt", now.plus(Duration.ofDays(1)).toString()))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void scheduledNoticeIsHiddenUntilItsTime() throws Exception {
        Instant at = Instant.now().plus(Duration.ofHours(2));
        JsonNode n = createNotice(admin, Map.of("title", "예약공지 " + tag, "publishMode", "SCHEDULED",
                "publishAt", at.toString()));
        assertThat(n.get("status").asText()).isEqualTo("SCHEDULED");
        mvc.perform(get("/api/feed").param("q", tag)).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(post("/api/admin/notices").header("Authorization", adminToken(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("title", "t", "description", "x",
                                "categoryIds", List.of(1), "publishMode", "SCHEDULED",
                                "publishAt", Instant.now().minusSeconds(60).toString()))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deadlineSoonNoticeShowsInNowFeedWithStatusFilter() throws Exception {
        createNotice(admin, Map.of("title", "곧마감 " + tag, "deadlineAt",
                Instant.now().plus(Duration.ofHours(5)).toString()));

        mvc.perform(get("/api/feed").param("q", tag).param("status", "DEADLINE_SOON"))
                .andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get("/api/feed").param("q", tag).param("status", "UPCOMING"))
                .andExpect(jsonPath("$.items.length()").value(0));
        MvcResult now = mvc.perform(get("/api/feed/now")).andExpect(status().isOk()).andReturn();
        assertThat(now.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).contains("곧마감 " + tag);
    }

    @Test
    void studentBookmarkAndInterestsDrivePersonalizedFeedAndNotifications() throws Exception {
        mvc.perform(put("/api/me/profile").header("Authorization", studentToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("nickname", "민지", "organizationIds",
                                List.of(council.getId()), "categoryIds", List.of()))))
                .andExpect(status().isOk());
        long id = createNotice(admin, Map.of("title", "관심단체공지 " + tag)).get("id").asLong();
        createNotice(otherAdmin, Map.of("title", "무관공지 " + tag));

        mvc.perform(get("/api/feed/for-me").header("Authorization", studentToken()))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].title").value("관심단체공지 " + tag));
        mvc.perform(get("/api/me/notifications").header("Authorization", studentToken()))
                .andExpect(jsonPath("$.unreadCount").value(1))
                .andExpect(jsonPath("$.items[0].noticeId").value(id));

        mvc.perform(put("/api/me/bookmarks/MANUAL/" + id).header("Authorization", studentToken()))
                .andExpect(status().isNoContent());
        mvc.perform(put("/api/me/bookmarks/MANUAL/" + id).header("Authorization", studentToken()))
                .andExpect(status().isNoContent()); // idempotent
        mvc.perform(get("/api/notices/" + id).header("Authorization", studentToken()))
                .andExpect(jsonPath("$.bookmarked").value(true)).andExpect(jsonPath("$.bookmarkCount").value(1));
        mvc.perform(get("/api/me/bookmarks").header("Authorization", studentToken()))
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(delete("/api/me/bookmarks/MANUAL/" + id).header("Authorization", studentToken()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/notices/" + id).header("Authorization", studentToken()))
                .andExpect(jsonPath("$.bookmarkCount").value(0));
    }

    @Test
    void profileRejectsOrganizationsOfTheWrongType() throws Exception {
        mvc.perform(put("/api/me/profile").header("Authorization", studentToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("primaryDepartmentId", council.getId()))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void crawledNoticesShareTheFeedAndHaveTheirOwnDetail() throws Exception {
        Organization dept = organizations.save(new Organization("학과-" + tag, OrganizationType.DEPARTMENT));
        CrawlSource source = sources.save(new CrawlSource("src-" + tag, dept, "https://example.com/list"));
        CrawledNotice c = new CrawledNotice(source, "1", "크롤링공지 " + tag, "https://example.com/1", false,
                Instant.now(), Instant.now());
        c.applyContent("<p>본문</p>", "본문");
        c = crawled.save(c);

        mvc.perform(get("/api/feed").param("q", tag).param("orgType", "DEPARTMENT"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].source").value("CRAWLED"));
        mvc.perform(get("/api/crawled-notices/" + c.getId()))
                .andExpect(jsonPath("$.contentHtml").value("<p>본문</p>"))
                .andExpect(jsonPath("$.externalLink").value("https://example.com/1"));
        mvc.perform(post("/api/crawled-notices/" + c.getId() + "/view")).andExpect(status().isNoContent());
        mvc.perform(get("/api/crawled-notices/" + c.getId())).andExpect(jsonPath("$.viewCount").value(1));
    }

    @Test
    void searchTreatsLikeWildcardsLiterally() throws Exception {
        createNotice(admin, Map.of("title", "일반 " + tag));
        mvc.perform(get("/api/feed").param("q", "%" + tag)).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/feed").param("q", "'; drop table notices; --")).andExpect(status().isOk());
    }

    @Test
    void scheduledNoticesArePublishedWhenDue() throws Exception {
        long id = createNotice(admin, Map.of("title", "곧게시 " + tag, "publishMode", "SCHEDULED",
                "publishAt", Instant.now().plusSeconds(1).toString())).get("id").asLong();
        Thread.sleep(1500);

        assertThat(adminNotices.publishDue()).isGreaterThanOrEqualTo(1);
        mvc.perform(get("/api/notices/" + id)).andExpect(status().isOk());
    }

    @Test
    void dashboardCountsOnlyOwnOrganizationsNotices() throws Exception {
        createNotice(admin, Map.of("title", "진행 " + tag, "deadlineAt", Instant.now().plus(Duration.ofHours(3)).toString()));
        createNotice(admin, Map.of("title", "초안 " + tag, "publishMode", "DRAFT"));
        createNotice(otherAdmin, Map.of("title", "남의것 " + tag));

        mvc.perform(get("/api/admin/dashboard").header("Authorization", adminToken(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organization.name").value("학생회-" + tag))
                .andExpect(jsonPath("$.kpi.active").value(1))
                .andExpect(jsonPath("$.performance.notices").value(2))
                .andExpect(jsonPath("$.activeNotices.length()").value(1))
                .andExpect(jsonPath("$.activeNotices[0].categoryCodes.length()").value(1));
    }

    @Test
    void adminListIncludesCategoriesWithoutLazyLoadingErrors() throws Exception {
        createNotice(admin, Map.of("title", "목록 " + tag));
        mvc.perform(get("/api/admin/notices").header("Authorization", adminToken(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].categoryCodes.length()").value(1));
        mvc.perform(get("/api/admin/schedule").header("Authorization", adminToken(admin))).andExpect(status().isOk());
    }

    @Test
    void scheduleEndpointListsEventsAndDeadlinesInRange() throws Exception {
        Instant event = Instant.now().plus(Duration.ofDays(3));
        createNotice(admin, Map.of("title", "일정공지 " + tag, "eventStartAt", event.toString(),
                "deadlineAt", event.minus(Duration.ofDays(1)).toString()));
        String from = java.time.LocalDate.now().toString();
        String to = java.time.LocalDate.now().plusDays(10).toString();

        MvcResult r = mvc.perform(get("/api/schedule").param("from", from).param("to", to))
                .andExpect(status().isOk()).andReturn();
        assertThat(r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).contains("일정공지 " + tag).contains("DEADLINE", "EVENT");
        mvc.perform(get("/api/schedule").param("from", from).param("to", "2099-01-01"))
                .andExpect(status().isBadRequest());
    }
}
