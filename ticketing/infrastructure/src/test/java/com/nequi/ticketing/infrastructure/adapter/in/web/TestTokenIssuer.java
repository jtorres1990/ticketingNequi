package com.nequi.ticketing.infrastructure.adapter.in.web;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * Simulated issuer of Cognito-shaped access tokens for the web layer tests (ADR-033: "tokens simulados por
 * el soporte de pruebas, sin contenedor"). It signs RS256 tokens with a key generated per test class and
 * serves the JWK set from a Reactor Netty server on a separate URL, so that the expected issuer and the key
 * URL are configured independently (ADR-033 consequences). Claims follow the ADR-033 contract: {@code sub},
 * {@code cognito:groups}, {@code token_use}, {@code client_id}, {@code iss}, {@code exp}, {@code iat}; no
 * audience. The five deterministic identities of ADR-033 are available as constants.
 */
public final class TestTokenIssuer implements AutoCloseable {

    public static final String ISSUER = "https://cognito-idp.local.test/ticketing-pool";
    public static final String CLIENT_ID = "ticketing-web-client";
    static final String OTHER_CLIENT_ID = "unknown-client";

    public static final Identity ADMIN = new Identity("admin", List.of("ADMIN"));
    public static final Identity CUSTOMER_A = new Identity("customer-a", List.of("CUSTOMER"));
    public static final Identity CUSTOMER_B = new Identity("customer-b", List.of("CUSTOMER"));
    public static final Identity ADMIN_CUSTOMER = new Identity("admin-customer", List.of("ADMIN", "CUSTOMER"));
    public static final Identity NO_GROUPS = new Identity("no-groups", List.of("viewer"));

    private final RSAKey signingKey;
    private final RSAKey foreignKey;
    private final DisposableServer jwksServer;
    private final Instant now;

    private TestTokenIssuer(Instant now) {
        this.now = now;
        this.signingKey = rsaKey("ticketing-test-key");
        this.foreignKey = rsaKey("ticketing-test-key");
        String jwks = new JWKSet(signingKey.toPublicJWK()).toString();
        this.jwksServer = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .route(routes -> routes.get("/.well-known/jwks.json", (request, response) -> response
                        .header("Content-Type", "application/json")
                        .sendString(reactor.core.publisher.Mono.just(jwks))))
                .bindNow();
    }

    public static TestTokenIssuer start(Instant now) {
        return new TestTokenIssuer(now);
    }

    public String jwkSetUri() {
        return "http://127.0.0.1:" + jwksServer.port() + "/.well-known/jwks.json";
    }

    public String token(Identity identity) {
        return sign(claims(identity), signingKey);
    }

    String token(Map<String, Object> claims) {
        return sign(claims, signingKey);
    }

    /** Same key identifier as the published key, but a different private key. */
    String tokenSignedByForeignKey(Identity identity) {
        return sign(claims(identity), foreignKey);
    }

    String hs256Token(Identity identity) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID(signingKey.getKeyID()).build(),
                    claimsSet(claims(identity)));
            jwt.sign(new MACSigner("a-shared-secret-of-at-least-thirty-two-bytes!".getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    String unsignedToken(Identity identity) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder.encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8));
        String payload = encoder.encodeToString(claimsSet(claims(identity)).toString().getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".";
    }

    /** Standard ADR-033 access token claims of {@code identity}, valid for one hour from {@code now}. */
    Map<String, Object> claims(Identity identity) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", identity.subject());
        claims.put("cognito:groups", identity.groups());
        claims.put("token_use", "access");
        claims.put("client_id", CLIENT_ID);
        claims.put("iss", ISSUER);
        claims.put("iat", now.minus(Duration.ofMinutes(1)));
        claims.put("exp", now.plus(Duration.ofHours(1)));
        claims.put("jti", UUID.nameUUIDFromBytes(identity.subject().getBytes(StandardCharsets.UTF_8)).toString());
        claims.put("username", identity.subject());
        return claims;
    }

    @Override
    public void close() {
        jwksServer.disposeNow();
    }

    private static String sign(Map<String, Object> claims, RSAKey key) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                    claimsSet(claims));
            jwt.sign(new RSASSASigner(key.toRSAPrivateKey()));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JWTClaimsSet claimsSet(Map<String, Object> claims) {
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder();
        claims.forEach((name, value) -> builder.claim(name, value instanceof Instant instant ? Date.from(instant) : value));
        return builder.build();
    }

    private static RSAKey rsaKey(String keyId) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate())
                    .keyID(keyId)
                    .build();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public record Identity(String subject, List<String> groups) {
    }
}
