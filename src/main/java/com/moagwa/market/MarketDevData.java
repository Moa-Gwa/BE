package com.moagwa.market;

import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** 로컬 개발·시연용 샘플(profile "local"): 재학생 인증을 마친 학생 3명과 물품·같이 사기 몇 개. 이미 있으면 건너뛴다. */
@Slf4j
@Component
@Profile("local")
@RequiredArgsConstructor
class MarketDevData implements CommandLineRunner {

    private final NamedParameterJdbcTemplate jdbc;

    @Override
    public void run(String... args) {
        Integer existing = jdbc.queryForObject("SELECT count(*) FROM market_items", new MapSqlParameterSource(),
                Integer.class);
        if (existing != null && existing > 0) {
            return;
        }
        long a = seedUser("seed-market-1", "알고리즘반장");
        long b = seedUser("seed-market-2", "도서관지박령");
        long c = seedUser("seed-market-3", "자취생활3년차");
        item(a, "알고리즘 전공 서적", "필기 거의 없고 상태 좋아요. 중간고사 끝나서 정리합니다.", 12000, "BOOK", "중앙도서관 앞", "ON_SALE", 3);
        item(b, "아이패드 에어 5세대 64GB", "케이스와 필름 포함입니다. 직접 보고 확인하신 뒤 거래해요.", 380000, "ELECTRONICS", "정문", "RESERVED", 8);
        item(c, "접이식 의자 (자취방용)", "이사 가면서 내놓습니다. 사용감 조금 있어요.", 8000, "LIVING", "학생식당 앞", "ON_SALE", 1);
        item(a, "공학용 계산기", "시험 기간에만 썼어요.", 15000, "ELECTRONICS", "공학관 로비", "SOLD", 0);
        groupBuy(b, "프린터 잉크 같이 사요", "낱개로 사면 배송비가 아까워서 같이 주문하려고 해요.", 9000, 10, Duration.ofDays(3), "중앙도서관 1층");
        groupBuy(c, "전공 노트 5권 묶음", "학교 앞 문구점 묶음 주문입니다.", 4500, 20, Duration.ofDays(5), "학생회관 로비");
        log.info("생활거래 로컬 샘플 데이터를 만들었다.");
    }

    private long seedUser(String providerId, String nickname) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("pid", providerId).addValue("nick", nickname);
        Long id = jdbc.queryForObject("""
                INSERT INTO users (auth_provider, provider_user_id, nickname, verified_at)
                VALUES ('DEV', :pid, :nick, now())
                ON CONFLICT (auth_provider, provider_user_id) DO UPDATE SET verified_at = COALESCE(users.verified_at, now())
                RETURNING id""", p, Long.class);
        return id;
    }

    private void item(long seller, String title, String description, int price, String category, String place,
                      String status, int likes) {
        jdbc.update("""
                INSERT INTO market_items (seller_id, title, description, price, category, trade_place, status, like_count)
                VALUES (:s, :t, :d, :p, :c, :pl, :st, :l)""", new MapSqlParameterSource().addValue("s", seller)
                .addValue("t", title).addValue("d", description).addValue("p", price).addValue("c", category)
                .addValue("pl", place).addValue("st", status).addValue("l", likes));
    }

    private void groupBuy(long host, String title, String description, int unitPrice, int target, Duration deadline,
                          String place) {
        jdbc.update("""
                INSERT INTO group_buys (host_id, title, description, unit_price, target_quantity, deadline_at, pickup_place)
                VALUES (:h, :t, :d, :u, :tg, :dl, :pl)""", new MapSqlParameterSource().addValue("h", host)
                .addValue("t", title).addValue("d", description).addValue("u", unitPrice).addValue("tg", target)
                .addValue("dl", Timestamp.from(Instant.now().plus(deadline))).addValue("pl", place));
    }
}
