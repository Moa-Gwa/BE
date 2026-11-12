package com.moagwa.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.moagwa.common.exception.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** 토스페이먼츠 결제 승인·취소 API. 시크릿 키가 없으면 결제 기능 비활성화 */
@Slf4j
@Component
class TossPaymentsClient {

    /** 승인 결과 중 검증에 쓰는 값. status DONE = 승인 완료 */
    record Payment(String paymentKey, String orderId, String status, long totalAmount) {
    }

    private final RestClient http;
    private final boolean enabled;

    TossPaymentsClient(@Value("${moagwa.toss.secret-key:}") String secretKey,
                       @Value("${moagwa.toss.base-url:https://api.tosspayments.com}") String baseUrl) {
        this.enabled = !secretKey.isBlank();
        // 인증 형식: Basic base64("시크릿키:")
        String basic = Base64.getEncoder().encodeToString((secretKey + ":").getBytes(StandardCharsets.UTF_8));
        this.http = RestClient.builder().baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    boolean enabled() {
        return enabled;
    }

    /** 결제 승인. 같은 주문의 재요청은 Idempotency-Key로 한 번만 처리됨 */
    Payment confirm(String paymentKey, String orderId, long amount) {
        JsonNode body = call("/v1/payments/confirm", "confirm-" + orderId,
                Map.of("paymentKey", paymentKey, "orderId", orderId, "amount", amount));
        return new Payment(body.path("paymentKey").asText(), body.path("orderId").asText(),
                body.path("status").asText(), body.path("totalAmount").asLong());
    }

    /** 전액 취소(환불) */
    void cancel(String paymentKey, String orderId, String reason) {
        call("/v1/payments/" + paymentKey + "/cancel", "cancel-" + orderId, Map.of("cancelReason", reason));
    }

    private JsonNode call(String path, String idempotencyKey, Map<String, Object> body) {
        if (!enabled) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "결제 기능이 설정되지 않았습니다.");
        }
        try {
            return http.post().uri(path).header("Idempotency-Key", idempotencyKey).body(body)
                    .retrieve().body(JsonNode.class);
        } catch (RestClientResponseException e) {
            // 오류 본문: {code, message} 또는 {error: {code, message}}
            JsonNode error = e.getResponseBodyAs(JsonNode.class);
            JsonNode detail = error == null ? null : error.has("error") ? error.path("error") : error;
            String code = detail == null ? "" : detail.path("code").asText();
            String message = detail == null ? "" : detail.path("message").asText();
            log.warn("Toss payment API failed: path={} status={} code={}", path, e.getStatusCode().value(), code);
            // 카드 거절(400)·결제 시간 만료(404) 등 결제 자체의 실패만 사용자에게 사유 표시. 키·권한 오류는 내부 오류로 처리
            int status = e.getStatusCode().value();
            if ((status == 400 || status == 404) && !message.isBlank()) {
                throw ApiException.badRequest(message);
            }
            throw new ApiException(HttpStatus.BAD_GATEWAY, "결제사 응답에 실패했습니다. 잠시 후 다시 시도해 주세요.");
        } catch (RestClientException e) {
            log.warn("Toss payment API unreachable: path={}", path, e);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "결제사 응답에 실패했습니다. 잠시 후 다시 시도해 주세요.");
        }
    }
}
