package dev.gamersden.common.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.gamersden.common.config.WebMvcConfig;
import dev.gamersden.common.error.ErrorResponseWriter;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires {@link IdempotencyFilter} into the servlet chain <em>behind</em> Spring Security, so the
 * 401 for an unauthenticated caller still wins over the 400 for a missing key (§5.2).
 */
@Configuration
public class IdempotencyConfig {

    /** Just after {@code SecurityProperties.DEFAULT_FILTER_ORDER}: security runs, then this. */
    static final int FILTER_ORDER = SecurityProperties.DEFAULT_FILTER_ORDER + 10;

    /** Overridable in tests with a {@code @Primary} bean; production runs the contract list. */
    @Bean
    public IdempotencyPolicy idempotencyPolicy() {
        return IdempotencyPolicy.contractDefaults();
    }

    @Bean
    public FilterRegistrationBean<IdempotencyFilter> idempotencyFilter(IdempotencyPolicy policy,
                                                                       IdempotencyStore store,
                                                                       ErrorResponseWriter errors,
                                                                       ObjectMapper mapper) {
        FilterRegistrationBean<IdempotencyFilter> registration = new FilterRegistrationBean<>(
                new IdempotencyFilter(policy, store, errors, mapper));
        registration.addUrlPatterns(WebMvcConfig.API_BASE_PATH + "/*");
        registration.setOrder(FILTER_ORDER);
        return registration;
    }
}
