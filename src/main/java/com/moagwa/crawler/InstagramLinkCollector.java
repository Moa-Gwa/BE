package com.moagwa.crawler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moagwa.crawler.InstagramAccountCatalog.InstagramAccount;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Runs a local, logged-in Playwright process to collect only recent post links from public
 * profiles. The caller removes database duplicates before the local detail collector opens
 * any individual post page.
 */
@Component
@RequiredArgsConstructor
class InstagramLinkCollector {

    private final ObjectMapper objectMapper;
    private final InstagramCrawlerProperties properties;

    @Value("${INSTAGRAM_SESSION_ID:}")
    private String sessionId;

    Map<InstagramAccount, List<InstagramPostLink>> collect(List<InstagramAccount> accounts, int limit) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalStateException("INSTAGRAM_SESSION_ID is not configured");
        }
        try {
            Path scriptPath = Path.of(properties.profileLinksScript()).toAbsolutePath().normalize();
            Process process = new ProcessBuilder(properties.nodeCommand(), scriptPath.toString()).start();
            var collectedOutput = CompletableFuture.supplyAsync(() -> readOutput(process.getInputStream()));
            var collectedError = CompletableFuture.supplyAsync(() -> readOutput(process.getErrorStream()));
            process.getOutputStream().write(objectMapper.writeValueAsBytes(new Input(
                    accounts.stream().map(account -> new AccountInput(account.username(), account.profileUrl())).toList(),
                    limit, properties.since() == null ? null : properties.since().toString())));
            process.getOutputStream().close();

            if (!process.waitFor(properties.commandTimeout().toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Instagram profile link collector timed out");
            }
            String stdout = collectedOutput.join();
            String stderr = collectedError.join();
            if (process.exitValue() != 0) {
                // 300자로 자르면 표식이 잘려 나가므로 전체 출력에서 확인
                throw new IllegalStateException("Instagram profile link collector failed: "
                        + (stderr.contains("INSTAGRAM_RATE_LIMIT") ? "INSTAGRAM_RATE_LIMIT" : trim(stderr)));
            }
            Output output = objectMapper.readValue(stdout, Output.class);
            Map<String, List<InstagramPostLink>> byUsername = new LinkedHashMap<>();
            for (AccountOutput account : output.accounts()) {
                byUsername.put(account.username(), account.links());
            }
            Map<InstagramAccount, List<InstagramPostLink>> collected = new LinkedHashMap<>();
            for (InstagramAccount account : accounts) {
                String username = normalize(account.username());
                collected.put(account, byUsername.getOrDefault(username, List.of()).stream()
                        .map(link -> new InstagramPostLink(username, link.externalId(), link.url()))
                        .toList());
            }
            return Map.copyOf(collected);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("Could not collect Instagram post links locally", e);
        }
    }

    private static String normalize(String username) {
        return username.strip().replaceFirst("^@", "").toLowerCase(java.util.Locale.ROOT);
    }

    private static String readOutput(InputStream stream) {
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String trim(String value) {
        String trimmed = value.strip();
        return trimmed.length() <= 300 ? trimmed : trimmed.substring(0, 300);
    }

    private record Input(List<AccountInput> accounts, int resultsLimit, String since) {}
    private record AccountInput(String username, String profileUrl) {}
    private record Output(List<AccountOutput> accounts) {}
    private record AccountOutput(String username, List<InstagramPostLink> links) {}

    record InstagramPostLink(String username, String externalId, String url) {}
}
