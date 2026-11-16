package com.moagwa.crawler;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InstagramPostDetailsCollectorTest {
    @TempDir Path temp;

    @Test void separatesTheFirstCaptionLineAsTitleAndPreservesTheOriginalBody() throws Exception {
        Path script = temp.resolve("caption.sh");
        Files.writeString(script, "cat >/dev/null\ncat <<'JSON'\n" + new ObjectMapper().writeValueAsString(
                Map.of("posts", List.of(Map.of("shortcode", "TEST",
                        "imageUrls", List.of("https://cdn.example/image.jpg"),
                        "caption", "[행사 안내]\n\n본문과 #학생회")))) + "\nJSON\n");
        var properties = new InstagramCrawlerProperties(true, null, null, 3, "/bin/sh", null,
                script.toString(), Duration.ofSeconds(10), null);
        var posts = new InstagramPostDetailsCollector(new ObjectMapper(), properties).collect(List.of(
                new InstagramLinkCollector.InstagramPostLink("test", "TEST", "https://www.instagram.com/p/TEST/")));

        assertThat(posts).hasSize(1);
        assertThat(posts.get(0).title()).isEqualTo("[행사 안내]");
        assertThat(posts.get(0).caption()).isEqualTo("[행사 안내]\n\n본문과 #학생회");
    }

    @Test void drainsLargeStdoutAndStderrBeforeWaitingForTheProcessToExit() throws Exception {
        var mapper = new ObjectMapper();
        String caption = "본문 ".repeat(50000);
        Path response = temp.resolve("response.json");
        Files.writeString(response, mapper.writeValueAsString(Map.of("posts", List.of(Map.of(
                "shortcode", "TEST", "imageUrls", List.of("https://cdn.example/image.jpg"),
                "caption", caption, "publishedAt", "2026-11-16T00:00:00Z")))));
        Path script = temp.resolve("collector.sh");
        String quoted = "'" + response.toString().replace("'", "'\"'\"'") + "'";
        Files.writeString(script, "cat >/dev/null\ncat " + quoted + " >&2\ncat " + quoted + "\n");
        var properties = new InstagramCrawlerProperties(true, null, null, 3, "/bin/sh", null,
                script.toString(), Duration.ofSeconds(10), null);
        var collector = new InstagramPostDetailsCollector(mapper, properties);
        var posts = collector.collect(List.of(new InstagramLinkCollector.InstagramPostLink("test", "TEST", "https://www.instagram.com/p/TEST/")));
        assertThat(posts).hasSize(1);
        assertThat(posts.get(0).caption()).isEqualTo(caption.strip());
        assertThat(posts.get(0).imageUrls()).containsExactly("https://cdn.example/image.jpg");
    }

    @Test void reportsRateLimitEvenWhenTheMarkerIsBeyondTheTrimmedError() throws Exception {
        Path script = temp.resolve("limited.sh");
        Files.writeString(script, "cat >/dev/null\nprintf '%0400d\\nError: INSTAGRAM_RATE_LIMIT\\n' 0 >&2\nexit 1\n");
        var properties = new InstagramCrawlerProperties(true, null, null, 3, "/bin/sh", null,
                script.toString(), Duration.ofSeconds(10), null);
        var collector = new InstagramPostDetailsCollector(new ObjectMapper(), properties);
        assertThatThrownBy(() -> collector.collect(List.of(
                new InstagramLinkCollector.InstagramPostLink("test", "TEST", "https://www.instagram.com/p/TEST/"))))
                .hasMessageContaining("INSTAGRAM_RATE_LIMIT");
    }
}
