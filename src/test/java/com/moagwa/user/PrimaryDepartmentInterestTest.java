package com.moagwa.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moagwa.auth.JwtService;
import com.moagwa.organization.Organization;
import com.moagwa.organization.OrganizationRepository;
import com.moagwa.organization.OrganizationType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
import org.springframework.test.web.servlet.MockMvc;

/** 주전공을 고르거나 바꿀 때 관심 학과와 학과 학생회를 자동으로 맞추는 동작 */
@SpringBootTest
@AutoConfigureMockMvc
class PrimaryDepartmentInterestTest {

    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired OrganizationRepository organizations;

    private String token;

    @BeforeEach
    void setUp() {
        // 학과 학생회는 운영 중 인스타 수집, 관리자 신청 승인으로 생기므로 테스트에서 만듦
        for (String name : List.of("총학생회", "철학과 학생회", "영어영문학부 학생회", "국사학과 학생회", "경영학과 학생회")) {
            organizations.findByName(name).orElseGet(() -> organizations.save(new Organization(name, OrganizationType.STUDENT_GOVERNMENT)));
        }
        User me = users.save(new User("DEV", "dept-" + UUID.randomUUID(), "학생"));
        token = "Bearer " + jwt.issueStudent(me.getId());
    }

    private long id(String name) {
        return jdbc.queryForObject("SELECT id FROM organizations WHERE name = ?", Long.class, name);
    }

    private JsonNode save(Long primary, List<Long> departments, List<Long> organizations) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("nickname", "학생");
        body.put("primaryDepartmentId", primary);
        body.put("departmentIds", departments);
        body.put("organizationIds", organizations);
        body.put("categoryIds", List.of());
        String res = mvc.perform(put("/api/me/profile").header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(res);
    }

    private static List<Long> ids(JsonNode profile, String field) {
        List<Long> out = new ArrayList<>();
        profile.get(field).forEach(n -> out.add(n.asLong()));
        return out;
    }

    @Test
    void choosingAndChangingPrimaryDepartmentSyncsDepartmentAndCouncil() throws Exception {
        long philosophy = id("철학과"), philosophyCouncil = id("철학과 학생회");
        long english = id("영어영문학과"), englishCouncil = id("영어영문학부 학생회");
        long other = id("총학생회");

        JsonNode first = save(philosophy, List.of(), List.of(other));
        assertThat(ids(first, "departmentIds")).containsExactly(philosophy);
        assertThat(ids(first, "organizationIds")).containsExactlyInAnyOrder(other, philosophyCouncil);

        // 같은 주전공으로 다시 저장하면 학생이 해제한 학생회는 다시 붙지 않음
        JsonNode unchecked = save(philosophy, List.of(philosophy), List.of(other));
        assertThat(ids(unchecked, "organizationIds")).containsExactly(other);

        // 주전공을 바꾸면 예전 학과와 학생회는 빠지고 새 학과와 학생회가 들어감
        save(philosophy, List.of(philosophy), List.of(other, philosophyCouncil));
        JsonNode changed = save(english, List.of(philosophy), List.of(other, philosophyCouncil));
        assertThat(ids(changed, "departmentIds")).containsExactly(english);
        assertThat(ids(changed, "organizationIds")).containsExactlyInAnyOrder(other, englishCouncil);
    }

    @Test
    void departmentWithoutCouncilAddsOnlyTheDepartment() throws Exception {
        long noCouncil = id("교직과");

        JsonNode profile = save(noCouncil, List.of(), List.of());

        assertThat(ids(profile, "departmentIds")).containsExactly(noCouncil);
        assertThat(ids(profile, "organizationIds")).isEmpty();
    }

    @Test
    void departmentListShowsLinkedCouncilAndRenamedDepartment() throws Exception {
        String body = mvc.perform(get("/api/organizations").param("type", "DEPARTMENT"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode list = json.readTree(body);
        Map<String, JsonNode> byName = new HashMap<>();
        list.forEach(o -> byName.put(o.get("name").asText(), o));

        assertThat(byName).containsKey("국사학과").doesNotContainKey("사학과");
        assertThat(byName.get("국사학과").get("studentGovernmentId").asLong()).isEqualTo(id("국사학과 학생회"));
        assertThat(byName.get("경영학부").get("studentGovernmentId").asLong()).isEqualTo(id("경영학과 학생회"));
    }
}
