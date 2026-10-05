package com.moagwa.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/** Create/update payload. {@code publishMode}: DRAFT, NOW, SCHEDULED; null on update keeps the current state. */
public record NoticeRequest(
        @NotBlank @Size(max = 40) String title,
        @NotBlank @Size(max = 10000) String description,
        @NotEmpty List<Long> categoryIds,
        @Size(max = 10) List<@Pattern(regexp = "^(/uploads/[A-Za-z0-9._-]+|https://\\S+)$") String> imageUrls,
        Instant eventStartAt,
        Instant eventEndAt,
        Instant deadlineAt,
        @Size(max = 200) String location,
        @Pattern(regexp = "^$|^https?://\\S+$") String externalLink,
        boolean urgent,
        String publishMode,
        Instant publishAt) {
}
