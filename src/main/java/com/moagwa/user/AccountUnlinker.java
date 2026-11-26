package com.moagwa.user;

import com.google.firebase.auth.FirebaseAuth;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

/** 탈퇴한 회원의 소셜 계정 연결을 끊음. 실패해도 탈퇴는 이미 끝났으므로 로그만 남김 */
@Slf4j
@Component
class AccountUnlinker {

    private final ObjectProvider<FirebaseAuth> firebaseAuth;
    private final String kakaoAdminKey;
    private final String kakaoApiUrl;

    AccountUnlinker(ObjectProvider<FirebaseAuth> firebaseAuth,
                    @Value("${moagwa.kakao.admin-key:}") String kakaoAdminKey,
                    @Value("${moagwa.kakao.api-url:https://kapi.kakao.com}") String kakaoApiUrl) {
        this.firebaseAuth = firebaseAuth;
        this.kakaoAdminKey = kakaoAdminKey;
        this.kakaoApiUrl = kakaoApiUrl;
    }

    void unlink(String provider, String providerUserId) {
        try {
            switch (provider) {
                case "GOOGLE" -> {
                    FirebaseAuth auth = firebaseAuth.getIfAvailable();
                    if (auth != null) auth.deleteUser(providerUserId);
                }
                case "KAKAO" -> {
                    if (kakaoAdminKey.isBlank()) return;
                    var form = new LinkedMultiValueMap<String, String>();
                    form.add("target_id_type", "user_id");
                    form.add("target_id", providerUserId);
                    RestClient.create().post().uri(kakaoApiUrl + "/v1/user/unlink")
                            .header("Authorization", "KakaoAK " + kakaoAdminKey)
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
                            .retrieve().toBodilessEntity();
                }
                default -> { }
            }
        } catch (Exception e) {
            log.warn("Social account unlink failed: provider={}", provider, e);
        }
    }
}
