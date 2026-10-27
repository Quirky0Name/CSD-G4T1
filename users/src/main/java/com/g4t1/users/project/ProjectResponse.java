package com.g4t1.users.project;

import java.time.Instant;
import java.util.UUID;

public record ProjectResponse(UUID id, String name, Instant createdAt) {

    static ProjectResponse from(Project project) {
        return new ProjectResponse(project.getId(), project.getName(), project.getCreatedAt());
    }
}
