package com.moagwa.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moagwa.admin.Admin;
import com.moagwa.admin.AdminNoticeService;
import com.moagwa.admin.AdminRepository;
import com.moagwa.auth.AuthCookies;
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
import java.util.Collections;
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
    @org.springframework.boot.test.mock.mockito.MockBean com.moagwa.feed.AcademicCalendar academicCalendar;

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
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

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
    void adminLoginSetsSessionCookieAndRejectsWrongPassword() throws Exception {
        mvc.perform(post("/api/admin/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"a-" + tag + "\",\"password\":\"pw-1234\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(cookie().httpOnly(AuthCookies.NAME, true))
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
    void searchFieldNarrowsToTitleOrBody() throws Exception {
        createNotice(admin, Map.of("title", "제목어" + tag, "description", "본문어" + tag + " 안내"));

        mvc.perform(get("/api/feed").param("q", "본문어" + tag).param("field", "title"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/feed").param("q", "본문어" + tag).param("field", "content"))
                .andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get("/api/feed").param("q", "제목어" + tag).param("field", "content"))
                .andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/feed").param("q", "본문어" + tag))
                .andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get("/api/feed").param("q", "제목어" + tag).param("field", "title"))
                .andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get("/api/feed").param("q", tag).param("field", "body")).andExpect(status().isBadRequest());

        CrawlSource source = sources.save(new CrawlSource("search-" + tag, council, "https://example.com/list"));
        CrawledNotice c = new CrawledNotice(source, "search", "수집제목" + tag, "https://example.com/s", false,
                Instant.now(), Instant.now());
        c.applyContent("<p>수집본문" + tag + "</p>", "수집본문" + tag);
        crawled.save(c);
        mvc.perform(get("/api/feed").param("q", "수집본문" + tag).param("field", "content"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].source").value("CRAWLED"));
        mvc.perform(get("/api/feed").param("q", "수집본문" + tag).param("field", "title"))
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    @Test
    void sameDayBoardNoticesAlternateBetweenOrganizationTypes() throws Exception {
        Organization dept = organizations.save(new Organization("학과-" + tag, OrganizationType.DEPARTMENT));
        CrawlSource councilBoard = sources.save(new CrawlSource("mix-c-" + tag, council, "https://example.com/c"));
        CrawlSource deptBoard = sources.save(new CrawlSource("mix-d-" + tag, dept, "https://example.com/d"));
        Instant day = Instant.now().minus(Duration.ofDays(1)).truncatedTo(java.time.temporal.ChronoUnit.DAYS);
        // 학생회 글은 같은 날 서로 다른 시각(인스타), 학과 글은 날짜만 있는 게시판으로 한 번에 몰아서 수집된 상황
        for (int i = 0; i < 3; i++) {
            Instant at = day.plus(Duration.ofHours(9 + i));
            crawled.save(new CrawledNotice(councilBoard, "c" + i, "섞기" + tag, "https://example.com/c" + i, false, at, at));
        }
        for (int i = 0; i < 3; i++) {
            crawled.save(new CrawledNotice(deptBoard, "d" + i, "섞기" + tag, "https://example.com/d" + i, false, day, day));
        }

        String body = mvc.perform(get("/api/feed").param("q", "섞기" + tag)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        List<String> types = new java.util.ArrayList<>();
        json.readTree(body).get("items").forEach(item -> types.add(item.get("organization").get("type").asText()));
        assertThat(types).hasSize(6);
        for (int i = 1; i < types.size(); i++) {
            assertThat(types.get(i)).as("index %d", i).isNotEqualTo(types.get(i - 1));
        }
    }

    @Test
    void categoryInterestOnlyPullsUniversityNoticesIntoForMe() throws Exception {
        String code = jdbc.queryForObject("SELECT code FROM categories WHERE id = 10", String.class);
        Organization school = organizations.save(new Organization("학교-" + tag, OrganizationType.UNIVERSITY));
        Organization dept = organizations.save(new Organization("무관학과-" + tag, OrganizationType.DEPARTMENT));
        CrawlSource schoolBoard = sources.save(new CrawlSource("cat-s-" + tag, school, "https://example.com/s"));
        CrawlSource deptBoard = sources.save(new CrawlSource("cat-d-" + tag, dept, "https://example.com/d"));
        CrawledNotice schoolNotice = new CrawledNotice(schoolBoard, "s", "학교취업 " + tag, "https://example.com/s1", false,
                Instant.now(), Instant.now());
        schoolNotice.assignCategory(code);
        crawled.save(schoolNotice);
        CrawledNotice deptNotice = new CrawledNotice(deptBoard, "d", "학과취업 " + tag, "https://example.com/d1", false,
                Instant.now(), Instant.now());
        deptNotice.assignCategory(code);
        crawled.save(deptNotice);
        mvc.perform(put("/api/me/profile").header("Authorization", studentToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("nickname", "민지", "organizationIds", List.of(),
                                "categoryIds", List.of(10)))))
                .andExpect(status().isOk());

        String body = mvc.perform(get("/api/feed/for-me").param("size", "100").header("Authorization", studentToken()))
                .andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(body).contains("학교취업 " + tag).doesNotContain("학과취업 " + tag);
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
    }

    @Test
    void nowFeedShowsUrgentToEveryoneAndTimelyNoticesOnlyFromInterests() throws Exception {
        String soon = Instant.now().plus(Duration.ofHours(5)).toString();
        createNotice(admin, Map.of("title", "관심곧마감 " + tag, "deadlineAt", soon));
        createNotice(otherAdmin, Map.of("title", "무관곧마감 " + tag, "deadlineAt", soon));
        createNotice(otherAdmin, Map.of("title", "무관긴급 " + tag, "urgent", true));
        mvc.perform(put("/api/me/profile").header("Authorization", studentToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("nickname", "민지", "organizationIds",
                                List.of(council.getId()), "categoryIds", List.of()))))
                .andExpect(status().isOk());

        String guest = mvc.perform(get("/api/feed/now")).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(guest).contains("무관긴급 " + tag).doesNotContain("관심곧마감 " + tag, "무관곧마감 " + tag);

        String mine = mvc.perform(get("/api/feed/now").header("Authorization", studentToken()))
                .andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(mine).contains("무관긴급 " + tag, "관심곧마감 " + tag).doesNotContain("무관곧마감 " + tag);
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
    void studentReadsNotificationsAndKeepsNotificationSettings() throws Exception {
        mvc.perform(put("/api/me/profile").header("Authorization", studentToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("organizationIds", List.of(council.getId())))))
                .andExpect(status().isOk());
        createNotice(admin, Map.of("title", "알림1 " + tag));
        createNotice(admin, Map.of("title", "알림2 " + tag));
        JsonNode list = json.readTree(mvc.perform(get("/api/me/notifications").header("Authorization", studentToken()))
                .andExpect(jsonPath("$.unreadCount").value(2)).andReturn().getResponse().getContentAsString());

        mvc.perform(put("/api/me/notifications/" + list.get("items").get(0).get("id").asLong() + "/read")
                .header("Authorization", studentToken())).andExpect(status().isNoContent());
        mvc.perform(get("/api/me/notifications").header("Authorization", studentToken()))
                .andExpect(jsonPath("$.unreadCount").value(1)).andExpect(jsonPath("$.items[0].read").value(true));
        mvc.perform(put("/api/me/notifications/read-all").header("Authorization", studentToken()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/me/notifications").header("Authorization", studentToken()))
                .andExpect(jsonPath("$.unreadCount").value(0));

        mvc.perform(get("/api/me/notification-settings").header("Authorization", studentToken()))
                .andExpect(jsonPath("$.newNotice").value(true)).andExpect(jsonPath("$.urgent").value(true));
        Map<String, Boolean> off = Map.of("newNotice", false, "eventStart", true, "deadlineSoon", false, "urgent", true);
        mvc.perform(put("/api/me/notification-settings").header("Authorization", studentToken())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(off))).andExpect(status().isOk());
        mvc.perform(get("/api/me/notification-settings").header("Authorization", studentToken()))
                .andExpect(jsonPath("$.newNotice").value(false)).andExpect(jsonPath("$.deadlineSoon").value(false))
                .andExpect(jsonPath("$.eventStart").value(true));
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
        mvc.perform(post("/api/crawled-notices/" + c.getId() + "/view").header("Authorization", studentToken())).andExpect(status().isNoContent());
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

        adminNotices.publishDue(); // 스케줄러가 먼저 게시할 수 있어 건수 대신 조회 결과로 확인
        mvc.perform(get("/api/notices/" + id)).andExpect(status().isOk());
    }

    private static Map<String, Object> text(String t) {
        return Map.of("type", "TEXT", "text", t);
    }

    private static Map<String, Object> images(String layout, String... urls) {
        return Map.of("type", "IMAGES", "layout", layout, "images", List.of(urls));
    }

    @Test
    void blogStyleBodyKeepsBlockOrderAndLayoutAndDerivesSummaryFields() throws Exception {
        List<Map<String, Object>> blocks = List.of(
                text("첫 문단입니다"), images("CAROUSEL", "/uploads/a.png", "/uploads/b.png"),
                text("둘째 문단"), images("STACK", "https://example.com/c.jpg"));
        long id = createNotice(admin, Map.of("title", "블록 " + tag, "blocks", blocks)).get("id").asLong();

        mvc.perform(get("/api/notices/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocks.length()").value(4))
                .andExpect(jsonPath("$.blocks[0].type").value("TEXT"))
                .andExpect(jsonPath("$.blocks[1].layout").value("CAROUSEL"))
                .andExpect(jsonPath("$.blocks[1].images.length()").value(2))
                .andExpect(jsonPath("$.blocks[3].layout").value("STACK"))
                .andExpect(jsonPath("$.description").value("첫 문단입니다\n\n둘째 문단"))
                .andExpect(jsonPath("$.images.length()").value(3));
        mvc.perform(get("/api/feed").param("q", tag))
                .andExpect(jsonPath("$.items[0].thumbnail").value("/uploads/a.png"));
        mvc.perform(get("/api/notices/" + id + "x")).andExpect(status().isBadRequest());
    }

    @Test
    void plainBodyNoticesStillWorkWithoutBlocks() throws Exception {
        long id = createNotice(admin, Map.of("title", "예전방식 " + tag, "description", "그냥 본문",
                "imageUrls", List.of("/uploads/x.png"))).get("id").asLong();
        mvc.perform(get("/api/notices/" + id))
                .andExpect(jsonPath("$.description").value("그냥 본문"))
                .andExpect(jsonPath("$.blocks").doesNotExist())
                .andExpect(jsonPath("$.images[0]").value("/uploads/x.png"));
    }

    @Test
    void blockValidationRejectsTooManyImagesBadUrlsAndEmptyBodies() throws Exception {
        String[] eleven = new String[11];
        java.util.Arrays.fill(eleven, "/uploads/a.png");
        String[] six = new String[6];
        java.util.Arrays.fill(six, "/uploads/a.png");
        // 6 + 6 images across two groups exceeds the 10-image cap even though each group is allowed
        assertBlocksRejected(List.of(text("본문"), images("STACK", six), images("CAROUSEL", six)));
        assertBlocksRejected(List.of(text("본문"), images("STACK", eleven)));
        assertBlocksRejected(List.of(text("본문"), images("STACK", "javascript:alert(1)")));
        assertBlocksRejected(List.of(text("본문"), images("STACK", "http://insecure.example.com/a.png")));
        assertBlocksRejected(List.of(text("   "), images("STACK", "/uploads/a.png"))); // no text at all
        assertBlocksRejected(List.of(Map.of("type", "VIDEO", "text", "x")));
    }

    private void assertBlocksRejected(List<Map<String, Object>> blocks) throws Exception {
        mvc.perform(post("/api/admin/notices").header("Authorization", adminToken(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("title", "t", "categoryIds", List.of(1),
                                "blocks", blocks))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void editingAndDuplicatingKeepTheBlockBody() throws Exception {
        long id = createNotice(admin, Map.of("title", "원본 " + tag,
                "blocks", List.of(text("글"), images("STACK", "/uploads/a.png")))).get("id").asLong();

        mvc.perform(put("/api/admin/notices/" + id).header("Authorization", adminToken(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("title", "수정 " + tag, "categoryIds", List.of(1),
                                "blocks", List.of(images("CAROUSEL", "/uploads/a.png", "/uploads/b.png"),
                                        text("수정된 글"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocks[0].layout").value("CAROUSEL"))
                .andExpect(jsonPath("$.description").value("수정된 글"));
        mvc.perform(post("/api/admin/notices/" + id + "/duplicate").header("Authorization", adminToken(admin)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.blocks.length()").value(2))
                .andExpect(jsonPath("$.status").value("DRAFT"));
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

    @Test
    void bookmarkedCrawledNoticeAppearsInTheStudentsSchedule() throws Exception {
        Organization dept = organizations.save(new Organization("학과-" + tag, OrganizationType.DEPARTMENT));
        CrawlSource source = sources.save(new CrawlSource("sched-" + tag, dept, "https://example.com/list"));
        CrawledNotice c = crawled.save(new CrawledNotice(source, "1", "수집일정 " + tag, "https://example.com/1", false,
                Instant.now(), Instant.now()));
        jdbc.update("UPDATE crawled_notices SET deadline_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().plus(Duration.ofDays(2))), c.getId());
        String from = java.time.LocalDate.now().toString();
        String to = java.time.LocalDate.now().plusDays(10).toString();
        String path = "$[?(@.title == '수집일정 " + tag + "')]";

        // 저장 전에는 비로그인·로그인 모두 수집 공지가 일정에 없음
        mvc.perform(get("/api/schedule").param("from", from).param("to", to)).andExpect(jsonPath(path).isEmpty());
        mvc.perform(get("/api/schedule").param("from", from).param("to", to).header("Authorization", studentToken()))
                .andExpect(jsonPath(path).isEmpty());

        mvc.perform(put("/api/me/bookmarks/CRAWLED/" + c.getId()).header("Authorization", studentToken()))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/schedule").param("from", from).param("to", to).header("Authorization", studentToken()))
                .andExpect(jsonPath(path + ".type").value("DEADLINE"))
                .andExpect(jsonPath(path + ".bookmarked").value(true));
        // 다른 사람(비로그인)에게는 보이지 않음
        mvc.perform(get("/api/schedule").param("from", from).param("to", to)).andExpect(jsonPath(path).isEmpty());
    }
    @Test
    void noticeViewsCountEachStudentOncePer24HoursAndRejectAnonymousOrAdmin() throws Exception {
        long id = createNotice(admin, Map.of("title", "조회수 " + tag)).get("id").asLong();
        String endpoint = "/api/notices/" + id + "/view";
        mvc.perform(post(endpoint)).andExpect(status().isUnauthorized());
        mvc.perform(post(endpoint).header("Authorization", adminToken(admin))).andExpect(status().isForbidden());
        for (int i = 0; i < 3; i++) {
            mvc.perform(post(endpoint).header("Authorization", studentToken())).andExpect(status().isNoContent());
        }
        mvc.perform(get("/api/notices/" + id)).andExpect(jsonPath("$.viewCount").value(1));
        jdbc.update("UPDATE notice_view_records SET viewed_at = now() - interval '23 hours 59 minutes' WHERE notice_id = ? AND source = 'MANUAL'", id);
        var before = jdbc.queryForObject("SELECT viewed_at FROM notice_view_records WHERE notice_id = ? AND source = 'MANUAL' AND user_id = ?", java.sql.Timestamp.class, id, student.getId());
        mvc.perform(post(endpoint).header("Authorization", studentToken())).andExpect(status().isNoContent());
        mvc.perform(get("/api/notices/" + id)).andExpect(jsonPath("$.viewCount").value(1));
        assertThat(jdbc.queryForObject("SELECT viewed_at FROM notice_view_records WHERE notice_id = ? AND source = 'MANUAL' AND user_id = ?", java.sql.Timestamp.class, id, student.getId())).isEqualTo(before);
        jdbc.update("UPDATE notice_view_records SET viewed_at = now() - interval '24 hours' WHERE notice_id = ? AND source = 'MANUAL'", id);
        mvc.perform(post(endpoint).header("Authorization", studentToken())).andExpect(status().isNoContent());
        mvc.perform(post(endpoint).header("Authorization", studentToken())).andExpect(status().isNoContent());
        mvc.perform(get("/api/notices/" + id)).andExpect(jsonPath("$.viewCount").value(2));
        assertThat(jdbc.queryForObject("SELECT viewed_at FROM notice_view_records WHERE notice_id = ? AND source = 'MANUAL' AND user_id = ?", java.sql.Timestamp.class, id, student.getId())).isAfter(before);
        User second = users.save(new User("DEV", "viewer-" + tag, "다른학생"));
        mvc.perform(post(endpoint).header("Authorization", "Bearer " + jwt.issueStudent(second.getId())))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/notices/" + id)).andExpect(jsonPath("$.viewCount").value(3));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notice_view_records WHERE source = 'MANUAL' AND notice_id = ?", Integer.class, id)).isEqualTo(2);
    }

    @Test
    void concurrentCrawledViewsCountOnce() throws Exception {
        CrawlSource source = sources.save(new CrawlSource("views-" + tag, council, "https://example.com/list"));
        CrawledNotice notice = crawled.save(new CrawledNotice(source, "views", "조회 테스트", "https://example.com/views", false,
                Instant.now(), Instant.now()));
        String endpoint = "/api/crawled-notices/" + notice.getId() + "/view";
        for (int round = 1; round <= 2; round++) {
            if (round == 2) {
                jdbc.update("UPDATE notice_view_records SET viewed_at = now() - interval '24 hours' WHERE source = 'CRAWLED' AND notice_id = ?", notice.getId());
            }
            var executor = java.util.concurrent.Executors.newFixedThreadPool(8);
            var start = new java.util.concurrent.CountDownLatch(1);
            try {
                var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
                for (int i = 0; i < 8; i++) {
                    futures.add(executor.submit(() -> {
                        start.await();
                        mvc.perform(post(endpoint).header("Authorization", studentToken())).andExpect(status().isNoContent());
                        return null;
                    }));
                }
                start.countDown();
                for (var future : futures) future.get(30, java.util.concurrent.TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
            }
            assertThat(jdbc.queryForObject("SELECT view_count FROM crawled_notices WHERE id = ?", Integer.class, notice.getId())).isEqualTo(round);
            mvc.perform(post(endpoint).header("Authorization", studentToken())).andExpect(status().isNoContent());
            assertThat(jdbc.queryForObject("SELECT view_count FROM crawled_notices WHERE id = ?", Integer.class, notice.getId())).isEqualTo(round);
        }
    }

    @Test
    void unviewableNoticesCannotCreateViewRecords() throws Exception {
        long draft = createNotice(admin, Map.of("title", "비공개 " + tag, "publishMode", "DRAFT")).get("id").asLong();
        mvc.perform(post("/api/notices/" + draft + "/view").header("Authorization", studentToken()))
                .andExpect(status().isNotFound());
        long deleted = createNotice(admin, Map.of("title", "삭제됨 " + tag)).get("id").asLong();
        jdbc.update("UPDATE notices SET deleted_at = now() WHERE id = ?", deleted);
        mvc.perform(post("/api/notices/" + deleted + "/view").header("Authorization", studentToken()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/crawled-notices/9223372036854775807/view").header("Authorization", studentToken()))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notice_view_records WHERE user_id = ?", Integer.class, student.getId())).isZero();
    }

    @Test
    void schoolMediaIsReturnedInFeedAndDetailAndCanBeAbsent() throws Exception {
        CrawlSource source = sources.save(new CrawlSource("media-" + tag, council, "https://school.example/notice.do"));
        CrawledNotice notice = new CrawledNotice(source, "media", "첨부 공지 " + tag, "https://school.example/notice.do?mode=view&articleNo=1", false, Instant.now(), Instant.now());
        notice.applyContent("<p>본문</p>", "본문");
        notice.assignBoardMedia("https://school.example/poster.jpg", List.of(
                new com.moagwa.crawler.domain.CrawledAttachment("신청서.hwpx", "https://school.example/notice.do?mode=download&attachNo=1")));
        long id = crawled.saveAndFlush(notice).getId();
        mvc.perform(get("/api/feed").param("q", tag))
                .andExpect(jsonPath("$.items[0].thumbnail").value("https://school.example/poster.jpg"));
        mvc.perform(get("/api/crawled-notices/" + id))
                .andExpect(jsonPath("$.attachments[0].fileName").value("신청서.hwpx"))
                .andExpect(jsonPath("$.attachments[0].url").value("https://school.example/notice.do?mode=download&attachNo=1"));
        // Text-only school notices do not gain placeholder thumbnails or files.
        CrawledNotice plain = new CrawledNotice(source, "plain", "텍스트 공지 " + tag, "https://school.example/plain", false, Instant.now(), Instant.now());
        plain.applyContent("<p>본문</p>", "본문");
        plain.assignBoardMedia(null, List.of());
        long plainId = crawled.saveAndFlush(plain).getId();
        mvc.perform(get("/api/crawled-notices/" + plainId)).andExpect(jsonPath("$.attachments").isEmpty());
        mvc.perform(get("/api/feed").param("q", "텍스트 공지 " + tag)).andExpect(jsonPath("$.items[0].thumbnail").isEmpty());
    }

    @Test
    void imageCaptionsSurviveCreateEditAndDuplicate() throws Exception {
        Map<String, Object> group = captioned("CAROUSEL",
                List.of("/uploads/a.png", "/uploads/b.png"), List.of("첫 사진 설명", "두 번째 사진 설명"));
        long id = createNotice(admin, Map.of("title", "캡션 " + tag, "blocks", List.of(text("본문"), group)))
                .get("id").asLong();

        mvc.perform(get("/api/notices/" + id))
                .andExpect(jsonPath("$.blocks[1].captions[0]").value("첫 사진 설명"));

        // 사진 순서를 바꾸면 설명도 같은 순서로 저장됨
        Map<String, Object> reordered = captioned("STACK",
                List.of("/uploads/b.png", "/uploads/a.png"), List.of("두 번째 사진 설명", "첫 사진 설명"));
        Map<String, Object> edit = Map.of("title", "캡션 수정", "categoryIds", List.of(1),
                "blocks", List.of(text("본문"), reordered));
        mvc.perform(put("/api/admin/notices/" + id)
                        .header("Authorization", adminToken(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(edit)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocks[1].captions[0]").value("두 번째 사진 설명"));

        mvc.perform(post("/api/admin/notices/" + id + "/duplicate")
                        .header("Authorization", adminToken(admin)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.blocks[1].captions[1]").value("첫 사진 설명"));

        // 캡션 필드가 생기기 전에 저장된 JSON도 그대로 조회됨
        jdbc.update("UPDATE notices SET content_blocks = ? WHERE id = ?", """
                [{"type":"TEXT","text":"예전 글"},
                 {"type":"IMAGES","images":["/uploads/a.png"],"layout":"STACK"}]""", id);
        mvc.perform(get("/api/notices/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.blocks[1].images[0]").value("/uploads/a.png"));
    }

    @Test
    void captionsRejectMismatchedCountsNullItemsAndExcessLength() throws Exception {
        List<String> oneImage = List.of("/uploads/a.png");
        assertBlocksRejected(List.of(text("본문"), captioned("STACK", oneImage, List.of("하나", "둘"))));
        assertBlocksRejected(List.of(text("본문"), captioned("STACK", oneImage, List.of("x".repeat(301)))));
        assertBlocksRejected(List.of(text("본문"), captioned("STACK", oneImage, Collections.singletonList(null))));

        // 설명 없이 보내면 사진마다 빈 설명으로 채워짐
        long id = createNotice(admin, Map.of("title", "설명 없음",
                "blocks", List.of(text("본문"), images("STACK", "/uploads/a.png"))))
                .get("id").asLong();
        mvc.perform(get("/api/notices/" + id))
                .andExpect(jsonPath("$.blocks[1].captions[0]").value(""));
    }

    private static Map<String, Object> captioned(String layout, List<String> urls, List<String> captions) {
        return Map.of("type", "IMAGES", "layout", layout, "images", urls, "captions", captions);
    }

    @Test
    void operatorNoticesReachEveryStudentRegardlessOfInterests() throws Exception {
        Organization operatorOrg = operatorOrganization();
        Admin operator = admins.save(new Admin("op-" + tag, encoder.encode("pw-1234"), "운영자", operatorOrg));

        // 운영자 단체를 관심 단체에 넣지 않고 저장해도 운영자 공지는 받음
        mvc.perform(put("/api/me/profile").header("Authorization", studentToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("organizationIds", List.of(council.getId())))))
                .andExpect(status().isOk());
        long id = createNotice(operator, Map.of("title", "운영공지 " + tag)).get("id").asLong();
        try {
            mvc.perform(get("/api/notices/" + id)).andExpect(jsonPath("$.organization.name").value("운영자"));
            String forMe = mvc.perform(get("/api/feed/for-me").header("Authorization", studentToken()))
                    .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            assertThat(forMe).contains("운영공지 " + tag);
            mvc.perform(get("/api/me/notifications").header("Authorization", studentToken()))
                    .andExpect(jsonPath("$.items[0].noticeId").value(id));
        } finally {
            // 다른 테스트의 맞춤 피드에 섞이지 않도록 정리
            jdbc.update("UPDATE notices SET deleted_at = now() WHERE id = ?", id);
        }
    }

    @Test
    void organizationsListMarksOperatorAsRequired() throws Exception {
        mvc.perform(get("/api/organizations").param("q", "운영자"))
                .andExpect(jsonPath("$[0].name").value("운영자"))
                .andExpect(jsonPath("$[0].required").value(true));
        mvc.perform(get("/api/organizations").param("q", council.getName()))
                .andExpect(jsonPath("$[0].required").value(false));
    }

    private Organization operatorOrganization() {
        return organizations.findByName(Organization.OPERATOR_NAME).orElseThrow();
    }
}
