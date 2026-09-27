package com.g4t1.storage.research;

import com.g4t1.storage.file.LocalFileStore;
import com.g4t1.storage.file.Pdfs;
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
import java.util.UUID;

@Service
public class ResearchPaperService {

    private final ResearchPaperRepository researchPapers;
    private final LocalFileStore files;
    private final TransactionTemplate tx;
    private final DataSize maxPdfSize;

    public ResearchPaperService(ResearchPaperRepository researchPapers, LocalFileStore files,
                                PlatformTransactionManager transactionManager,
                                @Value("${storage.max-pdf-size}") DataSize maxPdfSize) {
        this.researchPapers = researchPapers;
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
