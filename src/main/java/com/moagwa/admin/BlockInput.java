package com.moagwa.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record BlockInput(
        @NotBlank @Pattern(regexp = "TEXT|IMAGES") String type,
        @Size(max = 10000) String text,
        @Size(max = 10) List<@Pattern(regexp = "^(/uploads/[A-Za-z0-9._-]+|https://\\S+)$") String> images,
        @Pattern(regexp = "CAROUSEL|STACK") String layout) {
}
