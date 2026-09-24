package com.g4t1.storage.metadata;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MetadataClientTest {

    @Test
    void normalizeDoiStripsPrefixesAndLowercases() {
        assertThat(MetadataClient.normalizeDoi("https://doi.org/10.1016/S0140-6736(20)31180-6"))
                .isEqualTo("10.1016/s0140-6736(20)31180-6");
        assertThat(MetadataClient.normalizeDoi("doi:10.1/ABC")).isEqualTo("10.1/abc");
        assertThat(MetadataClient.normalizeDoi(" 10.1/x ")).isEqualTo("10.1/x");
    }

    @Test
    void normalizeDoiTreatsBlankAsMissing() {
        assertThat(MetadataClient.normalizeDoi(null)).isNull();
        assertThat(MetadataClient.normalizeDoi("  ")).isNull();
    }
}
