package com.moagwa.chat;

import com.moagwa.chat.ChatDto.Message;
import com.moagwa.chat.ChatDto.ReadState;
import com.moagwa.chat.ChatDto.RoomSummary;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
class ChatRepository {

    private final NamedParameterJdbcTemplate jdbc;

    void createForRequest(long requestId) {
        jdbc.update("INSERT INTO chat_rooms (request_id) VALUES (:id) ON CONFLICT DO NOTHING",
                new MapSqlParameterSource("id", requestId));
    }

    void createForGroupBuy(long groupBuyId) {
        jdbc.update("INSERT INTO chat_rooms (group_buy_id) VALUES (:id) ON CONFLICT DO NOTHING",
                new MapSqlParameterSource("id", groupBuyId));
    }

    boolean isMember(long roomId, long userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM chat_members WHERE room_id = :room AND user_id = :user)",
                new MapSqlParameterSource().addValue("room", roomId).addValue("user", userId), Boolean.class));
    }

    List<Long> members(long roomId) {
        return jdbc.queryForList("SELECT user_id FROM chat_members WHERE room_id = :room",
                new MapSqlParameterSource("room", roomId), Long.class);
    }

    /** 거래 방 제목은 물품명, 단체방은 모집명. 거래 방은 상대 닉네임도 함께 보여 준다. */
    List<RoomSummary> findRooms(long me, int limit) {
        return jdbc.query("""
                SELECT r.id, r.request_id, r.group_buy_id, i.id AS item_id,
                       COALESCE(i.title, g.title) AS title,
                       CASE WHEN r.request_id IS NULL THEN NULL
                            WHEN i.seller_id = :me THEN bu.nickname ELSE su.nickname END AS partner,
                       last.content AS last_content, COALESCE(last.created_at, r.created_at) AS last_at,
                       (SELECT count(*) FROM chat_messages x
                         WHERE x.room_id = r.id AND x.sender_id <> :me AND x.id > COALESCE(cr.last_read_id, 0)) AS unread
                  FROM chat_members cm
                  JOIN chat_rooms r ON r.id = cm.room_id
                  LEFT JOIN market_requests q ON q.id = r.request_id
                  LEFT JOIN market_items i ON i.id = q.item_id
                  LEFT JOIN users su ON su.id = i.seller_id
                  LEFT JOIN users bu ON bu.id = q.buyer_id
                  LEFT JOIN group_buys g ON g.id = r.group_buy_id
                  LEFT JOIN chat_reads cr ON cr.room_id = r.id AND cr.user_id = :me
                  LEFT JOIN LATERAL (SELECT content, created_at FROM chat_messages
                                      WHERE room_id = r.id ORDER BY id DESC LIMIT 1) last ON TRUE
                 WHERE cm.user_id = :me
                 ORDER BY last_at DESC, r.id DESC
                 LIMIT :limit""",
                new MapSqlParameterSource().addValue("me", me).addValue("limit", limit),
                (rs, n) -> new RoomSummary(rs.getLong("id"),
                        rs.getObject("request_id") == null ? "GROUP" : "TRADE",
                        rs.getString("title"), rs.getString("partner"),
                        rs.getObject("item_id", Long.class), rs.getObject("group_buy_id", Long.class),
                        rs.getString("last_content"), instant(rs, "last_at"), rs.getLong("unread")));
    }

    /** before보다 오래된 메시지를 최신순으로 limit개 가져온다. */
    List<Message> findMessages(long roomId, Long before, int limit) {
        return jdbc.query("""
                SELECT m.id, m.room_id, m.sender_id, u.nickname, m.content, m.created_at
                  FROM chat_messages m JOIN users u ON u.id = m.sender_id
                 WHERE m.room_id = :room AND (CAST(:before AS BIGINT) IS NULL OR m.id < :before)
                 ORDER BY m.id DESC
                 LIMIT :limit""",
                new MapSqlParameterSource().addValue("room", roomId).addValue("before", before)
                        .addValue("limit", limit),
                ChatRepository::message);
    }

    Message insert(long roomId, long senderId, String content) {
        return jdbc.queryForObject("""
                WITH m AS (INSERT INTO chat_messages (room_id, sender_id, content) VALUES (:room, :sender, :content)
                           RETURNING id, room_id, sender_id, content, created_at)
                SELECT m.id, m.room_id, m.sender_id, u.nickname, m.content, m.created_at
                  FROM m JOIN users u ON u.id = m.sender_id""",
                new MapSqlParameterSource().addValue("room", roomId).addValue("sender", senderId)
                        .addValue("content", content),
                ChatRepository::message);
    }

    /** 읽음 위치는 뒤로 가지 않고, 방에 없는 메시지 번호로 앞질러 가지도 않는다. 앞으로 갔을 때만 새 위치를 돌려준다. */
    Optional<Long> markRead(long roomId, long userId, long lastReadId) {
        return jdbc.queryForList("""
                INSERT INTO chat_reads (room_id, user_id, last_read_id)
                SELECT :room, :user, LEAST(:last, max(id)) FROM chat_messages WHERE room_id = :room
                HAVING max(id) IS NOT NULL
                ON CONFLICT (room_id, user_id) DO UPDATE SET last_read_id = EXCLUDED.last_read_id
                 WHERE chat_reads.last_read_id < EXCLUDED.last_read_id
                RETURNING last_read_id""",
                new MapSqlParameterSource().addValue("room", roomId).addValue("user", userId)
                        .addValue("last", lastReadId), Long.class).stream().findFirst();
    }

    List<ReadState> reads(long roomId) {
        return jdbc.query("""
                SELECT cr.user_id, cr.last_read_id FROM chat_reads cr
                  JOIN chat_members cm ON cm.room_id = cr.room_id AND cm.user_id = cr.user_id
                 WHERE cr.room_id = :room""",
                new MapSqlParameterSource("room", roomId),
                (rs, n) -> new ReadState(rs.getLong("user_id"), rs.getLong("last_read_id")));
    }

    private static Message message(ResultSet rs, int n) throws SQLException {
        return new Message(rs.getLong("id"), rs.getLong("room_id"), rs.getLong("sender_id"), rs.getString("nickname"),
                rs.getString("content"), instant(rs, "created_at"));
    }

    private static String instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant().toString();
    }
}
