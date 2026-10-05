package com.nequi.ticketing.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * IV-003 (a): the web layer tests use a literal copy of {@code architecture/ticketing.openapi.v2.yaml} versioned in
 * the test resources, with its SHA-256 recorded next to it. The hash is computed over the content with LF line
 * endings, so that a checkout with CRLF conversion ({@code core.autocrlf=true}) does not change it. When the
 * specification repository is available (system property {@code ticketing.spec-repo}, environment variable
 * {@code TICKETING_SPEC_REPO} or the sibling directory {@code PruebaeTecnicaNequi}), the copy is also compared with
 * the original.
 */
class TicketingContractCopyTest {

    private static final String COPY = OpenApiContract.CONTRACT;
    private static final String HASH = COPY + ".sha256";

    @Test
    @DisplayName("IV-003 the versioned copy of ticketing.openapi.v2.yaml matches its recorded SHA-256")
    void copyMatchesRecordedHash() throws IOException {
        String recorded = resource(HASH).trim().split("\\s+")[0];

        assertThat(sha256(normalized(resource(COPY)))).isEqualTo(recorded);
        assertThat(resource(HASH)).contains("ticketing.openapi.v2.yaml");
    }

    @Test
    @DisplayName("IV-003 the versioned copy is identical to the original in the specification repository")
    void copyMatchesTheOriginal() throws IOException {
        Path original = specRepository().resolve("architecture").resolve("ticketing.openapi.v2.yaml");
        Assumptions.assumeTrue(Files.isRegularFile(original), "specification repository not available: " + original);

        assertThat(normalized(Files.readString(original, StandardCharsets.UTF_8)))
                .isEqualTo(normalized(resource(COPY)));
    }

    private static Path specRepository() {
        String configured = System.getProperty("ticketing.spec-repo", System.getenv("TICKETING_SPEC_REPO"));
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        // working directory of the module: <CODE_REPO>/ticketing/infrastructure
        return Path.of("").toAbsolutePath().resolve("../../../PruebaeTecnicaNequi").normalize();
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = Objects.requireNonNull(TicketingContractCopyTest.class.getResourceAsStream(name), name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String normalized(String content) {
        return content.replace("\r\n", "\n");
    }

    private static String sha256(String content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
