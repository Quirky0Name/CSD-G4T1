package com.g4t1.storage.dev;

import com.g4t1.storage.file.LocalFileStore;
import com.g4t1.storage.paper.Paper;
import com.g4t1.storage.paper.PaperRepository;
import com.g4t1.storage.snapshot.Snapshot;
import com.g4t1.storage.snapshot.SnapshotRepository;
import com.g4t1.storage.snapshot.SnapshotRequest;
import com.g4t1.storage.snapshot.SnapshotResponse;
import com.g4t1.storage.snapshot.SnapshotService;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

// Sets a paper up for a demo or a rehearsal. On for everyone for now; take out before a real deployment.
@Service
public class DevService {

    // Crossref's updated-by types, as they appear in a snapshot's crossref_updates
    private static final Set<String> CHANGES = Set.of("retraction", "correction", "erratum", "expression_of_concern");

    private final PaperRepository papers;
    private final SnapshotRepository snapshots;
    private final SnapshotService snapshotService;
    private final JdbcTemplate jdbc;
    private final LocalFileStore files;
    private final JsonMapper json;

    public DevService(PaperRepository papers, SnapshotRepository snapshots, SnapshotService snapshotService,
                      JdbcTemplate jdbc, LocalFileStore files, JsonMapper json) {
        this.papers = papers;
        this.snapshots = snapshots;
        this.snapshotService = snapshotService;
        this.jdbc = jdbc;
        this.files = files;
        this.json = json;
    }

    // Stores a copy of the paper's latest snapshot with one change taken out, so the next poll,
    // which fetches the real data, finds that change again and nudges Research Evaluation.
    public SnapshotResponse undoChange(UUID ownerId, UUID paperId, String change) {
        requireOwnPaper(ownerId, paperId);
        if (!CHANGES.contains(change)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "change must be one of " + CHANGES);
        }
        Snapshot latest = snapshots.findByPaperIdAndSnapshotIdGreaterThanOrderBySnapshotIdDesc(paperId, 0, Limit.of(1))
                .stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "Paper " + paperId + " has no snapshot yet; poll it once first so there's real data to copy"));

        boolean undoRetraction = change.equals("retraction") && Boolean.TRUE.equals(latest.getIsRetracted());
        JsonNode updates = latest.getCrossrefUpdates() == null ? null : json.readTree(latest.getCrossrefUpdates());
        ArrayNode kept = json.createArrayNode();
        if (updates != null) {
            updates.forEach(entry -> {
                if (!change.equals(entry.path("type").asString())) {
                    kept.add(entry);
                }
            });
        }
        boolean removedNotices = updates != null && kept.size() < updates.size();
        if (!undoRetraction && !removedNotices) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Paper " + paperId + " has no " + change + " to undo");
        }

        SnapshotRequest before = new SnapshotRequest(latest.getDoi(), Instant.now(), latest.getOpenalexId(),
                latest.getTitle(), latest.getPublicationYear(),
                undoRetraction ? Boolean.FALSE : latest.getIsRetracted(),
                updates == null ? null : kept, latest.getInDoaj(), latest.getJournalSourceId(),
                latest.getJournalSourceType(), latest.getJournal(), latest.getIssnL(), latest.getPublisher(),
                toNode(latest.getAuthors()), latest.getCitedByCount(), toNode(latest.getSourceStatus()));
        return snapshotService.store(paperId, before);
    }

    // Research Evaluation never raises the same change twice, so a rehearsal needs the old alerts gone
    @Transactional
    public void clearHistory(UUID ownerId, UUID paperId) {
        requireOwnPaper(ownerId, paperId);
        deleteHistory(paperId).forEach(files::delete);
    }

    // Everything that points at the paper, then the paper and its PDF. Updating drops it from
    // its own tracking on the next poll, since storage no longer lists it.
    @Transactional
    public void deletePaper(UUID ownerId, UUID paperId) {
        Paper paper = requireOwnPaper(ownerId, paperId);
        List<String> documentFiles = deleteHistory(paperId);
        jdbc.update("delete from papers where id = ?", paperId);
        documentFiles.forEach(files::delete);
        if (paper.getFileKey() != null) {
            files.delete(paper.getFileKey());
        }
    }

    // returns the report documents' files, to delete once their rows are gone
    private List<String> deleteHistory(UUID paperId) {
        List<String> documentFiles = jdbc.queryForList("""
                select d.file_key from report_documents d join reports r on r.id = d.report_id
                where r.paper_id = ? and d.file_key is not null""", String.class, paperId);
        // documents cascade with their report, notes with their alert
        jdbc.update("delete from reports where paper_id = ?", paperId);
        jdbc.update("delete from alerts where paper_id = ?", paperId);
        jdbc.update("delete from background_metadata where paper_id = ?", paperId);
        return documentFiles;
    }

    // someone else's paper gets the same 404 as a missing one
    private Paper requireOwnPaper(UUID ownerId, UUID paperId) {
        return papers.findById(paperId)
                .filter(paper -> paper.getOwnerId().equals(ownerId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No paper " + paperId));
    }

    private JsonNode toNode(String text) {
        return text == null ? null : json.readTree(text);
    }
}
