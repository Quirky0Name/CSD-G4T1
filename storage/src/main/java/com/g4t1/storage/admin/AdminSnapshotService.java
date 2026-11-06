package com.g4t1.storage.admin;

import com.g4t1.storage.dev.DevService;
import com.g4t1.storage.paper.PaperRepository;
import com.g4t1.storage.snapshot.Snapshot;
import com.g4t1.storage.snapshot.SnapshotRepository;
import com.g4t1.storage.snapshot.SnapshotResponse;
import com.g4t1.storage.snapshot.SnapshotService;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

// Changes a paper's flags in its stored data: the next poll fetches the real values and compares them
// with this row, so setting a value to what it was before a real change makes that poll find the change.
@Service
public class AdminSnapshotService {

    private final PaperRepository papers;
    private final SnapshotRepository snapshots;
    private final SnapshotService snapshotService;
    private final DevService dev;
    private final JsonMapper json;

    public AdminSnapshotService(PaperRepository papers, SnapshotRepository snapshots, SnapshotService snapshotService,
                                DevService dev, JsonMapper json) {
        this.papers = papers;
        this.snapshots = snapshots;
        this.snapshotService = snapshotService;
        this.dev = dev;
        this.json = json;
    }

    public SnapshotResponse edit(UUID paperId, SnapshotEditRequest edit) {
        Boolean isRetracted = bool("is_retracted", edit.isRetracted());
        Boolean inDoaj = bool("in_doaj", edit.inDoaj());
        Integer citedByCount = count(edit.citedByCount());
        Set<String> removedTypes = types(edit.removedCrossrefUpdateTypes());
        if (isRetracted == null && inDoaj == null && citedByCount == null && removedTypes.isEmpty()) {
            throw badRequest("Give at least one of is_retracted, in_doaj, cited_by_count, removed_crossref_update_types");
        }

        if (!papers.existsById(paperId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No paper " + paperId);
        }
        Snapshot latest = snapshots.findByPaperIdAndSnapshotIdGreaterThanOrderBySnapshotIdDesc(paperId, 0, Limit.of(1))
                .stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "Paper " + paperId + " has no snapshot yet; poll it once first"));

        JsonNode sourceStatus = json.readTree(latest.getSourceStatus());
        if (isRetracted != null || inDoaj != null || citedByCount != null) {
            requireSourceOk(paperId, sourceStatus, "openalex");
        }
        if (!removedTypes.isEmpty()) {
            requireSourceOk(paperId, sourceStatus, "crossref");
        }

        return snapshotService.store(paperId, dev.copyOf(latest,
                isRetracted != null ? isRetracted : latest.getIsRetracted(),
                inDoaj != null ? inDoaj : latest.getInDoaj(),
                withoutTypes(latest.getCrossrefUpdates(), removedTypes),
                citedByCount != null ? citedByCount : latest.getCitedByCount()));
    }

    // the poll never compares a field whose source wasn't ok, so an edit to it could raise nothing
    private static void requireSourceOk(UUID paperId, JsonNode sourceStatus, String source) {
        String status = sourceStatus.path(source).asString();
        if (!"ok".equals(status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Paper " + paperId
                    + "'s latest snapshot has source_status." + source + " " + status
                    + ", and a poll never compares such a field");
        }
    }

    private JsonNode withoutTypes(String crossrefUpdates, Set<String> removedTypes) {
        if (crossrefUpdates == null) {
            return null;
        }
        JsonNode updates = json.readTree(crossrefUpdates);
        ArrayNode kept = json.createArrayNode();
        updates.forEach(entry -> {
            if (!removedTypes.contains(entry.path("type").asString())) {
                kept.add(entry);
            }
        });
        return kept;
    }

    private static Boolean bool(String name, JsonNode node) {
        if (absent(node)) {
            return null;
        }
        if (!node.isBoolean()) {
            throw badRequest(name + " must be a boolean");
        }
        return node.booleanValue();
    }

    private static Integer count(JsonNode node) {
        if (absent(node)) {
            return null;
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt() || node.intValue() < 0) {
            throw badRequest("cited_by_count must be an integer of 0 or more");
        }
        return node.intValue();
    }

    private static Set<String> types(JsonNode node) {
        Set<String> types = new HashSet<>();
        if (absent(node)) {
            return types;
        }
        if (!node.isArray()) {
            throw badRequest("removed_crossref_update_types must be an array of non-blank strings");
        }
        node.forEach(entry -> {
            if (!entry.isString() || entry.stringValue().isBlank()) {
                throw badRequest("removed_crossref_update_types must be an array of non-blank strings");
            }
            types.add(entry.stringValue());
        });
        return types;
    }

    private static boolean absent(JsonNode node) {
        return node == null || node.isNull();
    }

    private static ResponseStatusException badRequest(String detail) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, detail);
    }
}
