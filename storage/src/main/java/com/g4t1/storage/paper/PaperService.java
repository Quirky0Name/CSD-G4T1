package com.g4t1.storage.paper;

import com.g4t1.storage.file.LocalFileStore;
import com.g4t1.storage.grobid.GrobidClient;
import com.g4t1.storage.grobid.GrobidClient.PdfHeader;
import com.g4t1.storage.metadata.MetadataClient;
import com.g4t1.storage.metadata.PaperMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.unit.DataSize;
import org.springframework.web.client.RestClientException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

@Service
public class PaperService {

    private static final Logger log = LoggerFactory.getLogger(PaperService.class);

    private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);

    private final PaperRepository papers;
    private final LocalFileStore files;
    private final GrobidClient grobid;
    private final MetadataClient metadata;
    private final DataSize maxPdfSize;

    public PaperService(PaperRepository papers, LocalFileStore files, GrobidClient grobid, MetadataClient metadata,
                        @Value("${storage.max-pdf-size}") DataSize maxPdfSize) {
        this.papers = papers;
        this.files = files;
        this.grobid = grobid;
        this.metadata = metadata;
        this.maxPdfSize = maxPdfSize;
    }

    @Transactional
    public PaperResponse uploadPdf(UUID ownerId, MultipartFile file) throws IOException {
        if (file.getSize() > maxPdfSize.toBytes()) {
            throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE,
                    "PDF is too large, the limit is " + maxPdfSize.toMegabytes() + " MB");
        }
        byte[] bytes = file.getBytes();
        if (!isPdf(bytes)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only PDF files can be uploaded");
        }

        PdfHeader header = grobid.extractHeader(bytes).orElse(new PdfHeader(null, null));
        String doi = MetadataClient.normalizeDoi(header.doi());
        rejectDuplicate(ownerId, doi);

        Paper paper = new Paper(ownerId);
        paper.setDoi(doi);
        paper.setTitle(header.title() != null ? header.title() : file.getOriginalFilename());
        if (doi != null) {
            lookupQuietly(doi).ifPresent(found -> applyMetadata(paper, found));
        }
        paper.setFileKey(files.save(bytes));
        return PaperResponse.from(papers.save(paper));
    }

    private void rejectDuplicate(UUID ownerId, String doi) {
        if (doi != null && papers.existsByOwnerIdAndDoi(ownerId, doi)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You're already tracking the paper with DOI " + doi);
        }
    }

    // the PDF is already in hand, so a CrossRef outage shouldn't block saving it
    private Optional<PaperMetadata> lookupQuietly(String doi) {
        try {
            return metadata.lookup(doi);
        } catch (RestClientException e) {
            log.warn("Metadata lookup failed for {}: {}", doi, e.getMessage());
            return Optional.empty();
        }
    }

    private static void applyMetadata(Paper paper, PaperMetadata found) {
        paper.setOpenalexId(found.openalexId());
        if (found.title() != null) {
            paper.setTitle(found.title());
        }
        paper.setJournal(found.journal());
        paper.setIssn(found.issn());
        paper.setPublicationYear(found.publicationYear());
    }

    private static boolean isPdf(byte[] bytes) {
        return bytes.length >= PDF_MAGIC.length
                && Arrays.equals(bytes, 0, PDF_MAGIC.length, PDF_MAGIC, 0, PDF_MAGIC.length);
    }
}
