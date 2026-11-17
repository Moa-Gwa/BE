package com.moagwa.chatbot;

import com.moagwa.auth.CurrentUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 학교 안내 챗봇 중계. 클러스터 안 챗봇 서비스의 SSE 답변을 학생에게 그대로 전달 */
@Tag(name = "챗봇", description = "학생 로그인 필요. 답변은 SSE(token, clarify, understood, retry, sources, done, error)")
@RestController
@RequestMapping("/api/me/chatbot")
@RequiredArgsConstructor
class ChatbotController {

    private final ChatbotService service;

    @PostMapping
    SseEmitter ask(@Valid @RequestBody ChatRequest req, Authentication auth) {
        return service.ask(CurrentUser.from(auth).id(), req);
    }

    @PostMapping("/{threadId}/resume")
    SseEmitter resume(@PathVariable String threadId, @Valid @RequestBody ResumeRequest req, Authentication auth) {
        return service.resume(CurrentUser.from(auth).id(), threadId, req);
    }

    @PostMapping("/feedback")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void feedback(@Valid @RequestBody FeedbackRequest req) {
        service.feedback(req);
    }

    record Turn(@NotNull @Pattern(regexp = "user|assistant") String role, @NotNull @Size(max = 2000) String content) {}

    record ChatRequest(@NotBlank @Size(max = 500) String question, @Size(max = 6) List<@Valid @NotNull Turn> history) {}

    record ResumeRequest(@NotNull @Pattern(regexp = "confirm|alternative|retry") String choice, Integer index) {}

    record FeedbackRequest(@NotNull @Pattern(regexp = "helpful|not_helpful") String rating,
                           @Size(max = 12) List<@NotBlank @Size(max = 1000) String> sourceUrls) {}
}
