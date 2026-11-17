package com.moagwa.correction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.moagwa.admin.Admin;
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
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "moagwa.operator-login-ids=ops-a, " + DateCorrectionTest.CONFIGURED_OPERATOR)
@AutoConfigureMockMvc
class DateCorrectionTest {

    static final String CONFIGURED_OPERATOR = "ops-configured";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;
    @Autowired UserRepository users;
    @Autowired AdminRepository admins;
    @Autowired OrganizationRepository organizations;
    @Autowired CrawlSourceRepository sources;
    @Autowired CrawledNoticeRepository crawled;
    @Autowired PasswordEncoder encoder;

    private long noticeId;
    private long sourceId;
    private long orgId;
    private long userId;
    private long adminId;
    private String student;
    private String operator;
    private String orgAdmin;

    @BeforeEach
    void setUp() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        Organization dept = organizations.save(new Organization("학과-" + tag, OrganizationType.DEPARTMENT));
        CrawlSource source = sources.save(new CrawlSource("src-" + tag, dept, "https://example.com/list"));
        Instant published = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        CrawledNotice c = new CrawledNotice(source, tag, "신청 안내 " + tag, "https://example.com/" + tag, false,
                published, published);
        c.applyContent("<p>신청 마감 12월 4일</p>", "신청 마감 12월 4일");
        noticeId = crawled.save(c).getId();
        sourceId = source.getId();
        orgId = dept.getId();
        jdbc.update("UPDATE crawled_notices SET deadline_at = now() + interval '20 days', extracted_at = now() WHERE id = ?",
                noticeId);

