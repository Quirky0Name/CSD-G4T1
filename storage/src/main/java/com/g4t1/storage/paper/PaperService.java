package com.g4t1.storage.paper;

import com.g4t1.storage.file.LocalFileStore;
import com.g4t1.storage.file.Pdfs;
import com.g4t1.storage.grobid.GrobidClient;
import com.g4t1.storage.grobid.GrobidClient.PdfHeader;
import com.g4t1.storage.metadata.MetadataClient;
import com.g4t1.storage.metadata.OpenAccessPdfClient;
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
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class PaperService {

    private static final Logger log = LoggerFactory.getLogger(PaperService.class);

    // every DOI starts with the 10. directory indicator, a registrant code, then a slash
    private static final Pattern DOI_SHAPE = Pattern.compile("^10\\.\\d{4,9}/\\S+$");

    private final PaperRepository papers;
    private final LocalFileStore files;
    private final GrobidClient grobid;
    private final MetadataClient metadata;
    private final OpenAccessPdfClient openAccess;
    private final DataSize maxPdfSize;

    public PaperService(PaperRepository papers, LocalFileStore files, GrobidClient grobid, MetadataClient metadata,
                        OpenAccessPdfClient openAccess, @Value("${storage.max-pdf-size}") DataSize maxPdfSize) {
        this.papers = papers;
        this.files = files;
        this.grobid = grobid;
        this.metadata = metadata;
        this.openAccess = openAccess;
        this.maxPdfSize = maxPdfSize;
    }

    @Transactional
    public PaperResponse uploadPdf(UUID ownerId, UUID folderId, MultipartFile file) throws IOException {
        if (file.getSize() > maxPdfSize.toBytes()) {
            throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE,
                    "PDF is too large, the limit is " + maxPdfSize.toMegabytes() + " MB");
        }
        byte[] bytes = file.getBytes();
        if (!Pdfs.isPdf(bytes)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only PDF files can be uploaded");
        }

        PdfHeader header = grobid.extractHeader(bytes).orElse(new PdfHeader(null, null));
        String doi = MetadataClient.normalizeDoi(header.doi());
        rejectDuplicate(ownerId, doi);

        Paper paper = new Paper(ownerId);
        paper.setFolderId(folderId);
        paper.setDoi(doi);
        paper.setTitle(header.title() != null ? header.title() : file.getOriginalFilename());
        if (doi != null) {
            lookupQuietly(doi).ifPresent(found -> applyMetadata(paper, found));
        }
        paper.setFileKey(files.save(bytes));
        return PaperResponse.from(papers.save(paper));
    }

    @Transactional
    public PaperResponse trackByDoi(UUID ownerId, UUID folderId, String rawDoi) {
        String doi = MetadataClient.normalizeDoi(rawDoi);
        if (doi == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "doi is required");
        }
        if (!DOI_SHAPE.matcher(doi).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "\"" + rawDoi + "\" isn't a DOI, it should look like 10.1000/xyz123");
        }
        rejectDuplicate(ownerId, doi);

        PaperMetadata found;
        try {
            found = metadata.lookup(doi).orElseThrow(() -> new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_CONTENT, "CrossRef has no paper with DOI " + doi));
        } catch (RestClientException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Couldn't reach CrossRef to check that DOI, try again shortly");
        }

        // Research Evaluation needs the paper itself, so a DOI we can't get a PDF for isn't tracked
        byte[] pdf = openAccess.download(doi).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "Couldn't find an open-access PDF for DOI " + doi + ", so it can't be tracked. "
                        + "Try a different paper"));

        Paper paper = new Paper(ownerId);
        paper.setFolderId(folderId);
        paper.setDoi(doi);
        applyMetadata(paper, found);
        paper.setFileKey(files.save(pdf));
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
}
