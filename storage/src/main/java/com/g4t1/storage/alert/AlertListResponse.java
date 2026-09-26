package com.g4t1.storage.alert;

import java.util.List;

// wrapped in an object rather than a bare array, 
//  so fields can be added later without breaking callers
//  and so json looks nice :)
public record AlertListResponse(List<AlertResponse> alerts) {
}
