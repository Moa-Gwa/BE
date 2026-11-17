package com.moagwa.user;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** 약관별 동의 이력. 같은 문서 버전의 최초 동의 시각은 절대 덮어쓰지 않는다. */
@Repository
public class ConsentRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public ConsentRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean hasCurrentConsent(long userId) {
        Boolean agreed = jdbc.queryForObject("""
                SELECT count(DISTINCT document_type) = 2 FROM user_consents
                 WHERE user_id = :u AND version = :v AND document_type IN ('TERMS', 'PRIVACY')""",
                new MapSqlParameterSource().addValue("u", userId).addValue("v", User.TERMS_VERSION), Boolean.class);
        return Boolean.TRUE.equals(agreed);
    }

    public void agreeCurrent(long userId) {
        jdbc.update("""
                INSERT INTO user_consents (user_id, document_type, version)
                VALUES (:u, 'TERMS', :v), (:u, 'PRIVACY', :v)
                ON CONFLICT (user_id, document_type, version) DO NOTHING""",
                new MapSqlParameterSource().addValue("u", userId).addValue("v", User.TERMS_VERSION));
    }
}
