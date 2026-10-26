package com.g4t1.storage.project;

import com.g4t1.storage.dev.DevService;
import com.g4t1.storage.draft.DraftRepository;
import com.g4t1.storage.draft.DraftService;
import com.g4t1.storage.paper.PaperRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

// Projects belong to User Management; storage only holds their papers and drafts
@Service
public class ProjectService {

    private final PaperRepository papers;
    private final DevService dev;
    private final DraftRepository draftRows;
    private final DraftService drafts;

    public ProjectService(PaperRepository papers, DevService dev, DraftRepository draftRows, DraftService drafts) {
        this.papers = papers;
        this.dev = dev;
        this.draftRows = draftRows;
        this.drafts = drafts;
    }

    // a project with nothing left in it is already clear, so a retried delete still succeeds
    public void clear(UUID ownerId, UUID projectId) {
        papers.findByOwnerIdAndProjectId(ownerId, projectId)
                .forEach(paper -> dev.deletePaper(ownerId, paper.getId()));
        if (draftRows.findByOwnerIdAndProjectId(ownerId, projectId).isPresent()) {
            drafts.delete(ownerId, projectId);
        }
    }
}
