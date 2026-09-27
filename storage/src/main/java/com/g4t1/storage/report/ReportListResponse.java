package com.g4t1.storage.report;

import java.util.List;

// wrapped in an object like the alert list, so fields can be added later without breaking callers
public record ReportListResponse(List<UserReportResponse> reports) {
}
