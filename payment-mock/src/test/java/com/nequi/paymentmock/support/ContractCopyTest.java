package com.nequi.paymentmock.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/** PM-IV-003: the versioned contract copy is literal; it is compared with the original when SPEC_REPO exists. */
class ContractCopyTest {

    static final String EXPECTED_SHA256 = "5360ffeed2334612b1b493ebef1224910218abd28b520f03bd97a2a7eec05124";
    static final Path DEFAULT_ORIGINAL =
            Path.of("D:/Nequi/PruebaeTecnicaNequi/architecture/payment-mock.openapi.v1.yaml");

    @Test
    void copyMatchesTheRecordedHashOfVersion100() throws Exception {
        assertThat(sha256(Contract.bytes())).isEqualTo(Contract.recordedSha256()).isEqualTo(EXPECTED_SHA256);
        assertThat(Contract.text()).contains("version: 1.0.0");
    }

    @Test
    void copyIsIdenticalToTheOriginalWhenTheSpecificationRepositoryIsAvailable() throws Exception {
        Path original = Path.of(System.getProperty("payment-mock.contract.original", DEFAULT_ORIGINAL.toString()));
        if (Files.isRegularFile(original)) {
            assertThat(sha256(Files.readAllBytes(original))).isEqualTo(sha256(Contract.bytes()));
        } else {
            // SPEC_REPO absent (for example in a container build): the recorded hash check above still applies.
            assertThat(Files.exists(original)).isFalse();
        }
    }

    static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
