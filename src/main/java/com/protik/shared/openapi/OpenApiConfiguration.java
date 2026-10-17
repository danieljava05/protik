package com.protik.shared.openapi;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The spec is generated from the code (never hand-written) and covers only /v1. */
@Configuration(proxyBeanMethods = false)
class OpenApiConfiguration {

    @Bean
    OpenAPI protikOpenApi() {
        return new OpenAPI().info(new Info()
                .title("ProTik API")
                .version("v1")
                .description("Short-form video you control, built for expensive data and African languages.")
                .license(new License().name("Apache-2.0")
                        .url("https://www.apache.org/licenses/LICENSE-2.0.txt")));
    }
}
