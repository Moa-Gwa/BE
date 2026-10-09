package com.moagwa.crawler;

import com.moagwa.crawler.InstagramPostDetailsCollector.InstagramPost;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Downloads Instagram CDN images into the same local store used by admin uploads. */
@Slf4j
@Component
class InstagramImageStorage {

    private static final long MAX_IMAGE_BYTES = 5L * 1024 * 1024;

    private final Path root;
    private final HttpClient client;
    private final Duration requestTimeout;

    InstagramImageStorage(@Value("${moagwa.upload.dir}") String uploadDir,
                          @Value("${crawler.request-timeout:PT10S}") Duration requestTimeout) throws IOException {
        this.root = Path.of(uploadDir).toAbsolutePath().normalize();
        Files.createDirectories(root);
        this.client = HttpClient.newBuilder()
                .connectTimeout(requestTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.requestTimeout = requestTimeout;
    }

    /**
     * Returns copies of posts whose image URLs point to our own /uploads directory.
     * A failed image download leaves that post with no images so it can be retried on the next crawl.
     */
    Map<String, List<String>> storeImages(List<InstagramPost> posts) {
        Map<String, List<String>> stored = new LinkedHashMap<>();
        for (InstagramPost post : posts) {
            List<String> urls = new ArrayList<>();
            for (String imageUrl : post.imageUrls()) {
                download(imageUrl).ifPresent(urls::add);
            }
            stored.put(post.externalId(), List.copyOf(urls));
        }
        return stored;
    }

    private Optional<String> download(String imageUrl) {
        URI uri;
        try {
            uri = URI.create(imageUrl);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
            return Optional.empty();
        }

        try {
            HttpResponse<InputStream> response = client.send(HttpRequest.newBuilder(uri)
                            .GET()
                            .timeout(requestTimeout)
                            .header("User-Agent", "MoaGwaInstagramImageCrawler/1.0")
                            .build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200 || exceedsLimit(response)) {
                    return Optional.empty();
                }
                byte[] bytes = readAtMost(body, MAX_IMAGE_BYTES);
                String extension = extension(bytes);
                if (extension == null) {
                    return Optional.empty();
                }
                String filename = "instagram-" + UUID.randomUUID() + "." + extension;
                Files.write(root.resolve(filename), bytes);
                return Optional.of("/uploads/" + filename);
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Could not store an Instagram image locally");
            return Optional.empty();
        }
    }

    private static boolean exceedsLimit(HttpResponse<?> response) {
        return response.headers().firstValueAsLong("Content-Length")
                .stream()
                .anyMatch(length -> length > MAX_IMAGE_BYTES);
    }

    private static byte[] readAtMost(InputStream input, long maxBytes) throws IOException {
        byte[] buffer = new byte[8192];
        try (java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (output.size() + read > maxBytes) {
                    throw new IOException("Image exceeds maximum size");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static String extension(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
            return "jpg";
        }
        if (bytes.length >= 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
            return "png";
        }
        if (bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return "webp";
        }
        return null;
    }
}
