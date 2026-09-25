package com.g4t1.storage.metadata;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.Optional;

/**
 * 
 * looksup doi and returns metadata for the paper
 */
@Component
public class MetadataClient {

    private static final Logger log = LoggerFactory.getLogger(MetadataClient.class);

    private static final String CROSSREF = "https://api.crossref.org";
    private static final String OPENALEX = "https://api.openalex.org";

    private final RestClient rest;
    private final String crossrefMailto;
    private final String openalexApiKey;

    public MetadataClient(RestClient.Builder builder,
                          @Value("${storage.crossref-mailto}") String crossrefMailto,
                          @Value("${storage.openalex-api-key}") String openalexApiKey) {
        this.rest = builder.build();
        this.crossrefMailto = crossrefMailto;
        this.openalexApiKey = openalexApiKey;
    }

    /**
     * Empty when CrossRef has no record of the DOI. Any other CrossRef failure is
     * thrown, so callers can tell "no such paper" apart from "CrossRef is down".
     */
    public Optional<PaperMetadata> lookup(String doi) {
        CrossrefWork work;
        try {
            work = rest.get().uri(crossrefUri(doi)).retrieve().body(CrossrefWork.class);
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        }
        if (work == null || work.message() == null) {
            return Optional.empty();
        }

        CrossrefWork.Message m = work.message();
        return Optional.of(new PaperMetadata(
                doi,
                openalexId(doi),
                first(m.title()),
                first(m.containerTitle()),
                first(m.issn()),
                m.year()));
    }

    // OpenAlex only adds the openalexId Research Evaluation looks papers up by, so a
    // failure here shouldn't lose the CrossRef metadata
    private String openalexId(String doi) {
        try {
            OpenAlexWork work = rest.get().uri(openalexUri(doi)).retrieve().body(OpenAlexWork.class);
            if (work == null || work.id() == null) {
                return null;
            }
            return work.id().substring(work.id().lastIndexOf('/') + 1);
        } catch (RestClientException e) {
            log.warn("OpenAlex lookup failed for {}: {}", doi, e.getMessage());
            return null;
        }
    }

    public static String normalizeDoi(String raw) {
        if (raw == null) {
            return null;
        }
        String doi = raw.trim().replaceFirst("(?i)^(https?://(dx\\.)?doi\\.org/|doi:)", "");
        // DOIs are case-insensitive, so store one form to keep the owner+doi check honest
        return doi.isEmpty() ? null : doi.toLowerCase();
    }

    private URI crossrefUri(String doi) {
        var uri = UriComponentsBuilder.fromUriString(CROSSREF).path("/works/" + doi);
        if (!crossrefMailto.isBlank()) {
            uri.queryParam("mailto", crossrefMailto);
        }
        return uri.build().encode().toUri();
    }

    private URI openalexUri(String doi) {
        var uri = UriComponentsBuilder.fromUriString(OPENALEX).path("/works/doi:" + doi);
        if (!openalexApiKey.isBlank()) {
            uri.queryParam("api_key", openalexApiKey);
        }
        return uri.build().encode().toUri();
    }

    /**
     * 
     * helper function (currently used only for CrossRef API) 
     * CrossRef returns JSON where the fields are in a lists
     *  whether it's one elem or not
     * this helper just takes the first elem
     */
    private static String first(List<String> values) {
        return values == null || values.isEmpty() ? null : values.getFirst();
    }

    /**
     * used to store/format CrossRef's API response
     */
    record CrossrefWork(Message message) {

        record Message(List<String> title,
                       @JsonProperty("container-title") List<String> containerTitle,
                       @JsonProperty("ISSN") List<String> issn,
                       Issued issued) {

            Integer year() {
                if (issued == null || issued.dateParts() == null || issued.dateParts().isEmpty()) {
                    return null;
                }
                List<Integer> parts = issued.dateParts().getFirst();
                return parts == null || parts.isEmpty() ? null : parts.getFirst();
            }
        }

        record Issued(@JsonProperty("date-parts") List<List<Integer>> dateParts) {
        }
    }

    record OpenAlexWork(String id) {
    }
}
