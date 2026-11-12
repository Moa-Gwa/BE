package com.moagwa.crawler;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Opens only database-new Instagram posts in the local logged-in Playwright browser. */
@Component
@RequiredArgsConstructor
class InstagramPostDetailsCollector {

    private final ObjectMapper objectMapper;
    private final InstagramCrawlerProperties properties;

    List<InstagramPost> collect(List<InstagramLinkCollector.InstagramPostLink> links) {
        if (links.isEmpty()) {
            return List.of();
        }
        try {
            Path scriptPath = Path.of(properties.postDetailsScript()).toAbsolutePath().normalize();
            Process process = new ProcessBuilder(properties.nodeCommand(), scriptPath.toString()).start();
            process.getOutputStream().write(objectMapper.writeValueAsBytes(new Input(
                    links.stream().map(InstagramLinkCollector.InstagramPostLink::url).distinct().toList())));
            process.getOutputStream().close();

            if (!process.waitFor(properties.commandTimeout().toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Instagram post detail collector timed out");
            }
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                throw new IllegalStateException("Instagram post detail collector failed: " + trim(stderr));
            }

            Output output = objectMapper.readValue(stdout, Output.class);
            Map<String, InstagramLinkCollector.InstagramPostLink> linksById = new LinkedHashMap<>();
            links.forEach(link -> linksById.put(link.externalId(), link));
            List<InstagramPost> posts = new ArrayList<>();
            for (PostOutput detail : output.posts()) {
                InstagramLinkCollector.InstagramPostLink link = linksById.get(detail.shortcode());
                List<String> images = detail.imageUrls() == null ? List.of() : detail.imageUrls().stream()
                        .filter(url -> url != null && url.startsWith("https://"))
                        .distinct()
                        .toList();
                if (link != null && !images.isEmpty()) {
                    String caption = detail.caption() == null ? "" : detail.caption().trim();
                    posts.add(new InstagramPost(link.username(), link.externalId(), titleFrom(caption, link.username()),
                            link.url(), parseInstant(detail.publishedAt()), caption, images));
                }
            }
            return List.copyOf(posts);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Could not collect Instagram post details locally", e);
        }
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            try {
                return OffsetDateTime.parse(value).toInstant();
            } catch (DateTimeParseException ignoredAgain) {
                return null;
            }
        }
    }

    private static String titleFrom(String caption, String username) {
        String firstLine = caption.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .findFirst()
                .orElse(username + " 인스타그램 새 게시물")
                .replaceAll("\\s+", " ");
        return firstLine.length() <= 500 ? firstLine : firstLine.substring(0, 497) + "...";
    }

    private static String trim(String value) {
        String trimmed = value.strip();
        return trimmed.length() <= 300 ? trimmed : trimmed.substring(0, 300);
    }

    private record Input(List<String> postUrls) {}
    private record Output(List<PostOutput> posts) {}
    private record PostOutput(String postUrl, String shortcode, List<String> imageUrls, String caption,
                              String publishedAt, String status) {}

    record InstagramPost(String username, String externalId, String title, String url, Instant publishedAt,
                         String caption, List<String> imageUrls) {}
}
