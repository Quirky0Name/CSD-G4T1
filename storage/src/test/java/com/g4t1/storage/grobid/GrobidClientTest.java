package com.g4t1.storage.grobid;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GrobidClientTest {

    @Test
    void parsesDoiAndTitleFromBibtex() {
        String bibtex = """
                @article{-1,
                  author = {Mehra, Mandeep R and Desai, Sapan S},
                  title = {Hydroxychloroquine or chloroquine with or without a macrolide},
                  doi = {10.1016/S0140-6736(20)31180-6},
                  journal = {The Lancet}
                }
                """;

        var header = GrobidClient.parse(bibtex);

        assertThat(header.doi()).isEqualTo("10.1016/S0140-6736(20)31180-6");
        assertThat(header.title()).isEqualTo("Hydroxychloroquine or chloroquine with or without a macrolide");
    }

    @Test
    void missingFieldsComeBackNull() {
        var header = GrobidClient.parse("@article{-1,\n  author = {Someone}\n}\n");

        assertThat(header.doi()).isNull();
        assertThat(header.title()).isNull();
    }
}
