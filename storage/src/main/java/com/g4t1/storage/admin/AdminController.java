package com.g4t1.storage.admin;

import com.g4t1.storage.alert.AlertListResponse;
import com.g4t1.storage.alert.AlertService;
import com.g4t1.storage.report.ReportListResponse;
import com.g4t1.storage.report.ReportService;
import com.g4t1.storage.snapshot.SnapshotResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

// Dev mode only, admin token only (SecurityConfig), on any researcher's papers
@RestController
@ConditionalOnBooleanProperty("storage.dev-mode")
@RequestMapping("/admin")
public class AdminController {

    private final AdminService admin;
    private final AlertService alerts;
    private final ReportService reports;

    public AdminController(AdminService admin, AlertService alerts, ReportService reports) {
        this.admin = admin;
        this.alerts = alerts;
        this.reports = reports;
    }

    @GetMapping("/users/{ownerId}/papers")
    public Map<String, List<AdminPaperResponse>> papers(@PathVariable UUID ownerId) {
        return Map.of("papers", admin.papersOf(ownerId));
    }

    @GetMapping("/papers/{paperId}/snapshots")
    public Map<String, List<SnapshotResponse>> snapshots(@PathVariable UUID paperId) {
        return Map.of("snapshots", admin.snapshotsOf(paperId));
    }

    @GetMapping("/papers/{paperId}/alerts")
    public AlertListResponse alerts(@PathVariable UUID paperId) {
        return new AlertListResponse(alerts.listAllForPaper(paperId));
    }

    @GetMapping("/papers/{paperId}/reports")
    public ReportListResponse reports(@PathVariable UUID paperId) {
        return new ReportListResponse(reports.listForPaper(paperId));
    }

    @DeleteMapping("/papers/{paperId}/history")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearHistory(@PathVariable UUID paperId) {
        admin.clearHistory(paperId);
    }
}
