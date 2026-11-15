package com.moagwa.feed;

import com.moagwa.common.exception.ApiException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
class ScheduleColors {
    private final JdbcTemplate jdbc;
    private record Key(String source, String type, long id) {}

    List<ScheduleEntry> apply(Long user, List<ScheduleEntry> entries) {
        if (user == null || entries.isEmpty()) return entries;
        Map<Key, String> colors = new HashMap<>();
        jdbc.query("SELECT source,entry_type,target_id,color FROM schedule_colors WHERE user_id=?",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> colors.put(new Key(rs.getString(1), rs.getString(2), rs.getLong(3)), rs.getString(4)), user);
        return entries.stream().map(e -> e.withColor(colors.get(new Key(e.source(), e.type(), e.noticeId())))).toList();
    }

    @Transactional
    void set(long user, String source, String type, long id, String color) {
        boolean validType = switch (source) {
            case "MANUAL", "CRAWLED" -> Set.of("EVENT", "DEADLINE").contains(type == null ? "" : type);
            case "PERSONAL", "ACADEMIC" -> source.equals(type);
            default -> false;
        };
        if (!validType || id <= 0 || (color != null && !color.matches("^#[0-9A-Fa-f]{6}$"))) {
            throw ApiException.badRequest("올바른 일정과 색상을 선택해 주세요.");
        }
        if (source.equals("PERSONAL") && !Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM personal_schedules WHERE id=? AND user_id=?)", Boolean.class, id, user))) {
            throw ApiException.notFound("일정을 찾을 수 없습니다.");
        }
        if (color == null) {
            jdbc.update("DELETE FROM schedule_colors WHERE user_id=? AND source=? AND entry_type=? AND target_id=?", user, source, type, id);
        } else {
            jdbc.update("""
                INSERT INTO schedule_colors(user_id,source,entry_type,target_id,color) VALUES (?,?,?,?,?)
                ON CONFLICT(user_id,source,entry_type,target_id) DO UPDATE SET color=EXCLUDED.color
                """, user, source, type, id, color.toUpperCase(java.util.Locale.ROOT));
        }
    }
}
