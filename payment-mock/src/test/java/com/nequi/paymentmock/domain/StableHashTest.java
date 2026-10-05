package com.nequi.paymentmock.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * PM-IV-008 and PM-IV-012. The fixed vectors were computed outside Java (.NET SHA256 + BigInteger and
 * {@code sha256sum}), so they are an independent oracle for QA.
 */
class StableHashTest {

    @ParameterizedTest
    @CsvSource({
            "a-1, 41, pm-2f8fe63a6224321de5d0a24c",
            "pm-vector-1, 62, pm-a43806180e392ed2623f467f",
            "pm-vector-2, 39, pm-bf7e9f2114632fef0e9159a2",
            "pm-vector-3, 77, pm-1b7278c91ad64d493cb052e8",
            "pm-vector-4, 87, pm-3baa6ea59efd4657abb55336",
            "pm-vector-5, 96, pm-4f7d7ed902db392484f80747",
            "8a7d2c8e-5f0e-4c39-9d2e-1f4b2a6b7c11-1, 31, pm-848adf05c7f3da532632e44b",
            "ñandú-1, 92, pm-ca65729d3061bc28878d1e01"
    })
    void matchesIndependentVectors(String paymentAttemptId, int bucket, String providerReference) {
        assertThat(StableHash.declineBucket(paymentAttemptId)).isEqualTo(bucket);
        assertThat(StableHash.providerReference(paymentAttemptId)).isEqualTo(providerReference);
    }

    @Test
    void bucketEqualsTheUnsignedBigEndianPrefixModulo100ForManyIdentifiers() throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        for (int i = 0; i < 5_000; i++) {
            String id = "order-" + i + "-" + (i % 7 + 1);
            byte[] prefix = Arrays.copyOf(sha.digest(id.getBytes(StandardCharsets.UTF_8)), 8);
            int expected = new BigInteger(1, prefix).mod(BigInteger.valueOf(100)).intValue();
            assertThat(StableHash.declineBucket(id)).isEqualTo(expected).isBetween(0, 99);
        }
    }

    @Test
    void isRepeatableAndIndependentOfStringHashCode() {
        // "Aa" and "BB" share String.hashCode(); the stable hash distinguishes them.
        assertThat("Aa".hashCode()).isEqualTo("BB".hashCode());
        assertThat(StableHash.providerReference("Aa")).isNotEqualTo(StableHash.providerReference("BB"));
        for (int i = 0; i < 100; i++) {
            assertThat(StableHash.declineBucket("a-1")).isEqualTo(41);
        }
        assertThat(StableHash.providerReference("x")).matches("pm-[0-9a-f]{24}");
    }

    @Test
    void percentageFrontiers() {
        Map<String, Integer> buckets = Map.of("a-1", 41, "pm-vector-5", 96, "pm-vector-2", 39);
        buckets.forEach((id, bucket) -> {
            assertThat(OutcomeSelector.fallback(new Defaults(Outcome.APPROVED, 0), id)).isEqualTo(AuthorizationResult.APPROVED);
            assertThat(OutcomeSelector.fallback(new Defaults(Outcome.APPROVED, 100), id))
                    .isEqualTo(AuthorizationResult.declined(ReasonCode.PERCENTAGE_DECLINED));
            assertThat(OutcomeSelector.fallback(new Defaults(Outcome.APPROVED, bucket), id))
                    .as("bucket == percentage does not decline").isEqualTo(AuthorizationResult.APPROVED);
            assertThat(OutcomeSelector.fallback(new Defaults(Outcome.APPROVED, bucket + 1), id))
                    .as("bucket < percentage declines").isEqualTo(AuthorizationResult.declined(ReasonCode.PERCENTAGE_DECLINED));
        });
    }

    @Test
    void percentageOverEightThousandIdentifiersIsCloseToTheConfiguredShare() {
        int declined = 0;
        int total = 8_000;
        for (int i = 0; i < total; i++) {
            if (StableHash.declineBucket("load-" + i + "-1") < 25) {
                declined++;
            }
        }
        // Deterministic for these identifiers; a sanity check of the distribution, not a probabilistic test.
        assertThat(declined).isBetween(1_800, 2_200);
    }
}
