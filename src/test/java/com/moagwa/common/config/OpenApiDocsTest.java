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

    /** 사진 업로드는 Swagger에서 파일을 고를 수 있게 multipart 본문으로 문서화 */
    @Test
    void 사진_업로드는_파일_선택이_가능한_multipart_본문이다() throws Exception {
        JsonNode paths = docs().get("paths");
        for (String path : List.of("/api/admin/uploads/images", "/api/market/uploads/images")) {
            JsonNode op = paths.get(path).get("post");
            JsonNode files = op.at("/requestBody/content/multipart~1form-data/schema/properties/files");
            assertThat(files.path("type").asText()).as(path).isEqualTo("array");
            assertThat(files.at("/items/format").asText()).as(path).isEqualTo("binary");
            assertThat(op.path("parameters").findValuesAsText("name")).as(path).doesNotContain("files");
        }
    }

    /** 클래스 이름이 같은 요청·응답 형식(Input, OrgInfo)이 하나로 합쳐져 다른 API의 형식으로 보이지 않게 함 */
    @Test
    void 이름이_같은_요청과_응답_형식이_API마다_따로_문서화된다() throws Exception {
        JsonNode root = docs();
        assertThat(bodyFields(root, "/api/me/reminders/{source}/{id}", "put")).containsExactly("enabled");
        assertThat(bodyFields(root, "/api/me/schedule-colors/{source}/{id}", "put")).containsExactlyInAnyOrder("type", "color");
        assertThat(bodyFields(root, "/api/me/schedules", "post")).contains("title", "at", "reminderEnabled");

        JsonNode schemas = root.at("/components/schemas");
        assertThat(fields(schemas.at("/AdminSessionOrganization"))).containsExactlyInAnyOrder("id", "name", "type");
        assertThat(fields(schemas.at("/AdminOrganization"))).contains("description", "verified");
        assertThat(schemas.has("Input")).isFalse();
        assertThat(schemas.has("OrgInfo")).isFalse();
    }

    private JsonNode docs() throws Exception {
        return json.readTree(mvc.perform(get("/v3/api-docs")).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static List<String> bodyFields(JsonNode root, String path, String method) {
        String ref = root.at("/paths").get(path).get(method)
                .at("/requestBody/content/application~1json/schema/$ref").asText();
        return fields(root.at("/components/schemas/" + ref.substring(ref.lastIndexOf('/') + 1)));
    }

    private static List<String> fields(JsonNode schema) {
        List<String> names = new ArrayList<>();
        schema.path("properties").fieldNames().forEachRemaining(names::add);
        return names;
    }

    @Test
    void 스웨거_화면도_로그인_없이_열린다() throws Exception {
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }
}
