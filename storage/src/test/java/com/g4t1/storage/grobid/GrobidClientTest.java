package com.g4t1.storage.grobid;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

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

    // uploads rely on this: GROBID down means no header data, not a failed upload
    @Test
    void grobidFailingGivesNoHeaderInsteadOfThrowing() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        GrobidClient client = new GrobidClient(builder, "http://grobid.test");
        server.expect(requestTo("http://grobid.test/api/processHeaderDocument"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThat(client.extractHeader("%PDF-1.7".getBytes())).isEmpty();
        server.verify();
    }
}
