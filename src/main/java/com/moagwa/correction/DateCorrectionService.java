package com.moagwa.correction;

import com.moagwa.common.exception.ApiException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 크롤링 공지 날짜 교정 요청. 학생이 요청하고 운영자가 원문과 비교해 반영·반려한다.
 * 반영한 항목은 deadline_locked·event_locked로 표시해 자동 추출이 다시 덮어쓰지 않게 한다.
 */
@Service
@RequiredArgsConstructor
class DateCorrectionService {

    static final int LIST_LIMIT = 100;

    private final NamedParameterJdbcTemplate jdbc;

    @Transactional
    DateCorrectionView.Created request(long noticeId, long userId, DateCorrectionRequest req) {
        req.validate();
        if (!exists(noticeId)) {
            throw ApiException.notFound("공지를 찾을 수 없습니다.");
        }
        try {
            Long id = jdbc.queryForObject("""
                    INSERT INTO notice_date_corrections (crawled_notice_id, user_id, field, suggested_start, suggested_end, memo)
                    VALUES (:notice, :user, :field, :start, :end, :memo) RETURNING id""",
                    new MapSqlParameterSource().addValue("notice", noticeId).addValue("user", userId)
                            .addValue("field", req.field()).addValue("start", req.suggestedStart())
                            .addValue("end", req.suggestedEnd()).addValue("memo", req.memoOrNull()), Long.class);
            return new DateCorrectionView.Created(id, "PENDING");
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "이미 접수한 요청이 있어요. 운영자가 확인하고 있어요.");
        }
    }

    /** 검토 대기는 오래된 요청부터, 처리 내역은 최근 처리한 요청부터 */
    @Transactional(readOnly = true)
    List<DateCorrectionView> list(String status) {
        if (!List.of("PENDING", "APPLIED", "REJECTED").contains(status)) {
            throw ApiException.badRequest("알 수 없는 상태입니다.");
        }
        return jdbc.query("""
                SELECT r.id, r.field, r.suggested_start, r.suggested_end, r.memo, r.status, r.created_at,
                       c.id AS notice_id, c.title, c.url, c.published_at, c.deadline_at, c.event_start_at, c.event_end_at,
                       c.date_evidence, c.content_text
                  FROM notice_date_corrections r
                  JOIN crawled_notices c ON c.id = r.crawled_notice_id
                 WHERE r.status = :status
                 ORDER BY %s
                 LIMIT :limit""".formatted("PENDING".equals(status) ? "r.created_at" : "r.resolved_at DESC"),
                new MapSqlParameterSource().addValue("status", status).addValue("limit", LIST_LIMIT),
                (rs, i) -> new DateCorrectionView(rs.getLong("id"), rs.getString("field"),
                        date(rs.getObject("suggested_start", LocalDate.class)),
                        date(rs.getObject("suggested_end", LocalDate.class)), rs.getString("memo"),
                        rs.getString("status"), instant(rs.getTimestamp("created_at")),
                        new DateCorrectionView.Notice(rs.getLong("notice_id"), rs.getString("title"), rs.getString("url"),
                                instant(rs.getTimestamp("published_at")), instant(rs.getTimestamp("deadline_at")),
                                instant(rs.getTimestamp("event_start_at")), instant(rs.getTimestamp("event_end_at")),
                                rs.getString("date_evidence"), rs.getString("content_text"))));
    }

    /** 운영자가 확정한 값을 저장하고, 같은 공지·항목의 대기 요청을 함께 반영 처리 */
    @Transactional
    void apply(long correctionId, long adminId, DateCorrectionApply body) {
        Pending p = pending(correctionId);
        MapSqlParameterSource params = new MapSqlParameterSource("notice", p.noticeId());
        if ("DEADLINE".equals(p.field())) {
            params.addValue("deadline", ts(body.deadlineAt()));
            jdbc.update("""
                    UPDATE crawled_notices SET deadline_at = :deadline, date_evidence = NULL, deadline_locked = true
                     WHERE id = :notice""", params);
        } else {
            body.validateEvent();
            params.addValue("start", ts(body.eventStartAt())).addValue("end", ts(body.eventEndAt()));
            jdbc.update("""
                    UPDATE crawled_notices SET event_start_at = :start, event_end_at = :end, date_evidence = NULL,
                           event_locked = true
                     WHERE id = :notice""", params);
        }
        jdbc.update("""
                UPDATE notice_date_corrections SET status = 'APPLIED', resolved_at = now(), resolved_by = :admin
                 WHERE crawled_notice_id = :notice AND field = :field AND status = 'PENDING'""",
                new MapSqlParameterSource().addValue("notice", p.noticeId()).addValue("field", p.field())
                        .addValue("admin", adminId));
    }

    @Transactional
    void reject(long correctionId, long adminId) {
        pending(correctionId);
        jdbc.update("""
                UPDATE notice_date_corrections SET status = 'REJECTED', resolved_at = now(), resolved_by = :admin
                 WHERE id = :id""", new MapSqlParameterSource().addValue("id", correctionId).addValue("admin", adminId));
    }

    private record Pending(long noticeId, String field) {
    }

    /**
     * 대기 중인 요청을 잠그고 조회. 이미 처리된 요청이면 409.
     * 같은 공지의 요청을 동시에 처리할 때 교착되지 않도록 공지 행을 먼저 잠근 뒤 요청 행을 잠근다
     */
    private Pending pending(long correctionId) {
        jdbc.query("""
                SELECT c.id FROM crawled_notices c
                 WHERE c.id = (SELECT crawled_notice_id FROM notice_date_corrections WHERE id = :id) FOR UPDATE""",
                new MapSqlParameterSource("id", correctionId), (rs, i) -> rs.getLong(1));
        List<Pending> rows = jdbc.query("""
                SELECT crawled_notice_id, field, status FROM notice_date_corrections WHERE id = :id FOR UPDATE""",
                new MapSqlParameterSource("id", correctionId), (rs, i) -> "PENDING".equals(rs.getString("status"))
                        ? new Pending(rs.getLong("crawled_notice_id"), rs.getString("field")) : null);
        if (rows.isEmpty()) {
            throw ApiException.notFound("교정 요청을 찾을 수 없습니다.");
        }
        if (rows.get(0) == null) {
            throw new ApiException(HttpStatus.CONFLICT, "이미 처리된 요청입니다.");
        }
        return rows.get(0);
    }

    private boolean exists(long noticeId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM crawled_notices WHERE id = :id)",
                new MapSqlParameterSource("id", noticeId), Boolean.class));
    }

    private static Timestamp ts(Instant at) {
        return at == null ? null : Timestamp.from(at);
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    private static String date(LocalDate d) {
        return d == null ? null : d.toString();
    }
}
