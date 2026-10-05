package com.nequi.ticketing.infrastructure.adapter.in.web;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;

/**
 * Access token validation and role mapping of the Resource Server (CMP-002; FR-018, TC-016, ADR-032,
 * ADR-033; verified in SPK-020, contract of SPK-022 / IV-006 (c)).
 * <ul>
 *   <li>signature with the configured asymmetric algorithms and the keys of the JWK set URL;</li>
 *   <li>issuer equal to the configured one; {@code exp} required and checked with the configured tolerance
 *       (also {@code nbf} when present); access token type; allowed client; non-blank subject;</li>
 *   <li>each value of the groups claim equal to {@code ADMIN} or {@code CUSTOMER} becomes that authority;
 *       other values are ignored; without recognised groups there are no authorities.</li>
 * </ul>
 * No session is kept and no token is ever logged.
 */
public final class AccessTokens {

    public static final String ADMIN = "ADMIN";
    public static final String CUSTOMER = "CUSTOMER";

    private static final Set<String> RECOGNISED_GROUPS = Set.of(ADMIN, CUSTOMER);

    private AccessTokens() {
    }

    /** Reactive decoder that fetches the signing keys from {@code jwkSetUri} without blocking. */
    public static ReactiveJwtDecoder decoder(AccessTokenSettings settings, Clock clock) {
        Objects.requireNonNull(settings, "settings");
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(settings.jwkSetUri().toString())
                .jwsAlgorithms(algorithms -> settings.jwsAlgorithms().stream()
                        .map(SignatureAlgorithm::from)
                        .forEach(algorithms::add))
                .build();
        decoder.setJwtValidator(validator(settings, clock));
        return decoder;
    }

    static OAuth2TokenValidator<Jwt> validator(AccessTokenSettings settings, Clock clock) {
        JwtTimestampValidator timestamps = new JwtTimestampValidator(settings.clockSkew());
        timestamps.setClock(Objects.requireNonNull(clock, "clock"));
        timestamps.setAllowEmptyExpiryClaim(false);
        List<OAuth2TokenValidator<Jwt>> validators = List.of(
                timestamps,
                new JwtIssuerValidator(settings.issuer()),
                new JwtClaimValidator<Object>(settings.tokenUseClaim(), settings.expectedTokenUse()::equals),
                new JwtClaimValidator<Object>(settings.clientIdClaim(),
                        client -> client != null && settings.allowedClientIds().contains(client)),
                new JwtClaimValidator<Object>(settings.subjectClaim(),
                        subject -> subject instanceof String text && !text.isBlank()));
        return new DelegatingOAuth2TokenValidator<>(validators);
    }

    /** Authentication whose name is the subject (owner, VAL-011) and whose authorities come from the groups. */
    public static Converter<Jwt, Mono<AbstractAuthenticationToken>> authenticationConverter(AccessTokenSettings settings) {
        Objects.requireNonNull(settings, "settings");
        return jwt -> Mono.just(new JwtAuthenticationToken(
                jwt, authorities(jwt.getClaims().get(settings.groupsClaim())), jwt.getClaimAsString(settings.subjectClaim())));
    }

    static Collection<GrantedAuthority> authorities(Object groupsClaim) {
        if (!(groupsClaim instanceof Collection<?> groups)) {
            return List.of();
        }
        Set<String> recognised = new LinkedHashSet<>();
        for (Object group : groups) {
            if (group instanceof String name && RECOGNISED_GROUPS.contains(name)) {
                recognised.add(name);
            }
        }
        List<GrantedAuthority> authorities = new ArrayList<>(recognised.size());
        recognised.forEach(name -> authorities.add(new SimpleGrantedAuthority(name)));
        return List.copyOf(authorities);
    }
}
