package com.g4t1.storage.snapshot;

import com.g4t1.storage.paper.PaperRepository;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

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
        Snapshot saved = snapshots.save(new Snapshot(paperId, body,
                toText(body.crossrefUpdates()), toText(body.authors()), toText(body.sourceStatus())));
        return toResponse(saved);
    }

    // oldest first, so Updating and Research Evaluation can compare each pair in order
    @Transactional(readOnly = true)
    public List<SnapshotResponse> history(UUID paperId, Long afterId, Integer limit) {
        requirePaper(paperId);
        if (limit != null && limit < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be at least 1");
        }
        return snapshots.findByPaperIdAndSnapshotIdGreaterThanOrderBySnapshotIdAsc(
                        paperId, afterId == null ? 0 : afterId, limit == null ? Limit.unlimited() : Limit.of(limit))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    private void requirePaper(UUID paperId) {
        if (!papers.existsById(paperId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No paper with id " + paperId);
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
