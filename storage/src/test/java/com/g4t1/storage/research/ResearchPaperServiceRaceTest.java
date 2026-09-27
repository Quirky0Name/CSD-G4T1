package com.g4t1.storage.research;

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
class ResearchPaperServiceRaceTest {

    private static final MockMultipartFile DRAFT = new MockMultipartFile(
            "file", "draft-v2.pdf", "application/pdf", "%PDF-1.7 draft".getBytes(StandardCharsets.US_ASCII));

    private final ResearchPaperRepository researchPapers = mock(ResearchPaperRepository.class);
    private final PaperRepository papers = mock(PaperRepository.class);
    private final LocalFileStore files = mock(LocalFileStore.class);
    private final ResearchPaperService service = new ResearchPaperService(
            researchPapers, papers, files, mock(PlatformTransactionManager.class), DataSize.ofKilobytes(1));

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

    @Test
    void aReUploadBetweenReadingTheRowAndTheFileReadsTheNewFile() {
        UUID paperId = UUID.randomUUID();
        Paper tracked = new Paper(owner);
        tracked.setFolderId(folder);
        byte[] newest = "%PDF-1.7 newest".getBytes(StandardCharsets.US_ASCII);
        when(papers.findById(paperId)).thenReturn(Optional.of(tracked));
        when(researchPapers.findByOwnerIdAndFolderId(owner, folder))
                .thenReturn(Optional.of(new ResearchPaper(owner, folder, "old.pdf", "draft.pdf")))
                .thenReturn(Optional.of(new ResearchPaper(owner, folder, "new.pdf", "draft-v2.pdf")));
        when(files.read("old.pdf")).thenReturn(Optional.empty());
        when(files.read("new.pdf")).thenReturn(Optional.of(newest));

        assertThat(service.pdfForPaper(paperId)).isEqualTo(newest);
    }
}
