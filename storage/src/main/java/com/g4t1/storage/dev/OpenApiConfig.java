package com.g4t1.storage.dev;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

// Adds Swagger's Authorize button, so a pasted token is sent on every call it makes
@Configuration
@Profile("dev")
public class OpenApiConfig {

    @Bean
    OpenAPI storageOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Storage Management")
                        .description("Local testing only. Authorize with a user token, or a service token for /internal."))
                .components(new Components().addSecuritySchemes("bearer",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearer"));
    }
}
