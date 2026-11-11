package com.g4t1.storage.user;

import com.g4t1.storage.chunk.ChunkService;
import com.g4t1.storage.draft.DraftService;
import com.g4t1.storage.paper.PaperDeletion;
import com.g4t1.storage.paper.PaperRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

// Accounts belong to User Management; storage only holds their papers, drafts and profile chunks
@Service
public class UserService {

    private final PaperRepository papers;
    private final PaperDeletion deletion;
    private final DraftService drafts;
    private final ChunkService chunks;

    public UserService(PaperRepository papers, PaperDeletion deletion, DraftService drafts, ChunkService chunks) {
        this.papers = papers;
        this.deletion = deletion;
        this.drafts = drafts;
        this.chunks = chunks;
    }

    // every project, the default one included; a user with nothing left is already clear. A paper's chunks
    // cascade with it; profile chunks have no foreign key to cascade from, so they're deleted here
    public void clear(UUID userId) {
        papers.findByOwnerIdOrderByCreatedAtDesc(userId).forEach(deletion::delete);
        drafts.deleteAllFor(userId);
        chunks.deleteProfile(userId);
    }
}
