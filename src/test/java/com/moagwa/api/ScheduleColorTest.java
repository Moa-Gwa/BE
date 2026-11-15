package com.moagwa.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moagwa.auth.JwtService;
import com.moagwa.feed.AcademicCalendar;
import com.moagwa.feed.ScheduleEntry;
import com.moagwa.user.User;
import com.moagwa.user.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.mockito.Mockito.when;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ScheduleColorTest {
    @MockBean AcademicCalendar academic;
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    long user() { return users.save(new User("DEV", UUID.randomUUID().toString(), "색상 사용자")).getId(); }
    String auth(long user) { return "Bearer " + jwt.issueStudent(user); }
    String query = "/api/schedule?from=2026-12-01&to=2026-12-01";
    String payload = "{\"title\":\"회의\",\"at\":\"2026-12-01T00:00:00Z\",\"endAt\":\"2026-12-02T00:00:00Z\",\"color\":\"#147D56\"}";

    @Test void personalColorSavesWithTheScheduleCanChangeAndIsRemovedOnDelete() throws Exception {
        long user = user();
        var created = mvc.perform(post("/api/me/schedules").header("Authorization", auth(user))
                .contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isCreated()).andReturn();
        long id = json.readTree(created.getResponse().getContentAsString()).get("id").asLong();
        mvc.perform(get(query).header("Authorization", auth(user))).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.source == 'PERSONAL')].color", hasItem("#147D56")));
        mvc.perform(put("/api/me/schedules/" + id).header("Authorization", auth(user)).contentType(MediaType.APPLICATION_JSON)
                .content(payload.replace("#147D56", "#4651B3"))).andExpect(status().isNoContent());
        mvc.perform(get(query).header("Authorization", auth(user))).andExpect(jsonPath("$[?(@.source == 'PERSONAL')].color", hasItem("#4651B3")));
        mvc.perform(delete("/api/me/schedules/" + id).header("Authorization", auth(user))).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM schedule_colors WHERE user_id=?", Integer.class, user)).isZero();
    }

    @Test void scheduleUpdateWithoutColorPreservesTheExistingOverride() throws Exception {
        long user = user();
        var created = mvc.perform(post("/api/me/schedules").header("Authorization", auth(user))
                .contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isCreated()).andReturn();
        long id = json.readTree(created.getResponse().getContentAsString()).get("id").asLong();
        String update = "{\"title\":\"수정 회의\",\"at\":\"2026-12-01T00:00:00Z\",\"endAt\":\"2026-12-02T00:00:00Z\"}";
        mvc.perform(put("/api/me/schedules/" + id).header("Authorization", auth(user))
                .contentType(MediaType.APPLICATION_JSON).content(update)).andExpect(status().isNoContent());
        mvc.perform(get(query).header("Authorization", auth(user))).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.source == 'PERSONAL')].color", hasItem("#147D56")));
    }

    @Test void academicColorsArePrivateToEachAccountAndCanResetToDefault() throws Exception {
        long owner = user(), other = user();
        var day = LocalDate.of(2026, 12, 1);
        when(academic.entries(day, day.plusDays(1))).thenReturn(List.of(new ScheduleEntry("ACADEMIC", Instant.parse("2026-11-30T15:00:00Z"), "ACADEMIC", 100, "기말고사", "성심교정", null, false)));
        mvc.perform(put("/api/me/schedule-colors/ACADEMIC/100").header("Authorization", auth(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"ACADEMIC\",\"color\":\"#b83b72\"}")).andExpect(status().isNoContent());
        mvc.perform(get(query).header("Authorization", auth(owner))).andExpect(jsonPath("$[?(@.source == 'ACADEMIC')].color", hasItem("#B83B72")));
        for (String auth : new String[]{"", auth(other)}) {
            mvc.perform(get(query).header("Authorization", auth)).andExpect(jsonPath("$[?(@.source == 'ACADEMIC')].color", hasItem(nullValue())));
        }
        mvc.perform(put("/api/me/schedule-colors/ACADEMIC/100").header("Authorization", auth(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"ACADEMIC\",\"color\":null}")).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM schedule_colors WHERE user_id=?", Integer.class, owner)).isZero();
    }

    @Test void rejectsAnonymousInvalidColorsTypesAndOtherUsersPersonalSchedules() throws Exception {
        long owner = user(), other = user();
        String color = "{\"type\":\"ACADEMIC\",\"color\":\"#4651B3\"}";
        mvc.perform(put("/api/me/schedule-colors/ACADEMIC/100").contentType(MediaType.APPLICATION_JSON).content(color)).andExpect(status().isUnauthorized());
        for (String invalid : new String[]{color.replace("#4651B3", "red"), color.replace("ACADEMIC", "EVENT"), color.replace("#4651B3", "#1234567")}) {
            mvc.perform(put("/api/me/schedule-colors/ACADEMIC/100").header("Authorization", auth(owner)).contentType(MediaType.APPLICATION_JSON).content(invalid)).andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/me/schedules").header("Authorization", auth(owner)).contentType(MediaType.APPLICATION_JSON)
                .content(payload.replace("#147D56", "invalid"))).andExpect(status().isBadRequest());
        long id = jdbc.queryForObject("INSERT INTO personal_schedules(user_id,title,starts_at) VALUES (?,'비공개',now()) RETURNING id", Long.class, owner);
        mvc.perform(put("/api/me/schedule-colors/PERSONAL/" + id).header("Authorization", auth(other)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"PERSONAL\",\"color\":\"#4651B3\"}")).andExpect(status().isNotFound());
    }

    @Test void noticeEventAndDeadlineColorsAreIndependentAndDoNotChangeReminders() throws Exception {
        long user = user();
        for (String type : new String[]{"EVENT", "DEADLINE"}) {
            mvc.perform(put("/api/me/schedule-colors/MANUAL/10").header("Authorization", auth(user)).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"type\":\"" + type + "\",\"color\":\"#147D56\"}")).andExpect(status().isNoContent());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM schedule_colors WHERE user_id=?", Integer.class, user)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reminders WHERE user_id=?", Integer.class, user)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM event_start_reminders WHERE user_id=?", Integer.class, user)).isZero();
    }
}
