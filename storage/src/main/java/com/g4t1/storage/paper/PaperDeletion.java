package com.g4t1.storage.paper;

import com.g4t1.storage.file.LocalFileStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

// Deleting a tracked paper and everything that refers to it. The demo tools and a deleted project
// both use it, so it lives here rather than with the demo tools, which a deployment can switch off.
@Service
public class PaperDeletion {

    private final JdbcTemplate jdbc;
    private final LocalFileStore files;

    public PaperDeletion(JdbcTemplate jdbc, LocalFileStore files) {
        this.jdbc = jdbc;
        this.files = files;
    }

    // Everything that points at the paper, then the paper and its PDF. Updating drops it from
    // its own tracking on the next poll, since storage no longer lists it.
    @Transactional
    public void delete(Paper paper) {
        List<String> documentFiles = deleteHistory(paper.getId());
        jdbc.update("delete from papers where id = ?", paper.getId());
        documentFiles.forEach(files::delete);
        if (paper.getFileKey() != null) {
            files.delete(paper.getFileKey());
        }
    }

    // the paper's snapshots, alerts and reports, and its documents' PDFs, but not the paper
    @Transactional
    public void clearHistory(UUID paperId) {
        deleteHistory(paperId).forEach(files::delete);
    }

    // returns the report documents' files, to delete once their rows are gone
    private List<String> deleteHistory(UUID paperId) {
        List<String> documentFiles = jdbc.queryForList("""
                select d.file_key from report_documents d join reports r on r.id = d.report_id
                where r.paper_id = ? and d.file_key is not null""", String.class, paperId);
        // documents cascade with their report, notes with their alert
        jdbc.update("delete from reports where paper_id = ?", paperId);
        jdbc.update("delete from alerts where paper_id = ?", paperId);
        jdbc.update("delete from snapshots where paper_id = ?", paperId);
        return documentFiles;
    }
}
