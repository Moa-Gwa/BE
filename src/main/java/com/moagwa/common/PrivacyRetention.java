package com.moagwa.common;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 보유 기간이 끝난 개인정보 파기. 기간은 개인정보처리방침에 공개한 값과 같아야 함 */
@Component
@RequiredArgsConstructor
public class PrivacyRetention {
    private final JdbcTemplate jdbc;

    @Scheduled(cron = "0 30 4 * * *", zone = "Asia/Seoul")
    public void deleteExpired() {
        // 관리자 가입 신청서(담당자 이름, 메일, 연락처): 반려 30일, 승인 1년
        jdbc.update("""
                DELETE FROM admin_applications
                 WHERE (status = 'REJECTED' AND resolved_at <= now() - INTERVAL '30 days')
                    OR (status = 'APPROVED' AND resolved_at <= now() - INTERVAL '1 year')
                """);
        // 탈퇴한 회원의 결제 기록: 전자상거래법 보관 기간 5년
        jdbc.update("DELETE FROM group_buy_payments WHERE user_id IS NULL AND created_at <= now() - INTERVAL '5 years'");
    }
}
