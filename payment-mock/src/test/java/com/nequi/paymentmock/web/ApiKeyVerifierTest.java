package com.nequi.paymentmock.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nequi.paymentmock.config.PaymentMockProperties;
import org.junit.jupiter.api.Test;

class ApiKeyVerifierTest {

    private final ApiKeyVerifier verifier = new ApiKeyVerifier(new PaymentMockProperties("s3cr3t-key"));

    @Test
    void acceptsOnlyTheExactKey() {
        assertThat(verifier.matches("s3cr3t-key")).isTrue();
        assertThat(verifier.matches("s3cr3t-kez")).isFalse();
        assertThat(verifier.matches("s3cr3t-key ")).isFalse();
        assertThat(verifier.matches("S3CR3T-KEY")).isFalse();
        assertThat(verifier.matches("s3cr3t")).isFalse();
        assertThat(verifier.matches("")).isFalse();
        assertThat(verifier.matches(null)).isFalse();
    }

    @Test
    void propertiesRequireANonBlankKeyAndNeverPrintIt() {
        assertThatThrownBy(() -> new PaymentMockProperties(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentMockProperties("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PaymentMockProperties("   ")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PAYMENT_MOCK_API_KEY");
        assertThat(new PaymentMockProperties("s3cr3t-key").toString()).doesNotContain("s3cr3t-key");
    }
}
