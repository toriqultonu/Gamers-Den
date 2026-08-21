package dev.gamersden.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * {@code /v3/api-docs} is a build input: the frontend generates its types from it and breaking
 * drift fails FE CI (ARCHITECTURE.md §2). Keep the document accurate, not decorative.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI gamersDenOpenApi(@Value("${spring.application.name:gamersden-backend}") String appName) {
        return new OpenAPI()
                .info(new Info()
                        .title("Gamer's Den API")
                        .version("v1")
                        .description("""
                                Single-venue console gaming cafe POS.
                                Money is integer BDT; times are ISO-8601 with offset (+06:00).
                                Every non-2xx response uses the ErrorResponse envelope."""))
                .servers(List.of(new Server().url(WebMvcConfig.API_BASE_PATH).description(appName)))
                // Every route needs the bearer token except the three /auth ones, which opt out
                // with @SecurityRequirements — so the FE client generates the header by default.
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"))
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
