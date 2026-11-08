package com.moagwa.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import org.springdoc.core.customizers.GlobalOperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.GetMapping;

/** Swagger UI(/swagger-ui/index.html). 로그인 API로 받은 세션 쿠키로 그대로 호출 */
@Configuration
class OpenApiConfig {

    @Bean
    OpenAPI openApi() {
        return new OpenAPI().info(new Info().title("모아과 API").version("v1"));
    }

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
