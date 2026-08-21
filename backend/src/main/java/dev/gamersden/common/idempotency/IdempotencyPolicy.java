package dev.gamersden.common.idempotency;

import dev.gamersden.common.config.WebMvcConfig;
import org.springframework.util.AntPathMatcher;

import java.util.List;
import java.util.Objects;

/**
 * Which routes demand an {@code Idempotency-Key} — the list is fixed by
 * {@code docs/api-contract.md} §1 / ARCHITECTURE.md §5.2 and is deliberately code, not config:
 * a deployment must not be able to switch idempotency off for the money path.
 *
 * <p>The contract writes the wallet routes as {@code /wallet/*}; §2 spells the actual paths
 * {@code /members/{id}/wallet/topup|redeem-points}, which is what is matched here.
 */
public final class IdempotencyPolicy {

    private static final AntPathMatcher MATCHER = new AntPathMatcher();
    private static final String API = WebMvcConfig.API_BASE_PATH;

    private static final List<Route> CONTRACT_ROUTES = List.of(
            new Route("POST", API + "/payments"),
            new Route("POST", API + "/print-jobs"),
            new Route("POST", API + "/sessions/*/blocks"),
            new Route("POST", API + "/members/*/wallet/*"),
            new Route("POST", API + "/tournaments/*/entries"),
            new Route("POST", API + "/bookings"),
            new Route("POST", API + "/bookings/*/cancel"),
            new Route("POST", API + "/play-tickets"));

    private final List<Route> routes;

    public IdempotencyPolicy(List<Route> routes) {
        this.routes = List.copyOf(routes);
    }

    /** The guarded set every profile runs with. */
    public static IdempotencyPolicy contractDefaults() {
        return new IdempotencyPolicy(CONTRACT_ROUTES);
    }

    public boolean guards(String method, String path) {
        String normalised = normalise(path);
        return routes.stream().anyMatch(route -> route.matches(method, normalised));
    }

    public List<Route> routes() {
        return routes;
    }

    /** Trailing slashes are not significant — {@code /bookings/} is still {@code /bookings}. */
    private static String normalise(String path) {
        if (path.length() > 1 && path.endsWith("/")) {
            return path.substring(0, path.length() - 1);
        }
        return path;
    }

    public record Route(String method, String pattern) {

        public Route {
            Objects.requireNonNull(method);
            Objects.requireNonNull(pattern);
        }

        boolean matches(String requestMethod, String requestPath) {
            return method.equalsIgnoreCase(requestMethod) && MATCHER.match(pattern, requestPath);
        }
    }
}
