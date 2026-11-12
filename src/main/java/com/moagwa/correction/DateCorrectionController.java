package com.moagwa.correction;

import com.moagwa.auth.CurrentUser;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "날짜 교정", description = "크롤링 공지 날짜 교정 요청(학생)과 검토(운영자)")
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
class DateCorrectionController {

    private final DateCorrectionService service;

    @PostMapping("/crawled-notices/{id}/date-corrections")
    @ResponseStatus(HttpStatus.CREATED)
    DateCorrectionView.Created request(@PathVariable long id, @Valid @RequestBody DateCorrectionRequest req,
                                       Authentication auth) {
        return service.request(id, CurrentUser.from(auth).id(), req);
    }

    @GetMapping("/admin/date-corrections")
    List<DateCorrectionView> list(@RequestParam(defaultValue = "PENDING") String status) {
        return service.list(status);
    }

    @PostMapping("/admin/date-corrections/{id}/apply")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void apply(@PathVariable long id, @RequestBody DateCorrectionApply body, Authentication auth) {
        service.apply(id, CurrentUser.from(auth).id(), body);
    }

    @PostMapping("/admin/date-corrections/{id}/reject")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void reject(@PathVariable long id, Authentication auth) {
        service.reject(id, CurrentUser.from(auth).id());
    }
}
