package com.ws.wsAgenticSecurityGateway.wsServer.config;

import com.ws.wsAgenticSecurityGateway.authConfig.service.AuthConfigService;
import com.ws.wsAgenticSecurityGateway.authConfig.service.DelegatingJwtDecoder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Dynamic Spring Security configuration for the WS MCP Gateway.
 *
 * <p>Uses a request-time auth mode check instead of startup-time {@code @ConditionalOnProperty}.
 * This allows runtime switching between oauth2 and none modes via the dashboard without restart.
 *
 * <p>Two filter chains (ordered):
 * <ol>
 *   <li>{@code Order(1)} — MCP endpoint chain: matches {@code /mcp/**} ONLY when auth mode is oauth2.
 *       Enforces JWT validation via {@link DelegatingJwtDecoder}.</li>
 *   <li>{@code Order(2)} — Catch-all permissive chain: handles everything else (admin APIs,
 *       health, well-known endpoints) and also handles {@code /mcp/**} when mode is none.</li>
 * </ol>
 *
 * <p>IdP-agnostic: works with Keycloak, Azure AD, Okta, Auth0 — any OIDC-compliant provider.
 */
@Slf4j
@Configuration
@EnableWebSecurity
public class GatewaySecurityConfig {

    /**
     * DelegatingJwtDecoder bean — the dynamic decoder that supports runtime hot-swapping.
     * Starts with a no-op decoder; AuthConfigService.initializeOnStartup() activates it
     * from DB config or env vars after ApplicationReadyEvent.
     */
    @Bean
    public DelegatingJwtDecoder delegatingJwtDecoder(AuthConfigService authConfigService) {
        // Start with no-op — will be replaced by AuthConfigService on startup
        JwtDecoder noOp = token -> {
            throw new org.springframework.security.oauth2.jwt.JwtException(
                    "JWT decoding not yet initialized — gateway starting up");
        };
        DelegatingJwtDecoder decoder = new DelegatingJwtDecoder(noOp);

        // Wire the decoder reference into AuthConfigService (breaks circular dependency)
        authConfigService.setDelegatingJwtDecoder(decoder);

        log.info("DelegatingJwtDecoder registered — will be activated on startup from DB/env config");
        return decoder;
    }

    /**
     * JwtDecoder bean alias — Spring Boot's auto-config looks for a JwtDecoder bean.
     * By providing DelegatingJwtDecoder as THE JwtDecoder, we prevent Spring from
     * trying to auto-create one (which would fail with "jwkSetUri cannot be empty").
     */
    @Bean
    public JwtDecoder jwtDecoder(DelegatingJwtDecoder delegatingJwtDecoder) {
        return delegatingJwtDecoder;
    }

    /**
     * MCP endpoint security chain — enforces JWT authentication for /mcp/* when auth mode is oauth2.
     * Uses a request-time matcher that consults AuthConfigService.getEffectiveMode().
     * When mode is "none", this chain does NOT match, and the permissive chain below handles it.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain mcpSecurityFilterChain(HttpSecurity http,
                                                       DelegatingJwtDecoder delegatingJwtDecoder,
                                                       AuthConfigService authConfigService) throws Exception {
        log.info("Configuring dynamic MCP security filter chain (request-time auth mode check)");

        http
            .securityMatcher(request -> {
                String path = request.getRequestURI();
                if (!path.startsWith("/mcp")) return false;
                return "oauth2".equals(authConfigService.getEffectiveMode());
            })
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt
                    .decoder(delegatingJwtDecoder)
                    .jwtAuthenticationConverter(jwtAuthenticationConverter()))
            );

        return http.build();
    }

    /**
     * Permissive catch-all security chain — handles all non-MCP requests and
     * MCP requests when auth mode is "none".
     */
    @Bean
    @Order(2)
    public SecurityFilterChain permissiveFilterChain(HttpSecurity http) throws Exception {
        log.info("Configuring permissive catch-all security filter chain");

        http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());

        return http.build();
    }

    /**
     * Converts JWT claims to Spring Security GrantedAuthority objects.
     * Extracts roles from multiple IdP-specific claim locations:
     * <ul>
     *   <li>{@code realm_access.roles} — Keycloak realm roles</li>
     *   <li>{@code resource_access.<client>.roles} — Keycloak client roles</li>
     *   <li>{@code roles} — Azure AD app roles</li>
     *   <li>{@code groups} — Okta group memberships</li>
     * </ul>
     */
    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new IdpAgnosticRoleConverter());
        return converter;
    }

    /**
     * IdP-agnostic role extraction from JWT claims.
     * Merges roles from Keycloak, Azure AD, and Okta claim structures.
     */
    static class IdpAgnosticRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

        @Override
        @SuppressWarnings("unchecked")
        public Collection<GrantedAuthority> convert(Jwt jwt) {
            Set<String> roles = new LinkedHashSet<>();

            // Keycloak realm roles: realm_access.roles
            Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
            if (realmAccess != null && realmAccess.get("roles") instanceof List<?> realmRoles) {
                realmRoles.forEach(r -> roles.add(String.valueOf(r)));
            }

            // Keycloak client roles: resource_access.<client_id>.roles
            Map<String, Object> resourceAccess = jwt.getClaimAsMap("resource_access");
            if (resourceAccess != null) {
                for (Map.Entry<String, Object> entry : resourceAccess.entrySet()) {
                    if (entry.getValue() instanceof Map<?, ?> clientAccess) {
                        Object clientRoles = clientAccess.get("roles");
                        if (clientRoles instanceof List<?> clientRoleList) {
                            clientRoleList.forEach(r ->
                                roles.add(entry.getKey() + "_" + r));
                        }
                    }
                }
            }

            // Azure AD: roles (flat list)
            List<String> azureRoles = jwt.getClaimAsStringList("roles");
            if (azureRoles != null) {
                roles.addAll(azureRoles);
            }

            // Okta: groups (flat list)
            List<String> oktaGroups = jwt.getClaimAsStringList("groups");
            if (oktaGroups != null) {
                roles.addAll(oktaGroups);
            }

            return roles.stream()
                .map(role -> new SimpleGrantedAuthority("ROLE_" + role))
                .collect(Collectors.toList());
        }
    }
}
