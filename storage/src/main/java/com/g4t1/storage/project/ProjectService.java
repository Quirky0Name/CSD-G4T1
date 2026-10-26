package com.g4t1.storage.project;

import com.g4t1.storage.dev.DevService;
import com.g4t1.storage.draft.DraftService;
import com.g4t1.storage.paper.PaperRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

// Projects belong to User Management; storage only holds their papers and drafts
@Service
public class ProjectService {

    private final PaperRepository papers;
    private final DevService dev;
    private final DraftService drafts;

    public ProjectService(PaperRepository papers, DevService dev, DraftService drafts) {
        this.papers = papers;
        this.dev = dev;
        this.drafts = drafts;
    }

    public void clear(UUID ownerId, UUID projectId) {
        papers.findByOwnerIdAndProjectId(ownerId, projectId)
                .forEach(paper -> dev.deletePaper(ownerId, paper.getId()));
        drafts.delete(ownerId, projectId);
    }
}
