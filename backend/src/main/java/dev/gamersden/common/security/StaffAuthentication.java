package dev.gamersden.common.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

/**
 * The {@code Authentication} the JWT filter installs. Always already-authenticated — the bearer
 * token's signature is the proof, so there is no {@code AuthenticationProvider} to call.
 */
public class StaffAuthentication extends AbstractAuthenticationToken {

    private final transient StaffPrincipal principal;

    public StaffAuthentication(StaffPrincipal principal) {
        super(List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name())));
        this.principal = principal;
        setAuthenticated(true);
    }

    @Override
    public StaffPrincipal getPrincipal() {
        return principal;
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public String getName() {
        return String.valueOf(principal.id());
    }
}
