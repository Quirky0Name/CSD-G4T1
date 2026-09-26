package com.g4t1.storage.metadata;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.unit.DataSize;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenAccessPdfClientTest {

    private static final String DOI = "10.1000/xyz123";
    private static final byte[] PDF = "%PDF-1.7 open access copy".getBytes(StandardCharsets.US_ASCII);
    private static final String OPENALEX = "https://api.openalex.org/works/doi:" + DOI;
    private static final String S2 = "https://api.semanticscholar.org/graph/v1/paper/DOI:" + DOI;

    private MockRestServiceServer server;
    private OpenAccessPdfClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new OpenAccessPdfClient(builder.build(), "", "s2-test-key", DataSize.ofKilobytes(1));
    }

    @Test
    void fallsBackPastBlockedLinksToTheFirstRealPdf() {
        server.expect(requestTo(startsWith(OPENALEX))).andRespond(withSuccess("""
                {"best_oa_location": {"pdf_url": "https://publisher.example/blocked.pdf"},
                 "locations": [{"pdf_url": "https://publisher.example/blocked.pdf"},
                               {"pdf_url": "https://repo.example/landing"}]}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith(S2)))
                .andExpect(header("x-api-key", "s2-test-key"))
                .andRespond(withSuccess("""
                        {"openAccessPdf": {"url": "https://s2.example/paper.pdf"}}
                        """, MediaType.APPLICATION_JSON));
        // the duplicate OpenAlex link is only tried once
        server.expect(requestTo("https://publisher.example/blocked.pdf")).andRespond(withStatus(HttpStatus.FORBIDDEN));
        server.expect(requestTo("https://repo.example/landing"))
                .andRespond(withSuccess("<!doctype html>", MediaType.TEXT_HTML));
        server.expect(requestTo("https://s2.example/paper.pdf"))
                .andRespond(withSuccess(PDF, MediaType.APPLICATION_PDF));

        assertThat(client.download(DOI)).hasValue(PDF);
        server.verify();
    }

    @Test
    void nothingDownloadableMeansNoPdf() {
        server.expect(requestTo(startsWith(OPENALEX)))
                .andRespond(withSuccess("{\"best_oa_location\": null, \"locations\": []}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith(S2))).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThat(client.download(DOI)).isEmpty();
        server.verify();
    }

    @Test
    void lookupFailuresAndOversizedFilesAreSkipped() {
        server.expect(requestTo(startsWith(OPENALEX))).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        server.expect(requestTo(startsWith(S2))).andRespond(withSuccess("""
                {"openAccessPdf": {"url": "https://s2.example/huge.pdf"}}
                """, MediaType.APPLICATION_JSON));
        byte[] huge = new byte[2048];
        System.arraycopy(PDF, 0, huge, 0, PDF.length);
        server.expect(requestTo("https://s2.example/huge.pdf")).andRespond(withSuccess(huge, MediaType.APPLICATION_PDF));

        assertThat(client.download(DOI)).isEmpty();
        server.verify();
    }
}
