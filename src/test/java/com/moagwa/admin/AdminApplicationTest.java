package com.moagwa.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moagwa.auth.JwtService;
import com.moagwa.organization.Organization;
import com.moagwa.organization.OrganizationRepository;
import com.moagwa.organization.OrganizationType;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
class AdminApplicationTest {

    private static final String PASSWORD = "council-pass-1234";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtService jwt;
    @Autowired PasswordEncoder encoder;
    @Autowired OrganizationRepository organizations;
    @Autowired AdminRepository admins;
    @Autowired JdbcTemplate jdbc;

    private String tag;
    private String operator;
    private String orgAdmin;

    @BeforeEach
    void setUp() {
        tag = UUID.randomUUID().toString().substring(0, 8);
        Organization ops = organizations.save(new Organization("운영-" + tag, OrganizationType.STUDENT_GOVERNMENT));
        Admin a = admins.save(new Admin("ops-" + tag, encoder.encode("pw-1234"), "운영자", ops));
        operator = "Bearer " + jwt.issueAdmin(a.getId(), ops.getId(), true);
        orgAdmin = "Bearer " + jwt.issueAdmin(a.getId(), ops.getId(), false);
    }

    @Test
    void approvedApplicantSetsPasswordOnceAndThenLogsInWithChosenLoginId() throws Exception {
        String submittedEmail = "Council-" + tag + "@Catholic.AC.kr";
        long id = submit(submittedEmail);
        String email = "council-" + tag + "@catholic.ac.kr";
        String loginId = "council-" + tag;

        mvc.perform(get("/api/admin/applications").header("Authorization", operator))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + id + ")].email").value(email))
                .andExpect(jsonPath("$[?(@.id == " + id + ")].loginId").value(loginId));

        JsonNode approved = body(mvc.perform(post("/api/admin/applications/" + id + "/approve")
                .header("Authorization", operator)).andExpect(status().isOk()));
        assertThat(approved.get("mailSent").asBoolean()).isFalse();
        String token = approved.get("setupLink").asText().replaceAll(".*token=", "");

        // 비밀번호 설정 전에는 비활성 계정이라 로그인 불가
        assertThat(activated(loginId)).isFalse();
        login(loginId, PASSWORD).andExpect(status().isUnauthorized());

        mvc.perform(post("/api/admin/auth/setup/validate").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("token", token))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.valid").value(true));

        setup(token, "short").andExpect(status().isBadRequest());
        setup(token, PASSWORD).andExpect(status().isNoContent());
        setup(token, "another-pass-1234").andExpect(status().isBadRequest());

        assertThat(activated(loginId)).isTrue();
        login(loginId, PASSWORD).andExpect(status().isOk());
        // 신청 메일은 더 이상 로그인 아이디가 아님
        assertThat(admins.findByLoginId(email)).isEmpty();
        assertThat(jdbc.queryForObject("""
                SELECT o.is_verified FROM admins a JOIN organizations o ON o.id = a.organization_id
                 WHERE a.login_id = ?""", Boolean.class, loginId)).isTrue();
    }

    @Test
    void operatorCanFixOrganizationNameWhenApproving() throws Exception {
        long id = submit("rename-" + tag + "@catholic.ac.kr");
        String fixed = "철학과 학생회 " + tag;

        mvc.perform(post("/api/admin/applications/" + id + "/approve").header("Authorization", operator)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"organizationName\":\"  " + fixed + " \"}"))
                .andExpect(status().isOk());

        Long orgId = jdbc.queryForObject("SELECT organization_id FROM admin_applications WHERE id = ?", Long.class, id);
        assertThat(organizations.findById(orgId).orElseThrow().getName()).isEqualTo(fixed);
    }

    @Test
    void expiredSetupLinkCannotBeUsedUntilOperatorResendsIt() throws Exception {
        long id = submit("late-" + tag + "@catholic.ac.kr");
        String token = body(mvc.perform(post("/api/admin/applications/" + id + "/approve")
                .header("Authorization", operator))).get("setupLink").asText().replaceAll(".*token=", "");
        jdbc.update("UPDATE admin_applications SET setup_expires_at = now() - interval '1 minute' WHERE id = ?", id);

        mvc.perform(post("/api/admin/auth/setup/validate").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("token", token))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false));
        setup(token, PASSWORD).andExpect(status().isBadRequest());
        login("late-" + tag, PASSWORD).andExpect(status().isUnauthorized());

        // 운영자가 링크를 다시 보내면 새 링크로 설정 가능
        String newToken = body(mvc.perform(post("/api/admin/applications/" + id + "/resend-setup")
                .header("Authorization", operator)).andExpect(status().isOk()))
                .get("setupLink").asText().replaceAll(".*token=", "");
        setup(newToken, PASSWORD).andExpect(status().isNoContent());
        login("late-" + tag, PASSWORD).andExpect(status().isOk());

        // 비밀번호를 정한 계정과 승인 전 신청에는 다시 보낼 수 없음
        mvc.perform(post("/api/admin/applications/" + id + "/resend-setup").header("Authorization", operator))
                .andExpect(status().isConflict());
        long pending = submit("wait-" + tag + "@catholic.ac.kr");
        mvc.perform(post("/api/admin/applications/" + pending + "/resend-setup").header("Authorization", operator))
                .andExpect(status().isConflict());
    }

    @Test
    void processedApplicationCannotBeApprovedOrRejectedAgain() throws Exception {
        long id = submit("rej-" + tag + "@catholic.ac.kr");

        // 메일 설정이 없는 환경이라 안내 메일은 못 보내지만 반려는 처리됨
        mvc.perform(post("/api/admin/applications/" + id + "/reject").header("Authorization", operator)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("reason", "확인 불가"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mailSent").value(false));
        assertThat(jdbc.queryForObject("SELECT status FROM admin_applications WHERE id = ?", String.class, id))
                .isEqualTo("REJECTED");

        mvc.perform(post("/api/admin/applications/" + id + "/reject").header("Authorization", operator))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/admin/applications/" + id + "/approve").header("Authorization", operator))
                .andExpect(status().isConflict());

        mvc.perform(post("/api/admin/applications/0/approve").header("Authorization", operator))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/admin/applications/0/reject").header("Authorization", operator))
                .andExpect(status().isNotFound());
    }

    @Test
    void loginIdInUseIsRefusedAtSubmitAndApproval() throws Exception {
        // 기존 관리자 아이디(대소문자 무시)
        admins.save(new Admin("Taken-" + tag, "hash", "기존", organizations.findAll().get(0)));
        submitRequest(form("taken-" + tag + "@catholic.ac.kr", "taken-" + tag))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("이미 사용 중인 아이디입니다."));

        // 대기 중인 신청의 아이디
        long pending = submit("first-" + tag + "@catholic.ac.kr");
        submitRequest(form("second-" + tag + "@catholic.ac.kr", "first-" + tag))
                .andExpect(status().isConflict());

        // 신청 뒤 같은 아이디의 관리자 계정이 생기면 승인 불가
        admins.save(new Admin("FIRST-" + tag, "hash", "기존", organizations.findAll().get(0)));
        mvc.perform(post("/api/admin/applications/" + pending + "/approve").header("Authorization", operator))
                .andExpect(status().isConflict());
    }

    @Test
    void invalidFieldsAreRejectedWithMessagePerField() throws Exception {
        Map<String, String> bad = form("council-" + tag + "@gmail.com", "Bad Id!");
        bad.put("phone", "02-12");
        bad.put("channelUrl", "instagram.com/council");

        submitRequest(bad)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.email").value("학교 메일(@catholic.ac.kr)로 신청해 주세요."))
                .andExpect(jsonPath("$.errors.phone").isNotEmpty())
                .andExpect(jsonPath("$.errors.channelUrl").isNotEmpty())
                .andExpect(jsonPath("$.errors.loginId").isNotEmpty());

        Map<String, String> fakeDomain = form("council-" + tag + "@catholic.ac.kr.evil.com", "council-" + tag);
        submitRequest(fakeDomain).andExpect(status().isBadRequest());
        submitRequest(form("council-" + tag + "@catholic.ac.kr", "ab")).andExpect(status().isBadRequest());
        Map<String, String> ftp = form("council-" + tag + "@catholic.ac.kr", "council-" + tag);
        ftp.put("channelUrl", "ftp://example.com/council");
        submitRequest(ftp).andExpect(status().isBadRequest());
    }

    @Test
    void sameEmailCannotSubmitAgainUnlessRejected() throws Exception {
        String email = "again-" + tag + "@catholic.ac.kr";
        long first = submit(email);

        submitRequest(form("AGAIN-" + tag + "@catholic.ac.kr", "other-" + tag))
                .andExpect(status().isConflict());

        mvc.perform(post("/api/admin/applications/" + first + "/reject").header("Authorization", operator))
                .andExpect(status().isOk());
        long second = submit(email);

        // 승인된 뒤에도 같은 메일로는 다시 신청할 수 없음
        mvc.perform(post("/api/admin/applications/" + second + "/approve").header("Authorization", operator))
                .andExpect(status().isOk());
        submitRequest(form(email, "other-" + tag)).andExpect(status().isConflict());
    }

    @Test
    void onlyOperatorsReviewApplications() throws Exception {
        long id = submit("guard-" + tag + "@catholic.ac.kr");

        mvc.perform(get("/api/admin/applications").header("Authorization", orgAdmin))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/applications/" + id + "/approve").header("Authorization", orgAdmin))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/applications/" + id + "/approve"))
                .andExpect(status().isUnauthorized());
    }

    private Map<String, String> form(String email, String loginId) {
        return new HashMap<>(Map.of(
                "organizationName", "총학생회-" + tag, "university", "가톨릭대학교", "department", "총학생회",
                "contactName", "담당자", "email", email, "phone", "010-0000-0000",
                "channelUrl", "https://example.com/council", "reason", "공지 게시용 계정 신청",
                "loginId", loginId));
    }

    private ResultActions submitRequest(Map<String, String> form) throws Exception {
        return mvc.perform(post("/api/admin-applications").contentType(MediaType.APPLICATION_JSON)
                .content(json(form)));
    }

    /** 메일 앞부분을 소문자로 바꿔 로그인 아이디로 씀 */
    private long submit(String email) throws Exception {
        String loginId = email.substring(0, email.indexOf('@')).toLowerCase(Locale.ROOT);
        return body(submitRequest(form(email, loginId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING")))
                .get("id").asLong();
    }

    private boolean activated(String loginId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT activated FROM admins WHERE login_id = ?", Boolean.class, loginId));
    }

    private ResultActions setup(String token, String password) throws Exception {
        return mvc.perform(post("/api/admin/auth/setup").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("token", token, "password", password))));
    }

    private ResultActions login(String loginId, String password) throws Exception {
        return mvc.perform(post("/api/admin/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("loginId", loginId, "password", password))));
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String json(Object value) throws Exception {
        return json.writeValueAsString(value);
    }
}
