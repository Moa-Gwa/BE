package com.moagwa.crawler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * The account list is shared with the checked-in Instagram snapshot so the local browser crawler and
 * the local seed data always cover the same student-government accounts.
 */
@Component
@RequiredArgsConstructor
class InstagramAccountCatalog {

    private static final String DATA_FILE = "db/cuk_instagram_notices_sept2026.json";

    private final ObjectMapper objectMapper;
    private List<InstagramAccount> accounts = List.of();

    @PostConstruct
    void load() {
        try (InputStream input = new ClassPathResource(DATA_FILE).getInputStream()) {
            JsonNode root = objectMapper.readTree(input);
            List<InstagramAccount> loaded = new ArrayList<>();
            for (JsonNode node : root) {
                String organizationName = text(node, "org");
                String username = text(node, "instagram");
                String profileUrl = text(node, "profileUrl");
                if (!organizationName.isBlank() && !username.isBlank() && !profileUrl.isBlank()) {
                    loaded.add(new InstagramAccount(organizationName, username, profileUrl));
                }
            }
            accounts = List.copyOf(loaded);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load Instagram account catalog", e);
        }
    }

    List<InstagramAccount> accounts() {
        return accounts;
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).asText("").trim();
    }

    record InstagramAccount(String organizationName, String username, String profileUrl) {
    }
}
