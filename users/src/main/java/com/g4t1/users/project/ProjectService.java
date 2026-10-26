package com.g4t1.users.project;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class ProjectService {

    private final ProjectRepository projects;

    public ProjectService(ProjectRepository projects) {
        this.projects = projects;
    }

    public ProjectResponse create(UUID ownerId, ProjectRequest request) {
        return ProjectResponse.from(projects.save(new Project(ownerId, request.name())));
    }

    public List<ProjectResponse> list(UUID ownerId) {
        return projects.findByOwnerIdOrderByCreatedAtDesc(ownerId).stream().map(ProjectResponse::from).toList();
    }
}
