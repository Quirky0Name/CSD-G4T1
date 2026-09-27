package com.g4t1.storage.paper;

import java.util.List;

// an object rather than a bare array, like AlertListResponse, so fields can be added later
public record PaperListResponse(List<PaperResponse> papers) {
}
