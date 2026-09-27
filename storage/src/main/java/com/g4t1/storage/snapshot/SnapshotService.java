package com.g4t1.storage.snapshot;

import com.g4t1.storage.paper.PaperRepository;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Service
public class SnapshotService {

    private final SnapshotRepository snapshots;
    private final PaperRepository papers;
    private final JsonMapper json;

    public SnapshotService(SnapshotRepository snapshots, PaperRepository papers, JsonMapper json) {
        this.snapshots = snapshots;
        this.papers = papers;
        this.json = json;
    }

    @Transactional
    public SnapshotResponse store(UUID paperId, SnapshotRequest body) {
        requirePaper(paperId);
        // @NotNull lets an explicit JSON null through, and the column would then reject it with a 500
        if (body.sourceStatus().isNull()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "source_status is required");
        }
        Snapshot saved = snapshots.save(new Snapshot(paperId, body,
                toText(body.crossrefUpdates()), toText(body.authors()), toText(body.sourceStatus())));
        return toResponse(saved);
    }

    // Always oldest first, so each pair can be compared in order. The filters apply in the
    // order CONTRACTS.md gives: after_id, then the newest `last` of those, then `limit`.
    @Transactional(readOnly = true)
    public List<SnapshotResponse> history(UUID paperId, Long afterId, Integer last, Integer limit) {
        requirePaper(paperId);
        requireAtLeastOne("last", last);
        requireAtLeastOne("limit", limit);
        long after = afterId == null ? 0 : afterId;

        List<Snapshot> rows;
        if (last == null) {
            rows = snapshots.findByPaperIdAndSnapshotIdGreaterThanOrderBySnapshotIdAsc(
                    paperId, after, limit == null ? Limit.unlimited() : Limit.of(limit));
        } else {
            rows = new ArrayList<>(snapshots.findByPaperIdAndSnapshotIdGreaterThanOrderBySnapshotIdDesc(
                    paperId, after, Limit.of(last)));
            Collections.reverse(rows);
            if (limit != null && rows.size() > limit) {
                rows = rows.subList(0, limit);
            }
        }
        return rows.stream().map(this::toResponse).toList();
    }

    // Research Evaluation matches on this exact text to tell a missing paper from a missing route
    private void requirePaper(UUID paperId) {
        if (!papers.existsById(paperId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No paper " + paperId);
        }
    }

    private static void requireAtLeastOne(String name, Integer value) {
        if (value != null && value < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, name + " must be at least 1");
        }
    }

    private SnapshotResponse toResponse(Snapshot s) {
        return new SnapshotResponse(s.getSnapshotId(), s.getPaperId(), s.getDoi(), s.getFetchedAt(),
                s.getOpenalexId(), s.getTitle(), s.getPublicationYear(), s.getIsRetracted(),
                toNode(s.getCrossrefUpdates()), s.getInDoaj(), s.getJournalSourceId(), s.getJournalSourceType(),
                s.getJournal(), s.getIssnL(), s.getPublisher(), toNode(s.getAuthors()), s.getCitedByCount(),
                toNode(s.getSourceStatus()));
    }

    // a JSON null is stored as SQL null, not the text "null"
    private String toText(JsonNode node) {
        return node == null || node.isNull() ? null : json.writeValueAsString(node);
    }

    private JsonNode toNode(String text) {
        return text == null ? null : json.readTree(text);
    }
}
