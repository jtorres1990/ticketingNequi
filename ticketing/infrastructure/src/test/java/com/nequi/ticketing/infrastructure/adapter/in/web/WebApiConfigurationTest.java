package com.nequi.ticketing.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

/**
 * Configuration of the HTTP adapter: approved defaults (ADR-032, ADR-035, IV-004, IV-005), validation of the
 * settings, the role mapping of {@code cognito:groups} and configurable claim names (SPK-022, IV-006 (c)).
 */
class WebApiConfigurationTest {

    private static TestTokenIssuer issuer;

    @BeforeAll
    static void startIssuer() {
        issuer = TestTokenIssuer.start(WebApiTestServer.NOW);
    }

    @AfterAll
    static void stopIssuer() {
        issuer.close();
    }

    @Test
    @DisplayName("ADR-032 ADR-033 access token settings: approved defaults and only issuer, key URL and clients from the environment")
    void accessTokenDefaults() {
        AccessTokenSettings settings = AccessTokenSettings.deployed(
                TestTokenIssuer.ISSUER, URI.create(issuer.jwkSetUri()), Set.of(TestTokenIssuer.CLIENT_ID));

        assertThat(settings.jwsAlgorithms()).containsExactly("RS256");
        assertThat(settings.clockSkew()).isEqualTo(Duration.ofSeconds(60));
        assertThat(settings.subjectClaim()).isEqualTo("sub");
        assertThat(settings.groupsClaim()).isEqualTo("cognito:groups");
        assertThat(settings.tokenUseClaim()).isEqualTo("token_use");
        assertThat(settings.expectedTokenUse()).isEqualTo("access");
        assertThat(settings.clientIdClaim()).isEqualTo("client_id");
    }

    @Test
    @DisplayName("ADR-032 access token settings reject symmetric algorithms, missing issuer, key URL, clients or claim names")
    void accessTokenSettingsValidation() {
        URI keys = URI.create(issuer.jwkSetUri());
        Set<String> clients = Set.of(TestTokenIssuer.CLIENT_ID);
        assertThatThrownBy(() -> settings(List.of("HS256"), clients, "cognito:groups"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings(List.of(), clients, "cognito:groups")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings(List.of("RS256"), Set.of(), "cognito:groups")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings(List.of("RS256"), Set.of(" "), "cognito:groups")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings(List.of("RS256"), clients, " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AccessTokenSettings.deployed(" ", keys, clients)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AccessTokenSettings.deployed(TestTokenIssuer.ISSUER, null, clients))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AccessTokenSettings(TestTokenIssuer.ISSUER, keys, clients, List.of("ES256"),
                Duration.ofSeconds(-1), "sub", "g", "t", "access", "c")).isInstanceOf(IllegalArgumentException.class);
        assertThat(new AccessTokenSettings(TestTokenIssuer.ISSUER, keys, clients, List.of("ES256", "PS256"),
                Duration.ZERO, "sub", "g", "t", "access", "c").jwsAlgorithms()).containsExactly("ES256", "PS256");
    }

    @Test
    @DisplayName("FR-018 ADR-032 cognito:groups mapping: ADMIN and CUSTOMER become authorities, other values are ignored")
    void groupsMapping() {
        assertThat(names(AccessTokens.authorities(List.of("CUSTOMER", "ADMIN", "viewer", 7, "CUSTOMER"))))
                .containsExactly("CUSTOMER", "ADMIN");
        assertThat(AccessTokens.authorities(List.of("admin", "Customer"))).isEmpty();
        assertThat(AccessTokens.authorities("ADMIN")).isEmpty();
        assertThat(AccessTokens.authorities(null)).isEmpty();
    }

    @Test
    @DisplayName("SPK-022 IV-006 claim names are configurable: groups, token type, client and subject read from other claims")
    void configurableClaimNames() {
        AccessTokenSettings settings = new AccessTokenSettings(TestTokenIssuer.ISSUER, URI.create(issuer.jwkSetUri()),
                Set.of("custom-client"), List.of("RS256"), Duration.ofSeconds(60), "username", "roles", "typ_use",
                "access_token", "azp");
        ReactiveJwtDecoder decoder = AccessTokens.decoder(settings, Clock.fixed(WebApiTestServer.NOW, ZoneOffset.UTC));
        Map<String, Object> claims = issuer.claims(TestTokenIssuer.CUSTOMER_A);
        claims.remove("cognito:groups");
        claims.remove("token_use");
        claims.remove("client_id");
        claims.put("roles", List.of("ADMIN"));
        claims.put("typ_use", "access_token");
        claims.put("azp", "custom-client");
        claims.put("username", "custom-subject");

        // Tokens are signed before the reactive pipeline: RSA signing may read the platform entropy source
        // (/dev/urandom on Linux), a blocking call that must not run on a non-blocking thread.
        String customToken = issuer.token(claims);
        String standardToken = issuer.token(TestTokenIssuer.CUSTOMER_A);
        StepVerifier.create(Mono.defer(() -> decoder.decode(customToken))
                        .flatMap(jwt -> AccessTokens.authenticationConverter(settings).convert(jwt))
                        .subscribeOn(Schedulers.parallel()))
                .assertNext(authentication -> {
                    assertThat(authentication.getName()).isEqualTo("custom-subject");
                    assertThat(names(authentication.getAuthorities())).containsExactly("ADMIN");
                })
                .verifyComplete();
        StepVerifier.create(Mono.defer(() -> decoder.decode(standardToken))
                        .subscribeOn(Schedulers.parallel()))
                .expectError(JwtException.class)
                .verify();
    }

    @Test
    @DisplayName("ADR-032 ADR-035 IV-004 IV-005 web settings: approved defaults and validation")
    void webSettings() {
        WebApiSettings deployed = WebApiSettings.DEPLOYED;
        assertThat(deployed.basePath()).isEqualTo("/api/v1");
        assertThat(deployed.path("/orders")).isEqualTo("/api/v1/orders");
        assertThat(deployed.unavailableRetryAfter()).isEqualTo(Duration.ofSeconds(1));
        assertThatThrownBy(() -> new WebApiSettings("api", 1, 1, Duration.ofSeconds(1), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WebApiSettings("/api/", 1, 1, Duration.ofSeconds(1), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WebApiSettings("/api", 0, 1, Duration.ofSeconds(1), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WebApiSettings("/api", 1, 0, Duration.ofSeconds(1), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WebApiSettings("/api", 1, 1, Duration.ZERO, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WebApiSettings("/api", 1, 1, Duration.ofSeconds(1), Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WebApiSettings("/api", 1, 1, null, Duration.ofSeconds(1)))
                .isInstanceOf(NullPointerException.class);
        assertThat(WebApiCodecs.strategies(deployed).messageWriters()).isNotEmpty();
    }

    private static AccessTokenSettings settings(List<String> algorithms, Set<String> clients, String groupsClaim) {
        return new AccessTokenSettings(TestTokenIssuer.ISSUER, URI.create(issuer.jwkSetUri()), clients, algorithms,
                Duration.ofSeconds(60), "sub", groupsClaim, "token_use", "access", "client_id");
    }

    private static List<String> names(java.util.Collection<? extends GrantedAuthority> authorities) {
        return authorities.stream().map(GrantedAuthority::getAuthority).toList();
    }
}
