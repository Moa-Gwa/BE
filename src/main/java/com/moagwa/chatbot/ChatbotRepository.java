package com.moagwa.chatbot;

import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 질문 수와 답변 평가 저장. 질문 내용은 어디에도 남기지 않음 */
@Repository
@RequiredArgsConstructor
class ChatbotRepository {
    private final NamedParameterJdbcTemplate jdbc;

    /** 하루 한도 안이면 질문 수를 1 올리고 true. 단일 UPSERT라 동시 요청에도 한도를 넘지 않음 */
    boolean claimQuestion(long user, LocalDate day, int dailyLimit) {
        return jdbc.update("""
                INSERT INTO chatbot_usage (user_id, day, count) VALUES (:u, :d, 1)
                ON CONFLICT (user_id, day) DO UPDATE SET count = chatbot_usage.count + 1
                WHERE chatbot_usage.count < :limit
                """, new MapSqlParameterSource().addValue("u", user).addValue("d", day).addValue("limit", dailyLimit)) > 0;
    }

    int totalQuestions(LocalDate day) {
        Integer total = jdbc.queryForObject("SELECT COALESCE(SUM(count), 0) FROM chatbot_usage WHERE day = :d",
                new MapSqlParameterSource("d", day), Integer.class);
        return total == null ? 0 : total;
    }

    void saveFeedback(String rating, List<String> sourceUrls) {
        jdbc.update("INSERT INTO chatbot_feedback (rating, source_urls) VALUES (:r, :s)",
                new MapSqlParameterSource().addValue("r", rating).addValue("s", String.join("\n", sourceUrls)));
    }
}
