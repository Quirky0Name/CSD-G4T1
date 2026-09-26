package com.g4t1.storage.alert;

import com.g4t1.storage.paper.PaperRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
public class AlertService {

    // newest first; within one detection time, Severity's declared order (high first), then the newer id
    private static final Comparator<Alert> LIST_ORDER = Comparator
            .comparing(Alert::getDetectedAt, Comparator.reverseOrder())
            .thenComparing(Alert::getSeverity)
            .thenComparing(Alert::getId, Comparator.reverseOrder());

    private final AlertRepository alerts;
    private final AlertNoteRepository notes;
    private final PaperRepository papers;

    public AlertService(AlertRepository alerts, AlertNoteRepository notes, PaperRepository papers) {
        this.alerts = alerts;
        this.notes = notes;
        this.papers = papers;
    }

    /**
     * Store alert (idempotent -> check w change_key)
     *
     * If two same requests come at once -> first is stored
     */
    public StoredAlert store(UUID paperId, NewAlertRequest request) {
        requirePaper(paperId);
        var existing = alerts.findByPaperIdAndChangeKey(paperId, request.changeKey());
        if (existing.isPresent()) {
            return new StoredAlert(AlertResponse.from(existing.get()), false);
        }
        try {
            return new StoredAlert(AlertResponse.from(alerts.saveAndFlush(new Alert(paperId, request))), true);
        } catch (DataIntegrityViolationException e) {
            // lost the race; anything else (e.g. the paper was deleted meanwhile) is a real error
            return alerts.findByPaperIdAndChangeKey(paperId, request.changeKey())
                    .map(winner -> new StoredAlert(AlertResponse.from(winner), false))
                    .orElseThrow(() -> e);
        }
    }

    /**
     * Every change key stored for the paper, whatever the alert's status. Research Evaluation
     * checks these before evaluating, so a change it has already evaluated is never evaluated
     * again. An unknown paper is the same "No paper" 404 as when storing.
     */
    public List<String> changeKeys(UUID paperId) {
        requirePaper(paperId);
        return alerts.findChangeKeysByPaperId(paperId);
    }

    /**
     * The paper's alerts, newest first, for its owner. Alerts from the same poll share a detection
     * time, and among them the most severe comes first. Dismissed alerts are left out unless asked
     * for. A paper that doesn't exist and one that belongs to someone else are the same 404, so the
     * response never tells a user whether another user's paper exists.
     */
    public List<AlertResponse> listForPaper(UUID userId, UUID paperId, boolean includeDismissed) {
        requireOwnPaper(userId, paperId);
        List<Alert> found = includeDismissed
                ? alerts.findByPaperId(paperId)
                : alerts.findByPaperIdAndStatusNot(paperId, AlertStatus.DISMISSED);
        return found.stream().sorted(LIST_ORDER).map(AlertResponse::from).toList();
    }

    /**
     * Records the researcher's response to an alert. Either status can replace any other; setting
     * the status the alert already has changes nothing, so status_changed_at keeps the time of the
     * real change. A missing alert and one on another user's paper are the same 404.
     */
    @Transactional
    public AlertResponse changeStatus(UUID userId, long alertId, AlertStatus status) {
        if (status == AlertStatus.NEW) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status must be acknowledged or dismissed");
        }
        Alert alert = requireOwnAlert(userId, alertId);
        if (alert.getStatus() != status) {
            alert.setStatus(status);
            // Postgres keeps microseconds; truncating makes this response match later reads
            alert.setStatusChangedAt(Instant.now().truncatedTo(ChronoUnit.MICROS));
        }
        return AlertResponse.from(alert);
    }

    /**
     * Adds a note to the researcher's log on an alert, e.g. what they did about it. Notes are
     * separate from the status: adding one never changes it.
     */
    public AlertNoteResponse addNote(UUID userId, long alertId, String text) {
        Alert alert = requireOwnAlert(userId, alertId);
        return AlertNoteResponse.from(notes.save(new AlertNote(alert.getId(), text)));
    }

    /**
     * The alert's notes, newest first.
     */
    public List<AlertNoteResponse> notes(UUID userId, long alertId) {
        requireOwnAlert(userId, alertId);
        return notes.findByAlertIdOrderByCreatedAtDescIdDesc(alertId).stream().map(AlertNoteResponse::from).toList();
    }

    // a missing alert and one on another user's paper are the same 404
    private Alert requireOwnAlert(UUID userId, long alertId) {
        return alerts.findById(alertId)
                .filter(found -> ownsPaper(userId, found.getPaperId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No alert " + alertId));
    }

    private boolean ownsPaper(UUID userId, UUID paperId) {
        return papers.findById(paperId).map(paper -> paper.getOwnerId().equals(userId)).orElse(false);
    }

    private void requirePaper(UUID paperId) {
        if (!papers.existsById(paperId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No paper " + paperId);
        }
    }

    private void requireOwnPaper(UUID userId, UUID paperId) {
        if (!ownsPaper(userId, paperId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No paper " + paperId);
        }
    }

    public record StoredAlert(AlertResponse alert, boolean created) {
    }
}
