package com.moagwa.admin;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
class PublishScheduler {

    private final AdminNoticeService service;

    @Scheduled(fixedDelayString = "${moagwa.publish-check-interval:PT30S}")
    void publishDue() {
        int n = service.publishDue();
        if (n > 0) {
            log.info("published {} scheduled notices", n);
        }
    }
}
