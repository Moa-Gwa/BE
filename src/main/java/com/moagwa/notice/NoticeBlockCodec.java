package com.moagwa.notice;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NoticeBlockCodec {

    private final ObjectMapper mapper;

    public String toJson(List<NoticeBlock> blocks) {
        try {
            return mapper.writeValueAsString(blocks);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize notice blocks", e);
        }
    }

    /** Returns null when the notice has no block body (legacy plain-text notices). */
    public List<NoticeBlock> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(json, new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            return null; // a corrupt body falls back to description + images instead of failing the page
        }
    }
}
