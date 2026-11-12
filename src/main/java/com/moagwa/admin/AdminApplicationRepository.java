package com.moagwa.admin;

import com.moagwa.admin.AdminApplicationDto.ApplicationForm;
import com.moagwa.admin.AdminApplicationDto.ApplicationView;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** admin_applications 테이블 접근 */
@Repository
@RequiredArgsConstructor
class AdminApplicationRepository {

    /** 승인 처리에 필요한 신청 내용 */
    record Application(long id, String status, String organizationName, String university, String department,
                       String contactName, String email, String channelUrl, Long adminId) {

        boolean pending() {
            return "PENDING".equals(status);
        }

        boolean approved() {
            return "APPROVED".equals(status);
        }
    }

    record SetupTarget(String email, Instant expiresAt) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    long insert(ApplicationForm f, String email) {
        return jdbc.queryForObject("""
                INSERT INTO admin_applications
                    (organization_name, university, department, contact_name, email, phone, channel_url, reason)
                VALUES (:org, :uni, :dept, :name, :email, :phone, :channel, :reason)
                RETURNING id""",
                new MapSqlParameterSource()
                        .addValue("org", f.organizationName().trim())
                        .addValue("uni", f.university().trim())
                        .addValue("dept", f.department().trim())
                        .addValue("name", f.contactName().trim())
                        .addValue("email", email)
                        .addValue("phone", f.phone().trim())
                        .addValue("channel", f.channelUrl().trim())
                        .addValue("reason", f.reason().trim()),
                Long.class);
    }

    List<ApplicationView> findByStatus(String status) {
        return jdbc.query("""
                SELECT id, organization_name, university, department, contact_name, email, phone, channel_url,
                       reason, status, rejection_reason, created_at
                  FROM admin_applications
                 WHERE status = :status
                 ORDER BY created_at""",
                new MapSqlParameterSource("status", status),
                (rs, i) -> new ApplicationView(rs.getLong("id"), rs.getString("organization_name"),
                        rs.getString("university"), rs.getString("department"), rs.getString("contact_name"),
                        rs.getString("email"), rs.getString("phone"), rs.getString("channel_url"),
                        rs.getString("reason"), rs.getString("status"), rs.getString("rejection_reason"),
                        rs.getTimestamp("created_at").toInstant().toString()));
    }

    /** 승인이 동시에 두 번 처리되지 않도록 행을 잠근 채 읽음 */
    Optional<Application> lock(long id) {
        return jdbc.query("""
                SELECT id, status, organization_name, university, department, contact_name, email, channel_url,
                       admin_id
                  FROM admin_applications
                 WHERE id = :id
                   FOR UPDATE""",
                new MapSqlParameterSource("id", id),
                (rs, i) -> new Application(rs.getLong("id"), rs.getString("status"),
                        rs.getString("organization_name"), rs.getString("university"), rs.getString("department"),
                        rs.getString("contact_name"), rs.getString("email"), rs.getString("channel_url"),
                        rs.getObject("admin_id", Long.class)))
                .stream().findFirst();
    }

    boolean exists(long id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM admin_applications WHERE id = :id)",
                new MapSqlParameterSource("id", id), Boolean.class));
    }

    void markApproved(long id, long organizationId, long adminId) {
        jdbc.update("""
                UPDATE admin_applications
                   SET status = 'APPROVED', organization_id = :org, admin_id = :admin, resolved_at = now()
                 WHERE id = :id""",
                new MapSqlParameterSource().addValue("id", id).addValue("org", organizationId).addValue("admin", adminId));
    }

    void saveSetupToken(long id, String setupTokenHash, Instant setupExpiresAt) {
        jdbc.update("""
                UPDATE admin_applications
                   SET setup_token_hash = :hash, setup_expires_at = :expires
                 WHERE id = :id""",
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("hash", setupTokenHash)
                        .addValue("expires", Timestamp.from(setupExpiresAt)));
    }

    /** 대기 중인 신청만 거절됨. 거절했으면 true */
    boolean rejectPending(long id, String reason) {
        return jdbc.update("""
                UPDATE admin_applications
                   SET status = 'REJECTED', rejection_reason = :reason, resolved_at = now()
                 WHERE id = :id AND status = 'PENDING'""",
                new MapSqlParameterSource().addValue("id", id).addValue("reason", reason)) > 0;
    }

    Optional<SetupTarget> findSetup(String setupTokenHash) {
        return jdbc.query("""
                SELECT email, setup_expires_at
                  FROM admin_applications
                 WHERE setup_token_hash = :hash AND status = 'APPROVED'""",
                new MapSqlParameterSource("hash", setupTokenHash),
                (rs, i) -> new SetupTarget(rs.getString("email"), rs.getTimestamp("setup_expires_at").toInstant()))
                .stream().findFirst();
    }

    /** 만료 전 토큰을 지우면서 관리자 id를 돌려줌. 한 UPDATE로 처리해 같은 링크는 한 번만 쓰임 */
    Optional<Long> consumeSetupToken(String setupTokenHash) {
        return jdbc.queryForList("""
                UPDATE admin_applications
                   SET setup_token_hash = NULL, setup_expires_at = NULL
                 WHERE setup_token_hash = :hash AND status = 'APPROVED' AND setup_expires_at > now()
                RETURNING admin_id""",
                new MapSqlParameterSource("hash", setupTokenHash), Long.class)
                .stream().findFirst();
    }
}
