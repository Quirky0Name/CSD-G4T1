package com.g4t1.storage.grobid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class GrobidClient {

    private static final Logger log = LoggerFactory.getLogger(GrobidClient.class);

    // GROBID writes one BibTeX field per line, e.g.  doi = {10.1016/xyz},
    private static final Pattern DOI = Pattern.compile("^\\s*doi\\s*=\\s*\\{(.+)},?\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
    private static final Pattern TITLE = Pattern.compile("^\\s*title\\s*=\\s*\\{(.+)},?\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

    private final RestClient rest;

    public GrobidClient(RestClient.Builder builder, @Value("${storage.grobid-url}") String baseUrl) {
        this.rest = builder.baseUrl(baseUrl).build();
    }

    public Optional<PdfHeader> extractHeader(byte[] pdf) {
        var form = new LinkedMultiValueMap<String, Object>();
        form.add("input", new ByteArrayResource(pdf) {
            @Override
            public String getFilename() {
                return "paper.pdf";
            }
        });

        try {
            String bibtex = rest.post()
                    .uri("/api/processHeaderDocument")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .accept(MediaType.parseMediaType("application/x-bibtex"))
                    .body(form)
                    .retrieve()
                    .body(String.class);
            return Optional.ofNullable(bibtex).map(GrobidClient::parse);
        } catch (RestClientException e) {
            // an upload should still be saved when GROBID is down, just without header data
            log.warn("GROBID header extraction failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    static PdfHeader parse(String bibtex) {
        return new PdfHeader(field(DOI, bibtex), field(TITLE, bibtex));
    }

    private static String field(Pattern pattern, String bibtex) {
        Matcher m = pattern.matcher(bibtex);
        return m.find() ? m.group(1).trim() : null;
    }

    public record PdfHeader(String doi, String title) {
    }
}
