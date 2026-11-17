package com.moagwa.chatbot;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moagwa.crawler.domain.CrawlSource;
import com.moagwa.crawler.domain.CrawlSourceRepository;
import com.moagwa.crawler.domain.CrawledNotice;
import com.moagwa.crawler.domain.CrawledNoticeRepository;
import com.moagwa.organization.Organization;
import com.moagwa.organization.OrganizationRepository;
import com.moagwa.organization.OrganizationType;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class ChatbotNoticeSyncTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired OrganizationRepository organizations;
    @Autowired CrawlSourceRepository sources;
    @Autowired CrawledNoticeRepository crawled;
    @Autowired ObjectMapper json;

    HttpServer chatbot;
    final List<JsonNode> received = new CopyOnWriteArrayList<>();
    final Instant[] now = {Instant.parse("2026-11-18T00:00:00Z")};

    @BeforeEach
    void startFakeChatbot() throws Exception {
        chatbot = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        chatbot.createContext("/v1/notices", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("PUT");
            assertThat(exchange.getRequestHeaders().getFirst("X-Internal-Token")).isEqualTo("secret");
            received.add(json.readTree(exchange.getRequestBody()));
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
        });
        chatbot.start();
    }

    @AfterEach
    void stopFakeChatbot() {
        chatbot.stop(0);
    }

    ChatbotNoticeSync sync() {
        Clock clock = new Clock() {
            public ZoneOffset getZone() { return ZoneOffset.UTC; }
            public Clock withZone(java.time.ZoneId zone) { return this; }
            public Instant instant() { return now[0]; }
        };
        return new ChatbotNoticeSync(jdbc, clock, "http://127.0.0.1:" + chatbot.getAddress().getPort(), "secret",
                "https://moagwa.example/");
    }

    @Test
    void sendsActiveNoticesOnlyWhenChangedOrEveryTenMinutes() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        var org = organizations.saveAndFlush(new Organization("챗봇 공지 학과 " + tag, OrganizationType.DEPARTMENT));
        var source = sources.saveAndFlush(new CrawlSource("chatbot-" + tag, org, "https://school.example/list.do"));
        CrawledNotice fresh = new CrawledNotice(source, "fresh-" + tag, "장학금 신청 안내 " + tag, "https://school.example/1",
                false, Instant.now(), Instant.now());
        fresh.applyContent("<p>본문</p>", "성적우수 장학금 신청 본문 " + tag);
        long freshId = crawled.saveAndFlush(fresh).getId();
        long old = crawled.saveAndFlush(new CrawledNotice(source, "old-" + tag, "지난 공지 " + tag, "https://school.example/2",
                false, Instant.now().minus(Duration.ofDays(200)), Instant.now())).getId();

        ChatbotNoticeSync sync = sync();
        sync.tick();
        assertThat(received).hasSize(1);
        List<String> keys = received.get(0).get("notices").findValuesAsText("key");
        assertThat(keys).contains("CRAWLED:" + freshId).doesNotContain("CRAWLED:" + old);
        JsonNode notice = received.get(0).get("notices").findParents("key").stream()
                .filter(n -> n.get("key").asText().equals("CRAWLED:" + freshId)).findFirst().orElseThrow();
        assertThat(notice.get("url").asText()).isEqualTo("https://moagwa.example/notices/crawled/" + freshId);
        assertThat(notice.get("organization").asText()).isEqualTo(org.getName());
        assertThat(notice.get("body").asText()).isEqualTo("성적우수 장학금 신청 본문 " + tag);
        assertThat(notice.get("publishedAt").asText()).endsWith("Z");

        sync.tick(); // 바뀐 게 없으면 보내지 않음
        assertThat(received).hasSize(1);

        jdbc.update("UPDATE crawled_notices SET title = ? WHERE id = ?", "장학금 신청 기간 연장 " + tag, freshId);
        sync.tick(); // 제목이 바뀌면 바로 다시 보냄
        assertThat(received).hasSize(2);

        now[0] = now[0].plus(Duration.ofMinutes(11));
        sync.tick(); // 바뀐 게 없어도 10분이 지나면 다시 보냄(챗봇 재시작 복구)
        assertThat(received).hasSize(3);
    }

    @Test
    void failedSendIsRetriedOnTheNextCheck() {
        chatbot.stop(0);
        ChatbotNoticeSync sync = sync();
        sync.tick(); // 연결 실패는 예외 없이 넘어감
        startFakeChatbotQuietly();
        sync.tick();
        assertThat(received).hasSize(1);
    }

    private void startFakeChatbotQuietly() {
        try {
            int port = chatbot.getAddress().getPort();
            chatbot = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            chatbot.createContext("/v1/notices", exchange -> {
                received.add(json.readTree(exchange.getRequestBody()));
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
            });
            chatbot.start();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
