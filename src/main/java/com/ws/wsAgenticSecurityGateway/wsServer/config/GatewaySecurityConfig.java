package com.ws.wsAgenticSecurityGateway.wsServer.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Spring Security configuration for the WS MCP Gateway.
 *
 * <p>When {@code ws.gateway.auth.mode=oauth2}, the gateway acts as an OAuth2 Resource Server:
 * it validates JWT tokens against the IdP's JWKS endpoint before any request reaches
 * the MCP servlet. Invalid/expired/missing tokens get a 401 with WWW-Authenticate header.
 *
 * <p>When {@code ws.gateway.auth.mode=none}, all endpoints are open (dev/stdio mode).
 *
 * <p>IdP-agnostic: works with Keycloak, Azure AD, Okta, Auth0 — any OIDC-compliant provider.
 * Roles are extracted from both {@code realm_access.roles} (Keycloak realm roles)
 * and {@code resource_access.<client>.roles} (Keycloak client roles), plus
 * {@code roles} (Azure AD) and {@code groups} (Okta).
 */
@Slf4j
@Configuration
@EnableWebSecurity
public class GatewaySecurityConfig {

    @Value("${ws.gateway.auth.mode:none}")
    private String authMode;

    /**
     * OAuth2 security filter chain — active when auth mode is oauth2.
     * MCP endpoints require a valid JWT; admin/health/monitoring endpoints are open.
     */
    @Bean
    @ConditionalOnProperty(name = "ws.gateway.auth.mode", havingValue = "oauth2")
    public SecurityFilterChain oauth2SecurityFilterChain(HttpSecurity http) throws Exception {
        log.info("🔐 Configuring OAuth2 Resource Server security (mode=oauth2)");

        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/.well-known/**").permitAll()
                .requestMatchers("/api/admin/**").permitAll()
                .requestMatchers("/api/gateway/health").permitAll()
                .requestMatchers("/api/mcp/**").permitAll()
                .requestMatchers("/mcp/**").authenticated()
                .anyRequest().permitAll()
            )
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
            );

        return http.build();
    }

    /**
     * Permissive security filter chain — active when auth mode is none (dev/stdio).
     */
    @Bean
    @ConditionalOnProperty(name = "ws.gateway.auth.mode", havingValue = "none", matchIfMissing = true)
    public SecurityFilterChain noAuthSecurityFilterChain(HttpSecurity http) throws Exception {
        log.info("🔓 Configuring permissive security (mode=none)");

        http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());

        return http.build();
    }

    /**
     * No-op JwtDecoder when auth is disabled.
     * Prevents Spring Boot's OAuth2ResourceServerJwtConfiguration from failing
     * with "jwkSetUri cannot be empty" when no JWKS URI is configured.
     * Spring's auto-config is @ConditionalOnMissingBean, so this takes precedence.
     */
    @Bean
    @ConditionalOnProperty(name = "ws.gateway.auth.mode", havingValue = "none", matchIfMissing = true)
    public JwtDecoder noOpJwtDecoder() {
        log.debug("No-op JwtDecoder registered (auth mode=none)");
        return token -> {
            throw new UnsupportedOperationException("JWT decoding is disabled (auth.mode=none)");
        };
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
