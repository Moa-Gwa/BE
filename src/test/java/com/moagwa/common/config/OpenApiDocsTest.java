package com.moagwa.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.MockMvc;

/** 운영과 같이 문서를 켰을 때: 로그인 없이 열리고, 서버 주소는 상대 경로, local 전용 API는 빠짐 */
@SpringBootTest(properties = {"springdoc.api-docs.enabled=true", "springdoc.swagger-ui.enabled=true"})
@AutoConfigureMockMvc
class OpenApiDocsTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void 문서는_로그인_없이_열리고_서버_주소는_상대_경로다() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.servers[0].url").value("/"))
                .andExpect(jsonPath("$.paths['/api/categories'].get.summary").value("공지 카테고리 목록 조회"))
                .andExpect(jsonPath("$.paths['/api/crawled-notices/{id}'].get.description").isNotEmpty())
                .andExpect(jsonPath("$.paths['/api/admin/notices'].post.summary").value("관리자 공지 작성"))
                .andExpect(jsonPath("$.paths['/api/auth/dev-student']").doesNotExist());
    }

    /** 설명이 코드와 떨어진 JSON에 있어, API를 추가하거나 경로를 바꾸면 여기서 실패해 설명을 함께 고치게 함 */
    @Test
    void 모든_API에_제목과_설명이_있고_설명_파일에_없는_API를_가리키는_항목이_없다() throws Exception {
        JsonNode paths = json.readTree(mvc.perform(get("/v3/api-docs")).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).get("paths");
        Set<String> operations = new TreeSet<>();
        List<String> missing = new ArrayList<>();
        paths.fields().forEachRemaining(path -> path.getValue().fields().forEachRemaining(op -> {
            String key = op.getKey().toUpperCase() + " " + path.getKey();
            operations.add(key);
            if (op.getValue().path("summary").asText().isBlank() || op.getValue().path("description").asText().isBlank()) {
                missing.add(key);
            }
        }));
        Map<String, Object> docs;
        try (var input = new ClassPathResource("openapi-operation-docs.json").getInputStream()) {
            docs = json.readValue(input, new TypeReference<>() {});
        }

        assertThat(missing).as("설명이 없는 API").isEmpty();
        assertThat(docs.keySet()).as("없는 API를 가리키는 설명").isSubsetOf(operations);
    }

    @Test
    void 스웨거_화면도_로그인_없이_열린다() throws Exception {
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }
}
