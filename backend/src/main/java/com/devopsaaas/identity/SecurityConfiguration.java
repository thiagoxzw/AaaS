package com.devopsaaas.identity;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Deny by default: only login is public, every other API request needs a valid bearer token, and each
 * endpoint additionally declares the permission it requires with {@code @PreAuthorize}.
 */
@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties({JwtProperties.class, LoginThrottleProperties.class, BootstrapProperties.class})
class SecurityConfiguration {

    /**
     * Static key id. Without it, Nimbus uses the RFC 7638 thumbprint of the key as {@code kid}, which for an
     * HMAC key is a SHA-256 hash of the secret published in every token. A version label also prepares key
     * rotation (docs/fatias/01-autenticacao-ambientes-auditoria.md).
     */
    static final String KEY_ID = "hs256-v1";

    /**
     * Actuator endpoints are served only on the internal management port (docs/fatias/00-esqueleto.md), which
     * is not published outside the compose network; Prometheus scrapes it without credentials.
     */
    @Bean
    @Order(1)
    @SuppressFBWarnings(value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
            justification = "HttpSecurity.build() itself declares Exception")
    SecurityFilterChain managementEndpoints(HttpSecurity http) throws Exception {
        return http
                .securityMatcher(EndpointRequest.toAnyEndpoint())
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
                .csrf(SecurityConfiguration::disableCsrfForStatelessApi)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .build();
    }

    @Bean
    @Order(2)
    @SuppressFBWarnings(value = "THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION",
            justification = "HttpSecurity.build() itself declares Exception")
    SecurityFilterChain api(HttpSecurity http, UserReloadingJwtConverter converter,
            ProblemSecurityHandlers handlers) throws Exception {
        return http
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .csrf(SecurityConfiguration::disableCsrfForStatelessApi)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint(handlers.entryPoint())
                        .accessDeniedHandler(handlers.accessDeniedHandler()))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(handlers.entryPoint())
                        .accessDeniedHandler(handlers.accessDeniedHandler()))
                .build();
    }

    /**
     * CSRF protection targets browsers that send cookies automatically. This API is stateless and only accepts
     * bearer tokens in the Authorization header, which a cross-site request cannot attach
     * (docs/06-threat-model.md, B1). Revisit if the V6 dashboard authenticates with cookies.
     */
    @SuppressFBWarnings(value = "SPRING_CSRF_PROTECTION_DISABLED",
            justification = "Stateless bearer-token API without cookies (docs/06-threat-model.md, B1)")
    private static void disableCsrfForStatelessApi(CsrfConfigurer<HttpSecurity> csrf) {
        csrf.disable();
    }

    @Bean
    JwtDecoder jwtDecoder(JwtProperties properties) {
        // Algorithm pinned to HS256: unsigned tokens and other algorithms are rejected (TM-B1-01).
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(properties.secretKey())
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        return decoder;
    }

    @Bean
    JwtEncoder jwtEncoder(JwtProperties properties) {
        return NimbusJwtEncoder.withSecretKey(properties.secretKey())
                .algorithm(MacAlgorithm.HS256)
                .jwkPostProcessor(jwk -> jwk.keyID(KEY_ID))
                .build();
    }

    /** Delegating encoder ({bcrypt} prefix), so the hashing algorithm can change without resetting passwords. */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
