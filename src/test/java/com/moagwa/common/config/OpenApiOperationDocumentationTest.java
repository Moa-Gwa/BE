package com.moagwa.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import org.junit.jupiter.api.Test;

class OpenApiOperationDocumentationTest {

    @Test
    void 같은_경로의_조회와_생성에_별도_설명을_적용한다() throws Exception {
        var config = new OpenApiConfig();
        var get = new Operation();
        var post = new Operation();
        var api = new OpenAPI().path("/api/admin/notices", new PathItem().get(get).post(post));

        config.operationDocumentation(new ObjectMapper()).customise(api);

        assertThat(get.getSummary()).contains("공지 목록");
        assertThat(get.getDescription()).contains("공지 목록을 조회");
        assertThat(post.getSummary()).isEqualTo("관리자 공지 작성");
        assertThat(post.getDescription()).contains("publishMode", "DRAFT");
    }
}
