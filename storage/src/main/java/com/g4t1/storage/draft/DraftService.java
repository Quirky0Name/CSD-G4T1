package com.g4t1.storage.draft;

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
public class DraftService {

    private static final Logger log = LoggerFactory.getLogger(DraftService.class);

    private final DraftRepository drafts;
    private final PaperRepository papers;
    private final LocalFileStore files;
    private final TransactionTemplate tx;
    private final DataSize maxPdfSize;

    public DraftService(DraftRepository drafts, PaperRepository papers, LocalFileStore files,
                        PlatformTransactionManager transactionManager,
                        @Value("${storage.max-pdf-size}") DataSize maxPdfSize) {
        this.drafts = drafts;
        this.papers = papers;
        this.files = files;
        this.tx = new TransactionTemplate(transactionManager);
        this.maxPdfSize = maxPdfSize;
    }

    /**
     * Stores the researcher's draft for a project (the default project when projectId is null).
     * A project has one draft, so uploading to a project that has one replaces its file (the id
     * stays the same) and deletes the old PDF.
     */
    public StoredDraft upload(UUID ownerId, UUID projectId, MultipartFile file) throws IOException {
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
            stored = storeRow(ownerId, projectId, key, file.getOriginalFilename());
        } catch (RuntimeException e) {
            // no row points at the new file
            files.delete(key);
            throw e;
        }
        // only once the row points at the new file, so a failed write never loses the current one
        if (stored.replacedKey() != null) {
            files.delete(stored.replacedKey());
        }
        return new StoredDraft(DraftResponse.from(stored.draft()), stored.replacedKey() == null);
    }

    /**
     * The owner's draft for a project (projectId null = the default project).
     */
    public DraftResponse find(UUID ownerId, UUID projectId) {
        return drafts.findByOwnerIdAndProjectId(ownerId, projectId)
                .map(DraftResponse::from)
                .orElseThrow(() -> noDraft(projectId));
    }

    /**
     * Deletes the owner's draft for a project, and its PDF once the row is gone.
     */
    public void delete(UUID ownerId, UUID projectId) {
        String key = tx.execute(status -> {
            Draft current = drafts.findForUpdateByOwnerIdAndProjectId(ownerId, projectId)
                    .orElseThrow(() -> noDraft(projectId));
            drafts.delete(current);
            return current.getFileKey();
        });
        files.delete(key);
    }

    /**
     * The PDF of the draft in a tracked paper's project: the paper's owner plus its project, or
     * the owner's default project when it has none. For Research Evaluation, which evaluates one
     * tracked paper at a time. An unknown paper is the same "No paper" 404 as the other internal endpoints.
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
            log.warn("The draft for paper {} points at a file that isn't on disk", paperId);
            return new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "The draft for paper " + paperId + " is missing from disk");
        });
    }

    private Optional<byte[]> currentPdf(UUID paperId, Paper paper) {
        Draft draft = drafts.findByOwnerIdAndProjectId(paper.getOwnerId(), paper.getProjectId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No draft in the project of paper " + paperId));
        return files.read(draft.getFileKey());
    }

    private static ResponseStatusException noDraft(UUID projectId) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, projectId == null
                ? "No draft in the default project"
                : "No draft in project " + projectId);
    }

    private Stored storeRow(UUID ownerId, UUID projectId, String key, String filename) {
        try {
            return tx.execute(status -> insertOrReplace(ownerId, projectId, key, filename));
        } catch (DataIntegrityViolationException e) {
            // another upload created this project's row first; replace its file instead
            return tx.execute(status -> insertOrReplace(ownerId, projectId, key, filename));
        }
    }

    private Stored insertOrReplace(UUID ownerId, UUID projectId, String key, String filename) {
        var existing = drafts.findForUpdateByOwnerIdAndProjectId(ownerId, projectId);
        if (existing.isPresent()) {
            Draft current = existing.get();
            String replacedKey = current.getFileKey();
            current.replaceFile(key, filename);
            return new Stored(current, replacedKey);
        }
        return new Stored(drafts.saveAndFlush(new Draft(ownerId, projectId, key, filename)), null);
    }

    // replacedKey is the file the row pointed at before, null for a new row
    private record Stored(Draft draft, String replacedKey) {
    }

    public record StoredDraft(DraftResponse draft, boolean created) {
    }
}
