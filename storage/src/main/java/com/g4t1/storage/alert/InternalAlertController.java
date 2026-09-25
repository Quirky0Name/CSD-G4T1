package com.g4t1.storage.alert;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

// Controller for RE
@RestController
@RequestMapping("/internal/papers/{paperId}/alerts")
public class InternalAlertController {

    private final AlertService alerts;

    public InternalAlertController(AlertService alerts) {
        this.alerts = alerts;
    }

    @PostMapping
    public ResponseEntity<AlertResponse> store(@PathVariable UUID paperId, @Valid @RequestBody NewAlertRequest request) {
        AlertService.StoredAlert stored = alerts.store(paperId, request);
        return ResponseEntity.status(stored.created() ? HttpStatus.CREATED : HttpStatus.OK).body(stored.alert());
    }
}
