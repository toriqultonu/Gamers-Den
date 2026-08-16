package dev.gamersden.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Prefixes every {@code @RestController} in {@code dev.gamersden} with the API base path, so
 * controllers declare bare paths ({@code /sessions}) and actuator plus {@code /v3/api-docs} stay
 * at the root.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    public static final String API_BASE_PATH = "/api/v1";

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        configurer.addPathPrefix(API_BASE_PATH,
                type -> type.isAnnotationPresent(RestController.class)
                        && type.getPackageName().startsWith("dev.gamersden"));
    }
}
