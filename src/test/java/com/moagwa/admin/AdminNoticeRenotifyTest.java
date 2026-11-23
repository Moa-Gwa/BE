package com.moagwa.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moagwa.auth.JwtService;
import com.moagwa.organization.Organization;
import com.moagwa.organization.OrganizationRepository;
import com.moagwa.organization.OrganizationType;
import com.moagwa.user.User;
import com.moagwa.user.UserRepository;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
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

/** 관리자가 게시 중인 공지의 알림을 같은 대상에게 다시 보내는지 검증함 */
@SpringBootTest
@AutoConfigureMockMvc
class AdminNoticeRenotifyTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtService jwt;
    @Autowired PasswordEncoder encoder;
    @Autowired OrganizationRepository organizations;
    @Autowired AdminRepository admins;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;

    private Admin admin;
    private Admin otherAdmin;
    private User student;
    private String tag;

    @BeforeEach
    void setUp() {
        tag = UUID.randomUUID().toString().substring(0, 8);
        Organization council = organizations.save(new Organization("학생회-" + tag, OrganizationType.STUDENT_GOVERNMENT));
        Organization other = organizations.save(new Organization("다른학생회-" + tag, OrganizationType.STUDENT_GOVERNMENT));
        admin = admins.save(new Admin("ra-" + tag, encoder.encode("pw-1234"), "관리자", council));
        otherAdmin = admins.save(new Admin("rb-" + tag, encoder.encode("pw-1234"), "다른관리자", other));
        student = users.save(new User("DEV", "rs-" + tag, "학생" + tag));
        jdbc.update("INSERT INTO user_interests (user_id, kind, target_id) VALUES (?, 'ORGANIZATION', ?)",
                student.getId(), council.getId());
    }

    private String token(Admin a) {
        return "Bearer " + jwt.issueAdmin(a.getId(), a.getOrganization().getId());
    }

    private long createNotice(String publishMode) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("title", "재알림 " + tag, "description", "본문입니다",
                "categoryIds", List.of(1), "publishMode", publishMode));
        String res = mvc.perform(post("/api/admin/notices").header("Authorization", token(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(res).get("id").asLong();
    }

    private int notificationsOf(long noticeId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE user_id = ? AND notice_id = ? AND source = 'MANUAL'",
                Integer.class, student.getId(), noticeId);
    }

    @Test
    void resendsToTheSameRecipientsAsTheFirstPublish() throws Exception {
        long id = createNotice("NOW");
        assertThat(notificationsOf(id)).isEqualTo(1);

        mvc.perform(post("/api/admin/notices/" + id + "/renotify").header("Authorization", token(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.recipients").isNumber());
        assertThat(notificationsOf(id)).isEqualTo(2);
    }

    @Test
    void respectsTheStudentsNotificationSetting() throws Exception {
        long id = createNotice("NOW");
        mvc.perform(put("/api/me/notification-settings").header("Authorization", "Bearer " + jwt.issueStudent(student.getId()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"newNotice\":false,\"eventStart\":true,\"deadlineSoon\":true,\"urgent\":true}"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/admin/notices/" + id + "/renotify").header("Authorization", token(admin)))
                .andExpect(status().isOk());
        assertThat(notificationsOf(id)).isEqualTo(1);
    }

    @Test
    void onlyPublishedNoticesOfTheOwnOrganization() throws Exception {
        long draft = createNotice("DRAFT");
        mvc.perform(post("/api/admin/notices/" + draft + "/renotify").header("Authorization", token(admin)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("게시 중인 공지만 알림을 다시 보낼 수 있습니다."));

        long published = createNotice("NOW");
        mvc.perform(post("/api/admin/notices/" + published + "/close").header("Authorization", token(admin)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/admin/notices/" + published + "/renotify").header("Authorization", token(admin)))
                .andExpect(status().isBadRequest());

        long another = createNotice("NOW");
        mvc.perform(post("/api/admin/notices/" + another + "/renotify").header("Authorization", token(otherAdmin)))
                .andExpect(status().isNotFound());
        assertThat(notificationsOf(another)).isEqualTo(1);
    }
}
