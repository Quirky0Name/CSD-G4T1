package com.g4t1.storage.project;

import com.g4t1.storage.draft.DraftService;
import com.g4t1.storage.paper.PaperDeletion;
import com.g4t1.storage.paper.PaperRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

// Projects belong to User Management; storage only holds their papers and drafts
@Service
public class ProjectService {

    private final PaperRepository papers;
    private final PaperDeletion deletion;
    private final DraftService drafts;

    public ProjectService(PaperRepository papers, PaperDeletion deletion, DraftService drafts) {
        this.papers = papers;
        this.deletion = deletion;
        this.drafts = drafts;
    }

    // a project with nothing left in it is already clear, so a retried or doubled delete still succeeds
    public void clear(UUID ownerId, UUID projectId) {
        papers.findByOwnerIdAndProjectId(ownerId, projectId).forEach(deletion::delete);
        drafts.deleteIfPresent(ownerId, projectId);
    }
}
