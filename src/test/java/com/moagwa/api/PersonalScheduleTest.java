package com.moagwa.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moagwa.auth.JwtService;
import com.moagwa.user.User;
import com.moagwa.user.UserRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PersonalScheduleTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper json;

    String student() {
        String tag = UUID.randomUUID().toString();
        return "Bearer " + jwt.issueStudent(users.save(new User("DEV", tag, tag)).getId());
    }

    final String input = """
            {"title":"팀 회의","at":"2026-12-01T00:00:00Z","location":"도서관","memo":"자료 준비"}
            """;

    @Test
    void ownScheduleIsPrivateAndCanBeEditedAndDeleted() throws Exception {
        String owner = student(), other = student();
        var created = mvc.perform(post("/api/me/schedules").header("Authorization", owner)
                .contentType(MediaType.APPLICATION_JSON).content(input))
                .andExpect(status().isCreated()).andReturn();
        long id = json.readTree(created.getResponse().getContentAsString()).get("id").asLong();
        String query = "/api/schedule?from=2026-12-01&to=2026-12-01";
        mvc.perform(get(query).header("Authorization", owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.source == 'PERSONAL')].title", hasItem("팀 회의")))
                .andExpect(jsonPath("$[?(@.source == 'PERSONAL')].memo", hasItem("자료 준비")));
        mvc.perform(get(query).header("Authorization", other)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.source == 'PERSONAL')]", hasSize(0)));
        mvc.perform(get(query)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.source == 'PERSONAL')]", hasSize(0)));
        mvc.perform(put("/api/me/schedules/" + id).header("Authorization", other)
                .contentType(MediaType.APPLICATION_JSON).content(input)).andExpect(status().isNotFound());
        mvc.perform(delete("/api/me/schedules/" + id).header("Authorization", other))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/me/schedules/" + id).header("Authorization", owner)
                .contentType(MediaType.APPLICATION_JSON).content(input.replace("팀 회의", "팀 발표")))
                .andExpect(status().isNoContent());
        mvc.perform(get(query).header("Authorization", owner))
                .andExpect(jsonPath("$[?(@.source == 'PERSONAL')].title", hasItem("팀 발표")));
        mvc.perform(delete("/api/me/schedules/" + id).header("Authorization", owner))
                .andExpect(status().isNoContent());
        mvc.perform(get(query).header("Authorization", owner))
                .andExpect(jsonPath("$[?(@.source == 'PERSONAL')]", hasSize(0)));
    }

    @Test
    void rejectsMissingFieldsAndAnonymousWrites() throws Exception {
        mvc.perform(post("/api/me/schedules").contentType(MediaType.APPLICATION_JSON).content(input))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/me/schedules").header("Authorization", student())
                .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\" \"}"))
                .andExpect(status().isBadRequest());
    }
}
