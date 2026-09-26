package com.g4t1.storage.alert;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// Controller for client
@RestController
public class AlertController {

    private final AlertService alerts;

    public AlertController(AlertService alerts) {
        this.alerts = alerts;
    }

    // client GET alerts (have to iterate for each of its paper)
    @GetMapping("/papers/{paperId}/alerts")
    public AlertListResponse list(@AuthenticationPrincipal UUID userId,
                                  @PathVariable UUID paperId,
                                  @RequestParam(name = "include_dismissed", defaultValue = "false") boolean includeDismissed) {
        return new AlertListResponse(alerts.listForPaper(userId, paperId, includeDismissed));
    }

    // for user to acknowldge/reject alert
    @PatchMapping("/alerts/{alertId}")
    public AlertResponse changeStatus(@AuthenticationPrincipal UUID userId,
                                      @PathVariable long alertId,
                                      @Valid @RequestBody StatusChangeRequest request) {
        return alerts.changeStatus(userId, alertId, request.status());
    }

    @PostMapping("/alerts/{alertId}/notes")
    @ResponseStatus(HttpStatus.CREATED)
    public AlertNoteResponse addNote(@AuthenticationPrincipal UUID userId,
                                     @PathVariable long alertId,
                                     @Valid @RequestBody NewNoteRequest request) {
        return alerts.addNote(userId, alertId, request.text());
    }

    @GetMapping("/alerts/{alertId}/notes")
    public AlertNoteListResponse notes(@AuthenticationPrincipal UUID userId, @PathVariable long alertId) {
        return new AlertNoteListResponse(alerts.notes(userId, alertId));
    }
}
