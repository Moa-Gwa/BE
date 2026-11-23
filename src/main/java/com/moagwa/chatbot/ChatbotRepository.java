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

    /** 학생별 하루 질문 수를 1 올림. 한도 없이 사용량 확인용으로만 남김 */
    void recordQuestion(long user, LocalDate day) {
        jdbc.update("""
                INSERT INTO chatbot_usage (user_id, day, count) VALUES (:u, :d, 1)
                ON CONFLICT (user_id, day) DO UPDATE SET count = chatbot_usage.count + 1
                """, new MapSqlParameterSource().addValue("u", user).addValue("d", day));
    }

    void saveFeedback(String rating, List<String> sourceUrls) {
        jdbc.update("INSERT INTO chatbot_feedback (rating, source_urls) VALUES (:r, :s)",
                new MapSqlParameterSource().addValue("r", rating).addValue("s", String.join("\n", sourceUrls)));
    }
}
