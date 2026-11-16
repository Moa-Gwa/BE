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
    @org.springframework.boot.test.mock.mockito.MockBean com.moagwa.feed.AcademicCalendar academicCalendar;
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper json;

    String student() {
        String tag = UUID.randomUUID().toString();
        return "Bearer " + jwt.issueStudent(users.save(new User("DEV", tag, tag)).getId());
    }

    @Test
    void officialAcademicDatesAreIncludedWithoutSavingAndDoNotCreatePersonalSchedules() throws Exception {
        var from = java.time.LocalDate.of(2026, 12, 1);
        var entry = new com.moagwa.feed.ScheduleEntry("ACADEMIC", java.time.Instant.parse("2026-11-30T15:00:00Z"),
                "ACADEMIC", 1L, "성심교정 기말고사", "성심교정 학사일정", null, false, null,
                com.moagwa.feed.AcademicCalendar.url(2026), null);
        org.mockito.Mockito.when(academicCalendar.entries(from, from.plusDays(1))).thenReturn(java.util.List.of(entry));
        for (String auth : new String[]{"", student()}) {
            mvc.perform(get("/api/schedule?from=2026-12-01&to=2026-12-01").header("Authorization", auth))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[?(@.source == 'ACADEMIC')].title", hasItem("성심교정 기말고사")))
                    .andExpect(jsonPath("$[?(@.source == 'ACADEMIC')].bookmarked", hasItem(false)))
                    .andExpect(jsonPath("$[?(@.source == 'PERSONAL')]", hasSize(0)));
        }
    }

    @Test
    void periodsAreReturnedWhenTheyOverlapTheQueryAndExclusiveEndIsRespected() throws Exception {
        String owner = student(), other = student();
        String period = """
                {"title":"여러 날 회의","at":"2026-11-30T00:00:00Z","endAt":"2026-12-02T15:00:00Z"}
                """;
        var response = mvc.perform(post("/api/me/schedules").header("Authorization", owner)
                .contentType(MediaType.APPLICATION_JSON).content(period)).andExpect(status().isCreated()).andReturn();
        long id = json.readTree(response.getResponse().getContentAsString()).get("id").asLong();
        String query = "/api/schedule?from=2026-12-01&to=2026-12-01";
        mvc.perform(get(query).header("Authorization", owner)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.source == 'PERSONAL')].endAt", hasItem("2026-12-02T15:00:00Z")));
        mvc.perform(get(query).header("Authorization", other)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.source == 'PERSONAL')]", hasSize(0)));
        mvc.perform(get("/api/schedule?from=2026-12-03&to=2026-12-03").header("Authorization", owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.source == 'PERSONAL')]", hasSize(0)));
        mvc.perform(put("/api/me/schedules/" + id).header("Authorization", owner).contentType(MediaType.APPLICATION_JSON)
                .content(period.replace("2026-12-02T15:00:00Z", "2026-11-29T15:00:00Z"))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/me/schedules").header("Authorization", owner).contentType(MediaType.APPLICATION_JSON)
                .content(period.replace("2026-12-02T15:00:00Z", "2026-11-30T00:00:00Z"))).andExpect(status().isBadRequest());
        mvc.perform(put("/api/me/schedules/" + id).header("Authorization", owner).contentType(MediaType.APPLICATION_JSON)
                .content(period.replace("2026-12-02T15:00:00Z", "2026-12-04T15:00:00Z"))).andExpect(status().isNoContent());
        mvc.perform(get("/api/schedule?from=2026-12-04&to=2026-12-04").header("Authorization", owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.source == 'PERSONAL')].title", hasItem("여러 날 회의")));
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
    void locationAndMemoAreTrimmedAndBlankIsStoredAsNull() throws Exception {
        String owner = student();
        mvc.perform(post("/api/me/schedules").header("Authorization", owner).contentType(MediaType.APPLICATION_JSON)
                .content(input.replace("\"도서관\"", "\"  도서관 \"").replace("\"자료 준비\"", "\"   \"")))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/schedule?from=2026-12-01&to=2026-12-01").header("Authorization", owner))
                .andExpect(jsonPath("$[?(@.source == 'PERSONAL')].location", hasItem("도서관")))
                .andExpect(jsonPath("$[?(@.source == 'PERSONAL')].memo", hasItem(nullValue())));
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
