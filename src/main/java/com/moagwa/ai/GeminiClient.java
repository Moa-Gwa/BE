package com.moagwa.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Gemini API 호출. 정해진 JSON 스키마로만 답을 받는다(구조화 출력).
 * API 키가 없으면 비활성화되어 호출하는 기능만 꺼진다.
 */
@Slf4j
@Component
public class GeminiClient {

    /** 요청 한도 초과·과부하·시간 초과 등 일시적 오류. 호출하는 쪽은 이번 묶음을 멈추고 다음 주기에 다시 시도 */
    public static class UnavailableException extends RuntimeException {
        public UnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final RestClient http;
    private final ObjectMapper json;
    private final String model;
    private final boolean enabled;

    GeminiClient(@Value("${moagwa.gemini.api-key:}") String apiKey,
                 @Value("${moagwa.gemini.model:gemini-3.5-flash-lite}") String model,
                 @Value("${moagwa.gemini.base-url:https://generativelanguage.googleapis.com}") String baseUrl,
                 @Value("${moagwa.gemini.timeout:PT60S}") Duration timeout, ObjectMapper json) {
        this.enabled = !apiKey.isBlank();
        this.model = model;
        this.json = json;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(timeout);
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(factory)
                .defaultHeader("x-goog-api-key", apiKey).build();
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * 프롬프트를 보내고 스키마에 맞는 JSON을 돌려받는다.
     *
     * @param schema Gemini responseSchema(OpenAPI 부분집합)
     */
    public JsonNode generateJson(String prompt, Map<String, Object> schema) {
        if (!enabled) {
            throw new IllegalStateException("Gemini API key is not configured");
        }
        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", prompt)))),
                "generationConfig", Map.of("temperature", 0, "responseMimeType", "application/json",
                        "responseSchema", schema));
        JsonNode response;
        try {
            response = http.post().uri("/v1beta/models/{model}:generateContent", model)
                    .body(body).retrieve().body(JsonNode.class);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 429 || status >= 500) {
                throw new UnavailableException("Gemini unavailable: status=" + status, e);
            }
            log.warn("Gemini API failed: status={}", status);
            throw e;
        } catch (ResourceAccessException e) {
            throw new UnavailableException("Gemini unreachable or timed out", e); // 연결 실패·응답 시간 초과
        } catch (RestClientException e) {
            if (e.getCause() instanceof IOException) {
                throw new UnavailableException("Gemini response interrupted", e); // 응답 본문을 읽다 끊긴 경우
            }
            throw e;
        }
        String text = response == null ? "" : response.at("/candidates/0/content/parts/0/text").asText("");
        if (text.isBlank()) {
            throw new IllegalStateException("Gemini returned no content: "
                    + (response == null ? "null" : response.at("/candidates/0/finishReason").asText()));
        }
        try {
            return json.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException("Gemini returned invalid JSON", e);
        }
    }
}
