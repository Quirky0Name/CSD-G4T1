package com.g4t1.storage.metadata;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.g4t1.storage.file.Pdfs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

// Finds an open-access copy of a paper for DOI tracking: every PDF link OpenAlex
// knows (best one first), then Semantic Scholar's. The first link that actually
// returns a PDF wins; publishers often answer with a 403 or an HTML page instead.
@Component
public class OpenAccessPdfClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAccessPdfClient.class);

    private static final String OPENALEX = "https://api.openalex.org";
    private static final String SEMANTIC_SCHOLAR = "https://api.semanticscholar.org/graph/v1";

    private final RestClient rest;
    private final String openalexApiKey;
    private final String semanticScholarApiKey;
    private final DataSize maxPdfSize;

    @Autowired
    public OpenAccessPdfClient(RestClient.Builder builder,
                               @Value("${storage.openalex-api-key}") String openalexApiKey,
                               @Value("${storage.semantic-scholar-api-key}") String semanticScholarApiKey,
                               @Value("${storage.max-pdf-size}") DataSize maxPdfSize) {
        this(builder.requestFactory(downloadRequestFactory()).build(),
                openalexApiKey, semanticScholarApiKey, maxPdfSize);
    }

    OpenAccessPdfClient(RestClient rest, String openalexApiKey, String semanticScholarApiKey, DataSize maxPdfSize) {
        this.rest = rest;
        this.openalexApiKey = openalexApiKey;
        this.semanticScholarApiKey = semanticScholarApiKey;
        this.maxPdfSize = maxPdfSize;
    }

    public Optional<byte[]> download(String doi) {
        for (String url : candidateUrls(doi)) {
            Optional<byte[]> pdf = fetchPdf(url);
            if (pdf.isPresent()) {
                return pdf;
            }
        }
        return Optional.empty();
    }

    private List<String> candidateUrls(String doi) {
        Set<String> urls = new LinkedHashSet<>(openalexPdfUrls(doi));
        semanticScholarPdfUrl(doi).ifPresent(urls::add);
        return List.copyOf(urls);
    }

    private List<String> openalexPdfUrls(String doi) {
        var uri = UriComponentsBuilder.fromUriString(OPENALEX)
                .path("/works/doi:" + doi)
                .queryParam("select", "best_oa_location,locations");
        if (!openalexApiKey.isBlank()) {
            uri.queryParam("api_key", openalexApiKey);
        }
        try {
            OpenAlexLocations work = rest.get().uri(uri.build().encode().toUri()).retrieve()
                    .body(OpenAlexLocations.class);
            return work == null ? List.of() : work.pdfUrls();
        } catch (RestClientException e) {
            // a lookup failure just means one less place to look
            log.warn("OpenAlex PDF lookup failed for {}: {}", doi, e.getMessage());
            return List.of();
        }
    }

    private Optional<String> semanticScholarPdfUrl(String doi) {
        URI uri = UriComponentsBuilder.fromUriString(SEMANTIC_SCHOLAR)
                .path("/paper/DOI:" + doi)
                .queryParam("fields", "openAccessPdf")
                .build().encode().toUri();
        try {
            SemanticScholarPaper paper = rest.get().uri(uri)
                    .headers(h -> {
                        if (!semanticScholarApiKey.isBlank()) {
                            h.set("x-api-key", semanticScholarApiKey);
                        }
                    })
                    .retrieve()
                    .body(SemanticScholarPaper.class);
            if (paper == null || paper.openAccessPdf() == null || isBlank(paper.openAccessPdf().url())) {
                return Optional.empty();
            }
            return Optional.of(paper.openAccessPdf().url());
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (RestClientException e) {
            log.warn("Semantic Scholar PDF lookup failed for {}: {}", doi, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<byte[]> fetchPdf(String url) {
        try {
            byte[] body = rest.get().uri(URI.create(url))
                    // some hosts refuse requests without a browser-like user agent
                    .header(HttpHeaders.USER_AGENT, "Mozilla/5.0 (compatible; research-assistant)")
                    .retrieve()
                    .body(byte[].class);
            if (!Pdfs.isPdf(body)) {
                log.info("{} didn't return a PDF", url);
                return Optional.empty();
            }
            if (body.length > maxPdfSize.toBytes()) {
                log.info("{} is over the {} MB limit", url, maxPdfSize.toMegabytes());
                return Optional.empty();
            }
            return Optional.of(body);
        } catch (RestClientException | IllegalArgumentException e) {
            log.info("Couldn't download {}: {}", url, e.getMessage());
            return Optional.empty();
        }
    }

    // publisher links redirect a lot and some never answer, so follow redirects and give up after a while
    private static JdkClientHttpRequestFactory downloadRequestFactory() {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(30));
        return factory;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    record OpenAlexLocations(@JsonProperty("best_oa_location") Location bestOaLocation,
                             @JsonProperty("locations") List<Location> locations) {

        List<String> pdfUrls() {
            List<String> urls = new ArrayList<>();
            if (bestOaLocation != null && !isBlank(bestOaLocation.pdfUrl())) {
                urls.add(bestOaLocation.pdfUrl());
            }
            if (locations != null) {
                locations.stream()
                        .map(Location::pdfUrl)
                        .filter(url -> !isBlank(url))
                        .forEach(urls::add);
            }
            return urls;
        }
    }

    record Location(@JsonProperty("pdf_url") String pdfUrl) {
    }

    record SemanticScholarPaper(@JsonProperty("openAccessPdf") OpenAccessPdf openAccessPdf) {
    }

    record OpenAccessPdf(@JsonProperty("url") String url) {
    }
}
