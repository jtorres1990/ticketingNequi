package com.nequi.ticketing.infrastructure.adapter.in.web;

import static com.nequi.ticketing.infrastructure.adapter.in.web.AccessTokens.ADMIN;
import static com.nequi.ticketing.infrastructure.adapter.in.web.AccessTokens.CUSTOMER;

import java.util.Objects;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;

/**
 * CMP-002 security configuration (FR-018, FR-019, TC-016, NFR-010, ADR-032): stateless OAuth2 Resource Server
 * over the Cognito access token, authorization by role at the entrypoint and ownership in the use case.
 *
 * <table>
 *   <caption>Authority per operation (ADR-032, AV-005)</caption>
 *   <tr><th>Operation</th><th>Authority</th></tr>
 *   <tr><td>API-001 {@code POST /events}</td><td>ADMIN</td></tr>
 *   <tr><td>API-002 {@code GET /events}</td><td>ADMIN or CUSTOMER</td></tr>
 *   <tr><td>API-003 {@code GET /events/{eventId}/availability}</td><td>ADMIN or CUSTOMER</td></tr>
 *   <tr><td>API-004 {@code POST /orders}</td><td>CUSTOMER (an ADMIN only if also CUSTOMER)</td></tr>
 *   <tr><td>API-005 {@code GET /orders/{orderId}}</td><td>CUSTOMER and ownership (use case)</td></tr>
 *   <tr><td>API-006 {@code GET /events/{eventId}/provisioning}</td><td>ADMIN</td></tr>
 * </table>
 *
 * Without a token or with an invalid one: 401 {@code UNAUTHENTICATED}; with a valid token lacking the authority:
 * 403 {@code FORBIDDEN}, both as Problem Details without detail about the token. Anything outside the contract
 * is denied. No session, no CSRF (no cookies), no CORS, no saved requests; tokens are only read from the
 * {@code Authorization} header and never logged.
 */
public final class ApiSecurity {

    private ApiSecurity() {
    }

    public static SecurityWebFilterChain securityWebFilterChain(
            ServerHttpSecurity http,
            WebApiSettings settings,
            AccessTokenSettings tokens,
            ReactiveJwtDecoder decoder,
            ApiProblems problems) {
        Objects.requireNonNull(http, "http");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(tokens, "tokens");
        Objects.requireNonNull(decoder, "decoder");
        Objects.requireNonNull(problems, "problems");
        ServerAuthenticationEntryPoint unauthenticated =
                (exchange, error) -> problems.write(exchange, ApiRejection.unauthenticated());
        ServerAccessDeniedHandler forbidden = (exchange, error) -> problems.write(exchange, error);
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(ServerHttpSecurity.CorsSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .requestCache(cache -> cache.requestCache(NoOpServerRequestCache.getInstance()))
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers(HttpMethod.POST, settings.path("/events")).hasAuthority(ADMIN)
                        .pathMatchers(HttpMethod.GET, settings.path("/events")).hasAnyAuthority(ADMIN, CUSTOMER)
                        .pathMatchers(HttpMethod.GET, settings.path("/events/*/provisioning")).hasAuthority(ADMIN)
                        .pathMatchers(HttpMethod.GET, settings.path("/events/*/availability"))
                                .hasAnyAuthority(ADMIN, CUSTOMER)
                        .pathMatchers(HttpMethod.POST, settings.path("/orders")).hasAuthority(CUSTOMER)
                        .pathMatchers(HttpMethod.GET, settings.path("/orders/*")).hasAuthority(CUSTOMER)
                        .anyExchange().denyAll())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(unauthenticated)
                        .accessDeniedHandler(forbidden))
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .authenticationEntryPoint(unauthenticated)
                        .accessDeniedHandler(forbidden)
                        .jwt(jwt -> jwt
                                .jwtDecoder(decoder)
                                .jwtAuthenticationConverter(AccessTokens.authenticationConverter(tokens))))
                .build();
    }
}
