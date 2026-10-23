package com.g4t1.storage.draft;

import com.g4t1.storage.file.LocalFileStore;
import com.g4t1.storage.paper.Paper;
import com.g4t1.storage.paper.PaperRepository;
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

// Races that are hard to time through HTTP, so the repositories and file store are scripted here:
// two first uploads to one project, and a re-upload landing while Research Evaluation reads the PDF.
class DraftServiceRaceTest {

    private static final MockMultipartFile DRAFT = new MockMultipartFile(
            "file", "draft-v2.pdf", "application/pdf", "%PDF-1.7 draft".getBytes(StandardCharsets.US_ASCII));

    private final DraftRepository drafts = mock(DraftRepository.class);
    private final PaperRepository papers = mock(PaperRepository.class);
    private final LocalFileStore files = mock(LocalFileStore.class);
    private final DraftService service = new DraftService(
            drafts, papers, files, mock(PlatformTransactionManager.class), DataSize.ofKilobytes(1));

    private final UUID owner = UUID.randomUUID();
    private final UUID project = UUID.randomUUID();

    @Test
    void theLoserOfARaceReplacesTheWinnersFile() throws Exception {
        Draft winner = new Draft(owner, project, "winner.pdf", "draft.pdf");
        when(files.save(any())).thenReturn("loser.pdf");
        when(drafts.findForUpdateByOwnerIdAndProjectId(owner, project))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(drafts.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("uq_drafts_owner_project"));

        var stored = service.upload(owner, project, DRAFT);

        assertThat(stored.created()).isFalse();
        assertThat(stored.draft().filename()).isEqualTo("draft-v2.pdf");
        assertThat(winner.getFileKey()).isEqualTo("loser.pdf");
        verify(files).delete("winner.pdf");
        verify(files, never()).delete("loser.pdf");
    }

    @Test
    void aFailedRowWriteDeletesTheNewFileAndIsNotSwallowed() {
        var error = new DataIntegrityViolationException("something else");
        when(files.save(any())).thenReturn("new.pdf");
        when(drafts.findForUpdateByOwnerIdAndProjectId(owner, project)).thenReturn(Optional.empty());
        when(drafts.saveAndFlush(any())).thenThrow(error);

        assertThatThrownBy(() -> service.upload(owner, project, DRAFT)).isSameAs(error);
        verify(files).delete("new.pdf");
    }

    @Test
    void aReUploadBetweenReadingTheRowAndTheFileReadsTheNewFile() {
        UUID paperId = UUID.randomUUID();
        Paper tracked = new Paper(owner);
        tracked.setProjectId(project);
        byte[] newest = "%PDF-1.7 newest".getBytes(StandardCharsets.US_ASCII);
        when(papers.findById(paperId)).thenReturn(Optional.of(tracked));
        when(drafts.findByOwnerIdAndProjectId(owner, project))
                .thenReturn(Optional.of(new Draft(owner, project, "old.pdf", "draft.pdf")))
                .thenReturn(Optional.of(new Draft(owner, project, "new.pdf", "draft-v2.pdf")));
        when(files.read("old.pdf")).thenReturn(Optional.empty());
        when(files.read("new.pdf")).thenReturn(Optional.of(newest));

        assertThat(service.pdfForPaper(paperId)).isEqualTo(newest);
    }
}
