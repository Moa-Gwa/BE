package com.moagwa.reminder;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class ReminderScheduler {
    private final ReminderService service;
    @Scheduled(fixedDelayString="${moagwa.reminder.interval:PT1M}")
    void tick() { service.sendDue(); }
}
