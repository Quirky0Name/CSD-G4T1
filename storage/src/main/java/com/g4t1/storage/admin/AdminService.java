package com.g4t1.storage.admin;

import com.g4t1.storage.paper.PaperDeletion;
import com.g4t1.storage.paper.PaperRepository;
import com.g4t1.storage.paper.PaperService;
import com.g4t1.storage.snapshot.SnapshotResponse;
import com.g4t1.storage.snapshot.SnapshotService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

// What the admin can see of any researcher's papers, and clearing a paper's history
@Service
public class AdminService {

    private final PaperService paperService;
    private final PaperRepository papers;
    private final SnapshotService snapshots;
    private final PaperDeletion deletion;

    public AdminService(PaperService paperService, PaperRepository papers, SnapshotService snapshots,
                        PaperDeletion deletion) {
        this.paperService = paperService;
        this.papers = papers;
        this.snapshots = snapshots;
        this.deletion = deletion;
    }

    public List<AdminPaperResponse> papersOf(UUID ownerId) {
        return paperService.listForOwner(ownerId).stream()
                .map(paper -> new AdminPaperResponse(paper, ownerId,
                        snapshots.history(paper.id(), null, 1, null).stream().findFirst().orElse(null)))
                .toList();
    }

    public List<SnapshotResponse> snapshotsOf(UUID paperId) {
        return snapshots.history(paperId, null, null, null);
    }

    // Research Evaluation never raises a change key twice, so raising the same change again needs its alert gone
    public void clearHistory(UUID paperId) {
        if (!papers.existsById(paperId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No paper " + paperId);
        }
        deletion.clearHistory(paperId);
    }
}
