package com.ws.wsAgenticSecurityGateway.security;

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

@Slf4j
@Configuration
@EnableWebSecurity
public class GatewaySecurityConfig {

    @Bean
    public DelegatingJwtDecoder delegatingJwtDecoder(AuthConfigService authConfigService) {
        JwtDecoder noOp = token -> {
            throw new org.springframework.security.oauth2.jwt.JwtException(
                    "JWT decoding not yet initialized — gateway starting up");
        };
        DelegatingJwtDecoder decoder = new DelegatingJwtDecoder(noOp);

        authConfigService.setDelegatingJwtDecoder(decoder);

        log.info("DelegatingJwtDecoder registered — will be activated on startup from DB/env config");
        return decoder;
    }

    @Bean
    public JwtDecoder jwtDecoder(DelegatingJwtDecoder delegatingJwtDecoder) {
        return delegatingJwtDecoder;
    }

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

    @Bean
    @Order(2)
    public SecurityFilterChain permissiveFilterChain(HttpSecurity http) throws Exception {
        log.info("Configuring permissive catch-all security filter chain");

        http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());

        return http.build();
    }

    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(new IdpAgnosticRoleConverter());
        return converter;
    }

    static class IdpAgnosticRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

        @Override
        @SuppressWarnings("unchecked")
        public Collection<GrantedAuthority> convert(Jwt jwt) {
            Set<String> roles = new LinkedHashSet<>();

            Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
            if (realmAccess != null && realmAccess.get("roles") instanceof List<?> realmRoles) {
                realmRoles.forEach(r -> roles.add(String.valueOf(r)));
            }

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

            List<String> azureRoles = jwt.getClaimAsStringList("roles");
            if (azureRoles != null) {
                roles.addAll(azureRoles);
            }

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
