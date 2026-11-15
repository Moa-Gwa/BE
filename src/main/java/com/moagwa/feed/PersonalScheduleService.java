package com.moagwa.feed;

import com.moagwa.common.exception.ApiException;
import com.moagwa.reminder.ReminderService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
class PersonalScheduleService {
    private final PersonalScheduleRepository schedules;
    private final ReminderService reminders;
    private final ScheduleColors colors;

    @Transactional
    long create(long user, PersonalScheduleController.Input input) {
        validate(input);
        long id = schedules.insert(user, input);
        if (Boolean.TRUE.equals(input.reminderEnabled())) reminders.set(user, "PERSONAL", id, true);
        if (input.colorPresent()) colors.set(user, "PERSONAL", "PERSONAL", id, input.color());
        return id;
    }

    @Transactional
    void update(long user, long id, PersonalScheduleController.Input input) {
        validate(input);
        if (schedules.update(user, id, input) == 0) throw ApiException.notFound("일정을 찾을 수 없습니다.");
        if (input.colorPresent()) colors.set(user, "PERSONAL", "PERSONAL", id, input.color());
        if (input.reminderEnabled() != null) reminders.set(user, "PERSONAL", id, input.reminderEnabled());
    }

    @Transactional
    void delete(long user, long id) {
        colors.set(user, "PERSONAL", "PERSONAL", id, null);
        reminders.remove(user, "PERSONAL", id);
        if (schedules.delete(user, id) == 0) throw ApiException.notFound("일정을 찾을 수 없습니다.");
    }

    private static void validate(PersonalScheduleController.Input input) {
        if (input.endAt() != null && !input.endAt().isAfter(input.at())) {
            throw ApiException.badRequest("종료 날짜·시간은 시작 날짜·시간 이후여야 합니다.");
        }
    }
}
