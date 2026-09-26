package com.g4t1.storage.alert;

import java.util.List;

// the body of GET /alerts/{id}/notes: {"notes": [...]}, like AlertListResponse
public record AlertNoteListResponse(List<AlertNoteResponse> notes) {
}
