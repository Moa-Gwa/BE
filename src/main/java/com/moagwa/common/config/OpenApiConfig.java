package com.moagwa.common.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.servers.Server;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springdoc.core.customizers.GlobalOpenApiCustomizer;
import org.springdoc.core.customizers.GlobalOperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.GetMapping;

/** Swagger UI(/swagger-ui/index.html). 로그인 API로 받은 세션 쿠키로 그대로 호출 */
@Configuration
class OpenApiConfig {

    /** 서버 주소를 상대 경로로 둬서 TLS 종료 프록시 뒤에서도 Try it out이 현재 주소(https)로 호출됨 */
    @Bean
    OpenAPI openApi() {
        return new OpenAPI().info(new Info().title("모아과 API").version("v1"))
                .servers(List.of(new Server().url("/")));
    }

    @Bean
    GlobalOpenApiCustomizer operationDocumentation(ObjectMapper mapper) throws IOException {
        Map<String, OperationDocumentation> documentation;
        try (var input = new ClassPathResource("openapi-operation-docs.json").getInputStream()) {
            documentation = mapper.readValue(input, new TypeReference<>() {});
        }

        return api -> api.getPaths().forEach((path, item) ->
                item.readOperationsMap().forEach((method, operation) -> {
                    var doc = documentation.get(method.name() + " " + path);
                    if (doc != null) {
                        operation.setSummary(doc.summary());
                        operation.setDescription(doc.description());
                    }
                }));
    }

    private record OperationDocumentation(String summary, String description) {}

    /** 쿠키 인증 변경 요청에 필요한 X-Requested-With 헤더를 기본값으로 채움(CookieCsrfFilter) */
    @Bean
    GlobalOperationCustomizer requestedWithHeader() {
        return (operation, handler) -> {
            if (!handler.hasMethodAnnotation(GetMapping.class)) {
                operation.addParametersItem(new HeaderParameter().name("X-Requested-With")
                        .schema(new StringSchema()._default("XMLHttpRequest")));
            }
            return operation;
        };
    }
}
