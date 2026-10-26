package com.g4t1.storage.user;

import com.g4t1.storage.draft.DraftService;
import com.g4t1.storage.paper.PaperDeletion;
import com.g4t1.storage.paper.PaperRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

// Accounts belong to User Management; storage only holds their papers and drafts
@Service
public class UserService {

    private final PaperRepository papers;
    private final PaperDeletion deletion;
    private final DraftService drafts;

    public UserService(PaperRepository papers, PaperDeletion deletion, DraftService drafts) {
        this.papers = papers;
        this.deletion = deletion;
        this.drafts = drafts;
    }

    // every project, the default one included; a user with nothing left is already clear
    public void clear(UUID userId) {
        papers.findByOwnerIdOrderByCreatedAtDesc(userId).forEach(deletion::delete);
        drafts.deleteAllFor(userId);
    }
}
