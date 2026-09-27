package com.g4t1.storage.metadata;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class MetadataClientTest {

    private static final String DOI = "10.1016/s0140-6736(20)31180-6";
    private static final String CROSSREF = "https://api.crossref.org/works/" + DOI;
    private static final String OPENALEX = "https://api.openalex.org/works/doi:" + DOI;

    private MockRestServiceServer server;
    private MetadataClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new MetadataClient(builder, "", "");
    }

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

    @Test
    void lookupTakesTheFirstOfCrossRefsListsAndTheBareOpenAlexId() {
        server.expect(requestTo(CROSSREF)).andRespond(withSuccess("""
                {"status": "ok", "message": {
                  "title": ["Hydroxychloroquine or chloroquine with or without a macrolide"],
                  "container-title": ["The Lancet", "Lancet"],
                  "ISSN": ["0140-6736", "1474-547X"],
                  "issued": {"date-parts": [[2020, 5, 22]]},
                  "publisher": "Elsevier BV"}}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(OPENALEX))
                .andRespond(withSuccess("{\"id\": \"https://openalex.org/W3027887473\"}", MediaType.APPLICATION_JSON));

        assertThat(client.lookup(DOI)).hasValue(new PaperMetadata(DOI, "W3027887473",
                "Hydroxychloroquine or chloroquine with or without a macrolide", "The Lancet", "0140-6736", 2020));
        server.verify();
    }

    // trackByDoi turns empty into a 422 and a thrown error into a 503, so the two must not blur
    @Test
    void aDoiCrossRefDoesNotKnowIsEmpty() {
        server.expect(requestTo(CROSSREF)).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.lookup(DOI)).isEmpty();
        server.verify();
    }

    @Test
    void anyOtherCrossRefFailureIsThrown() {
        server.expect(requestTo(CROSSREF)).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> client.lookup(DOI)).isInstanceOf(RestClientException.class);
    }

    @Test
    void anOpenAlexFailureKeepsTheCrossRefMetadata() {
        server.expect(requestTo(CROSSREF))
                .andRespond(withSuccess("{\"message\": {\"title\": [\"A title\"], \"ISSN\": [\"0140-6736\"]}}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(OPENALEX)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

        assertThat(client.lookup(DOI)).hasValue(new PaperMetadata(DOI, null, "A title", null, "0140-6736", null));
        server.verify();
    }
}
