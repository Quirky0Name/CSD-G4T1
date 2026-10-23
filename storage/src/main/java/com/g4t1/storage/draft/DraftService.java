package com.g4t1.storage.research;

import com.g4t1.storage.file.LocalFileStore;
import com.g4t1.storage.file.Pdfs;
import com.g4t1.storage.paper.Paper;
import com.g4t1.storage.paper.PaperRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.unit.DataSize;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

@Service
public class ResearchPaperService {

    private static final Logger log = LoggerFactory.getLogger(ResearchPaperService.class);

    private final ResearchPaperRepository researchPapers;
    private final PaperRepository papers;
    private final LocalFileStore files;
    private final TransactionTemplate tx;
    private final DataSize maxPdfSize;

    public ResearchPaperService(ResearchPaperRepository researchPapers, PaperRepository papers, LocalFileStore files,
                                PlatformTransactionManager transactionManager,
                                @Value("${storage.max-pdf-size}") DataSize maxPdfSize) {
        this.researchPapers = researchPapers;
        this.papers = papers;
        this.files = files;
        this.tx = new TransactionTemplate(transactionManager);
        this.maxPdfSize = maxPdfSize;
    }

    /**
     * Stores the researcher's own paper for a project: one of their folders, or everything they keep
     * outside folders when folderId is null. A project has one research paper, so uploading to a
     * project that has one replaces its file (the id stays the same) and deletes the old PDF.
     */
    public StoredResearchPaper upload(UUID ownerId, UUID folderId, MultipartFile file) throws IOException {
        if (file.getSize() > maxPdfSize.toBytes()) {
            throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE,
                    "PDF is too large, the limit is " + maxPdfSize.toMegabytes() + " MB");
        }
        byte[] bytes = file.getBytes();
        if (!Pdfs.isPdf(bytes)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only PDF files can be uploaded");
        }

        String key = files.save(bytes);
        Stored stored;
        try {
            stored = storeRow(ownerId, folderId, key, file.getOriginalFilename());
        } catch (RuntimeException e) {
            // no row points at the new file
            files.delete(key);
            throw e;
        }
        // only once the row points at the new file, so a failed write never loses the current one
        if (stored.replacedKey() != null) {
            files.delete(stored.replacedKey());
        }
        return new StoredResearchPaper(ResearchPaperResponse.from(stored.researchPaper()), stored.replacedKey() == null);
    }

    /**
     * The owner's research paper for a project (folderId null = the "no folder" project).
     */
    public ResearchPaperResponse find(UUID ownerId, UUID folderId) {
        return researchPapers.findByOwnerIdAndFolderId(ownerId, folderId)
                .map(ResearchPaperResponse::from)
                .orElseThrow(() -> noResearchPaper(folderId));
    }

    /**
     * Deletes the owner's research paper for a project, and its PDF once the row is gone.
     */
    public void delete(UUID ownerId, UUID folderId) {
        String key = tx.execute(status -> {
            ResearchPaper current = researchPapers.findForUpdateByOwnerIdAndFolderId(ownerId, folderId)
                    .orElseThrow(() -> noResearchPaper(folderId));
            researchPapers.delete(current);
            return current.getFileKey();
        });
        files.delete(key);
    }

    /**
     * The PDF of the research paper in a tracked paper's project: the paper's owner plus its folder, or
     * the owner's "no folder" project when it has none. For Research Evaluation, which evaluates one
     * user's paper at a time. An unknown paper is the same "No paper" 404 as the other internal endpoints.
     */
    public byte[] pdfForPaper(UUID paperId) {
        Paper paper = papers.findById(paperId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No paper " + paperId));
        Optional<byte[]> pdf = currentPdf(paperId, paper);
        if (pdf.isEmpty()) {
            // a re-upload can delete the file between reading the row and reading the file; the row now has the new one
            pdf = currentPdf(paperId, paper);
        }
        return pdf.orElseThrow(() -> {
            log.warn("The research paper for paper {} points at a file that isn't on disk", paperId);
            return new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "The research paper for paper " + paperId + " is missing from disk");
        });
    }

    private Optional<byte[]> currentPdf(UUID paperId, Paper paper) {
        ResearchPaper researchPaper = researchPapers.findByOwnerIdAndFolderId(paper.getOwnerId(), paper.getFolderId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No research paper in the project of paper " + paperId));
        return files.read(researchPaper.getFileKey());
    }

    private static ResponseStatusException noResearchPaper(UUID folderId) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, folderId == null
                ? "No research paper outside folders"
                : "No research paper in folder " + folderId);
    }

    private Stored storeRow(UUID ownerId, UUID folderId, String key, String filename) {
        try {
            return tx.execute(status -> insertOrReplace(ownerId, folderId, key, filename));
        } catch (DataIntegrityViolationException e) {
            // another upload created this project's row first; replace its file instead
            return tx.execute(status -> insertOrReplace(ownerId, folderId, key, filename));
        }
    }

    private Stored insertOrReplace(UUID ownerId, UUID folderId, String key, String filename) {
        var existing = researchPapers.findForUpdateByOwnerIdAndFolderId(ownerId, folderId);
        if (existing.isPresent()) {
            ResearchPaper current = existing.get();
            String replacedKey = current.getFileKey();
            current.replaceFile(key, filename);
            return new Stored(current, replacedKey);
        }
        return new Stored(researchPapers.saveAndFlush(new ResearchPaper(ownerId, folderId, key, filename)), null);
    }

    // replacedKey is the file the row pointed at before, null for a new row
    private record Stored(ResearchPaper researchPaper, String replacedKey) {
    }

    public record StoredResearchPaper(ResearchPaperResponse researchPaper, boolean created) {
    }
}
