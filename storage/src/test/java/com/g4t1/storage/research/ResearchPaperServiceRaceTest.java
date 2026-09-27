package com.g4t1.storage.research;

import com.g4t1.storage.file.LocalFileStore;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.unit.DataSize;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Two first uploads to one project race past the lookup; the database's unique constraint
// rejects the second insert. Hard to time through HTTP, so the repository is scripted here.
class ResearchPaperServiceRaceTest {

    private static final MockMultipartFile DRAFT = new MockMultipartFile(
            "file", "draft-v2.pdf", "application/pdf", "%PDF-1.7 draft".getBytes(StandardCharsets.US_ASCII));

    private final ResearchPaperRepository researchPapers = mock(ResearchPaperRepository.class);
    private final LocalFileStore files = mock(LocalFileStore.class);
    private final ResearchPaperService service = new ResearchPaperService(
            researchPapers, files, mock(PlatformTransactionManager.class), DataSize.ofKilobytes(1));

    private final UUID owner = UUID.randomUUID();
    private final UUID folder = UUID.randomUUID();

    @Test
    void theLoserOfARaceReplacesTheWinnersFile() throws Exception {
        ResearchPaper winner = new ResearchPaper(owner, folder, "winner.pdf", "draft.pdf");
        when(files.save(any())).thenReturn("loser.pdf");
        when(researchPapers.findForUpdateByOwnerIdAndFolderId(owner, folder))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(researchPapers.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("uq_research_papers_owner_folder"));

        var stored = service.upload(owner, folder, DRAFT);

        assertThat(stored.created()).isFalse();
        assertThat(stored.researchPaper().filename()).isEqualTo("draft-v2.pdf");
        assertThat(winner.getFileKey()).isEqualTo("loser.pdf");
        verify(files).delete("winner.pdf");
        verify(files, never()).delete("loser.pdf");
    }

    @Test
    void aFailedRowWriteDeletesTheNewFileAndIsNotSwallowed() {
        var error = new DataIntegrityViolationException("something else");
        when(files.save(any())).thenReturn("new.pdf");
        when(researchPapers.findForUpdateByOwnerIdAndFolderId(owner, folder)).thenReturn(Optional.empty());
        when(researchPapers.saveAndFlush(any())).thenThrow(error);

        assertThatThrownBy(() -> service.upload(owner, folder, DRAFT)).isSameAs(error);
        verify(files).delete("new.pdf");
    }
}