        User user = users.save(new User("DEV", "u-" + tag, "u-" + tag));
        userId = user.getId();
        student = "Bearer " + jwt.issueStudent(user.getId());
        Admin admin = admins.save(new Admin("op-" + tag, "hash", "운영자", dept));
        adminId = admin.getId();
        operator = "Bearer " + jwt.issueAdmin(admin.getId(), dept.getId(), true);
        orgAdmin = "Bearer " + jwt.issueAdmin(admin.getId(), dept.getId());
    }

    /** 다른 테스트가 전체 공지 수를 세므로 만든 데이터는 지움 */
    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM crawled_notices WHERE id = ?", noticeId); // 교정 요청은 함께 삭제됨
        jdbc.update("DELETE FROM crawl_sources WHERE id = ?", sourceId);
        jdbc.update("DELETE FROM admins WHERE id = ?", adminId);
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
        jdbc.update("DELETE FROM organizations WHERE id = ?", orgId);
    }

    @Test
    void studentRequestIsAppliedByOperator() throws Exception {
        long id = request("{\"field\":\"DEADLINE\",\"suggestedStart\":\"2026-12-04\",\"memo\":\"제목에 12/4까지\"}");

        // 같은 항목 중복 요청은 막음
        mvc.perform(post(url()).header("Authorization", student).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"field\":\"DEADLINE\",\"suggestedStart\":\"2026-12-05\"}"))
                .andExpect(status().isConflict());
        // 단체 관리자는 운영자 화면 접근 불가
        mvc.perform(get("/api/admin/date-corrections").header("Authorization", orgAdmin))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/date-corrections").header("Authorization", operator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + id + ")].notice.contentText").value("신청 마감 12월 4일"));

        mvc.perform(post("/api/admin/date-corrections/" + id + "/apply").header("Authorization", operator)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"deadlineAt\":\"2026-12-04T08:00:00Z\"}"))
                .andExpect(status().isNoContent());

        assertThat(deadline()).isEqualTo(Instant.parse("2026-12-04T08:00:00Z"));
        assertThat(jdbc.queryForObject("SELECT status FROM notice_date_corrections WHERE id = ?", String.class, id))
                .isEqualTo("APPLIED");
        mvc.perform(post("/api/admin/date-corrections/" + id + "/reject").header("Authorization", operator))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT deadline_locked AND NOT event_locked FROM crawled_notices WHERE id = ?", Boolean.class, noticeId))
                .isTrue();
    }

    @Test
    void rejectedRequestLeavesTheNoticeAlone() throws Exception {
        Instant before = deadline();
        long id = request("{\"field\":\"EVENT\",\"memo\":\"행사 일정이 없는 공지예요\"}");

        mvc.perform(post("/api/admin/date-corrections/" + id + "/reject").header("Authorization", operator))
                .andExpect(status().isNoContent());

        assertThat(deadline()).isEqualTo(before);
        // 반려 후에는 같은 항목을 다시 요청할 수 있음
        request("{\"field\":\"EVENT\",\"suggestedStart\":\"2026-12-10\"}");
    }

    @Test
    void adminsListedInConfigLogInAsOperators() throws Exception {
        Organization org = organizations.findById(orgId).orElseThrow();
        Admin configured = admins.save(new Admin(CONFIGURED_OPERATOR, encoder.encode("pw-1234"), "운영자", org));
        try {
            mvc.perform(post("/api/admin/auth/login").header("X-Requested-With", "fetch")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"loginId\":\"" + CONFIGURED_OPERATOR + "\",\"password\":\"pw-1234\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.operator").value(true))
                    .andExpect(jsonPath("$.organization.name").value(Organization.OPERATOR_NAME));
            // 마이그레이션 이후 설정으로 운영자가 된 계정도 운영자 단체로 옮겨져 공지가 모든 학생에게 감
            assertThat(jdbc.queryForObject("SELECT o.is_required FROM admins a JOIN organizations o"
                    + " ON o.id = a.organization_id WHERE a.id = ?", Boolean.class, configured.getId())).isTrue();
            mvc.perform(post("/api/admin/auth/login").header("X-Requested-With", "fetch")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"loginId\":\"op-x\",\"password\":\"x\"}"))
                    .andExpect(status().isUnauthorized());
        } finally {
            admins.delete(configured);
        }
    }

    @Test
    void invalidRequestsAreRejected() throws Exception {
        mvc.perform(post(url()).contentType(MediaType.APPLICATION_JSON).content("{\"field\":\"DEADLINE\",\"memo\":\"x\"}"))
                .andExpect(status().isUnauthorized());
        for (String body : new String[] {
                "{\"field\":\"DEADLINE\"}", // 날짜도 메모도 없음
                "{\"field\":\"EVENT\",\"suggestedStart\":\"2026-12-10\",\"suggestedEnd\":\"2026-12-09\"}",
                "{\"field\":\"DEADLINE\",\"suggestedStart\":\"2026-12-10\",\"suggestedEnd\":\"2026-12-11\"}",
                "{\"field\":\"CATEGORY\",\"memo\":\"x\"}"}) {
            mvc.perform(post(url()).header("Authorization", student).contentType(MediaType.APPLICATION_JSON)
                    .content(body)).andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/crawled-notices/0/date-corrections").header("Authorization", student)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"field\":\"DEADLINE\",\"memo\":\"x\"}"))
                .andExpect(status().isNotFound());
    }

    private long request(String body) throws Exception {
        String res = mvc.perform(post(url()).header("Authorization", student)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return Long.parseLong(res.replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    private String url() {
        return "/api/crawled-notices/" + noticeId + "/date-corrections";
    }

    @Test
    void resolvedRequestsAreListedNewestFirst() throws Exception {
        long earlier = request("{\"field\":\"DEADLINE\",\"suggestedStart\":\"2026-12-04\"}");
        long later = request("{\"field\":\"EVENT\",\"suggestedStart\":\"2026-12-10\"}");
        // 먼저 들어온 요청을 먼저 처리하고, 나중 요청을 방금 처리한 경우
        jdbc.update("UPDATE notice_date_corrections SET status = 'REJECTED', resolved_at = now() - interval '1 hour' WHERE id = ?", earlier);
        jdbc.update("UPDATE notice_date_corrections SET status = 'REJECTED', resolved_at = now() WHERE id = ?", later);

        String body = mvc.perform(get("/api/admin/date-corrections").param("status", "REJECTED")
                        .header("Authorization", operator))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Long> mine = com.jayway.jsonpath.JsonPath.<List<Number>>read(body, "$[*].id").stream()
                .map(Number::longValue).filter(id -> id == earlier || id == later).toList();

        assertThat(mine).containsExactly(later, earlier); // 방금 처리한 요청이 먼저
    }

    private Instant deadline() {
        return jdbc.queryForObject("SELECT deadline_at FROM crawled_notices WHERE id = ?", java.sql.Timestamp.class,
                noticeId).toInstant();
    }
}
