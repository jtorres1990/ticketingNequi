package com.nequi.ticketing.infrastructure.adapter.in.web;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Validation contract of the access token (CMP-002; ADR-032, ADR-033, IV-006): the backend depends only on the
 * expected issuer, the URL of the signing keys and the names of the claims, configured separately because the
 * issuer seen from the clients and the key URL reachable from the container network may differ.
 * <ul>
 *   <li>{@code jwsAlgorithms}: accepted asymmetric signature algorithms (Cognito signs with RS256);</li>
 *   <li>{@code clockSkew}: tolerance applied to {@code exp} and {@code nbf}, 60 s (ADR-032);</li>
 *   <li>{@code tokenUseClaim} / {@code expectedTokenUse}: access token type ({@code token_use = access});</li>
 *   <li>{@code clientIdClaim} / {@code allowedClientIds}: allowed application clients ({@code client_id});</li>
 *   <li>{@code subjectClaim}: owner identity ({@code sub}, VAL-011); {@code groupsClaim}: roles
 *       ({@code cognito:groups}, FR-018).</li>
 * </ul>
 * Cognito access tokens carry no audience, so none is validated. Issuer, key URL and allowed clients have no
 * default: they come from the environment (INC-010, Platform).
 */
public record AccessTokenSettings(
        String issuer,
        URI jwkSetUri,
        Set<String> allowedClientIds,
        List<String> jwsAlgorithms,
        Duration clockSkew,
        String subjectClaim,
        String groupsClaim,
        String tokenUseClaim,
        String expectedTokenUse,
        String clientIdClaim) {

    public static final List<String> DEFAULT_JWS_ALGORITHMS = List.of("RS256");
    public static final Duration DEFAULT_CLOCK_SKEW = Duration.ofSeconds(60);
    public static final String DEFAULT_SUBJECT_CLAIM = "sub";
    public static final String DEFAULT_GROUPS_CLAIM = "cognito:groups";
    public static final String DEFAULT_TOKEN_USE_CLAIM = "token_use";
    public static final String DEFAULT_EXPECTED_TOKEN_USE = "access";
    public static final String DEFAULT_CLIENT_ID_CLAIM = "client_id";

    private static final Set<String> ASYMMETRIC = Set.of(
            "RS256", "RS384", "RS512", "ES256", "ES384", "ES512", "PS256", "PS384", "PS512");

    public AccessTokenSettings {
        requireText(issuer, "issuer");
        Objects.requireNonNull(jwkSetUri, "jwkSetUri");
        allowedClientIds = Set.copyOf(Objects.requireNonNull(allowedClientIds, "allowedClientIds"));
        if (allowedClientIds.isEmpty() || allowedClientIds.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("at least one allowed client identifier is required");
        }
        jwsAlgorithms = List.copyOf(Objects.requireNonNull(jwsAlgorithms, "jwsAlgorithms"));
        if (jwsAlgorithms.isEmpty() || !ASYMMETRIC.containsAll(jwsAlgorithms)) {
            throw new IllegalArgumentException("only asymmetric signature algorithms are accepted: " + jwsAlgorithms);
        }
        Objects.requireNonNull(clockSkew, "clockSkew");
        if (clockSkew.isNegative()) {
            throw new IllegalArgumentException("clockSkew must not be negative");
        }
        requireText(subjectClaim, "subjectClaim");
        requireText(groupsClaim, "groupsClaim");
        requireText(tokenUseClaim, "tokenUseClaim");
        requireText(expectedTokenUse, "expectedTokenUse");
        requireText(clientIdClaim, "clientIdClaim");
    }

    /** Approved defaults for every value except the environment-specific issuer, key URL and clients. */
    public static AccessTokenSettings deployed(String issuer, URI jwkSetUri, Set<String> allowedClientIds) {
        return new AccessTokenSettings(
                issuer,
                jwkSetUri,
                allowedClientIds,
                DEFAULT_JWS_ALGORITHMS,
                DEFAULT_CLOCK_SKEW,
                DEFAULT_SUBJECT_CLAIM,
                DEFAULT_GROUPS_CLAIM,
                DEFAULT_TOKEN_USE_CLAIM,
                DEFAULT_EXPECTED_TOKEN_USE,
                DEFAULT_CLIENT_ID_CLAIM);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
